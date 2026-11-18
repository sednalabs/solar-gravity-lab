import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from verify_apk_page_alignment import PAGE, PT_GNU_RELRO, PT_LOAD, inspect_apk, inspect_elf


def header(kind, address, size, *, offset=None, file_size=None, flags=6, alignment=PAGE):
    return (kind, flags, address if offset is None else offset, address, address,
            size if file_size is None else file_size, size, alignment)


def relro(address, size, **kwargs):
    return header(PT_GNU_RELRO, address, size, flags=4, alignment=1, **kwargs)


def elf(*headers, machine=183):
    # Include real file extents to exercise the bounds check.
    size = max(64 + 56 * len(headers), *(h[2] + h[5] for h in headers))
    data = bytearray(size)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, len(headers))
    for i, value in enumerate(headers):
        struct.pack_into("<IIQQQQQQ", data, 64 + 56 * i, *value)
    return bytes(data)


def graphics_path(machine=183):
    # Exact LOAD/RELRO headers from graphics-path 1.0.1 in source 2d5a9d6's
    # debug APK. RELRO includes LLD padding beyond the RW LOAD's exact end.
    # https://developer.android.com/guide/practices/page-sizes
    if machine == 183:
        code_size, data_size, relro_size = 0x1C40, 0x390, 0x3C0
    else:
        code_size, data_size, relro_size = 0x1EC0, 0x3A8, 0x1140
    data_address = code_size + PAGE
    bss_offset = code_size + data_size
    return [
        header(PT_LOAD, 0, code_size, flags=5),
        header(PT_LOAD, data_address, data_size, offset=code_size),
        header(PT_LOAD, bss_offset + 2 * PAGE, 0x10, offset=bss_offset, file_size=0),
        relro(data_address, relro_size, offset=code_size, file_size=data_size),
    ]


class AlignmentTest(unittest.TestCase):
    def test_no_relro_is_structurally_compatible(self):
        self.assertEqual(inspect_elf(elf(header(PT_LOAD, 0, 1024)))["status"], "PASS")

    def test_aligned_relro_leaves_trailing_writable_data(self):
        result = inspect_elf(elf(header(PT_LOAD, PAGE, 2 * PAGE), relro(PAGE, PAGE)))
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["relro_segments"][0]["qualification"], "aligned-end")

    def test_android_documented_isolated_unaligned_tail(self):
        # Android guide example 4, including a safe following writable LOAD.
        result = inspect_elf(elf(
            header(PT_LOAD, 0, 0x680, flags=5),
            header(PT_LOAD, 0x4680, 0x980, offset=0x680, file_size=0x1D8),
            relro(0x4680, 0x980, offset=0x680, file_size=0x1D8),
            header(PT_LOAD, 0x8000, 16, offset=0x4000, file_size=0),
        ))
        self.assertEqual(result["relro_segments"][0]["end"], 0x5000)
        self.assertEqual(result["status"], "PASS")

    def test_actual_androidx_headers_and_lld_padding(self):
        for machine, raw_end, padding in ((183, 0x6000, 0x30), (62, 0x7000, 0xD98)):
            with self.subTest(machine=machine):
                result = inspect_elf(elf(*graphics_path(machine), machine=machine))
                segment = result["relro_segments"][0]
                self.assertEqual(result["status"], "PASS")
                self.assertEqual(segment["end"], raw_end)
                self.assertEqual(segment["padding_bytes"], padding)
                self.assertEqual((segment["protection_start"], segment["protection_end"]), (0x4000, 0x8000))

    def test_relro_header_before_loads_is_supported(self):
        headers = graphics_path()
        self.assertEqual(inspect_elf(elf(headers[-1], *headers[:-1]))["status"], "PASS")

    def test_android_documented_unsafe_trailing_data(self):
        # Android guide example 1: 0xe1000..0xe4000 must remain writable.
        with self.assertRaisesRegex(ValueError, "non-RELRO writable bytes"):
            inspect_elf(elf(
                header(PT_LOAD, 0, 0xCFAF0, flags=5),
                header(PT_LOAD, 0xDFAF0, 0x4510, offset=0xCFAF0, file_size=0x2510),
                relro(0xDFAF0, 0x1510, offset=0xCFAF0),
            ))

    def test_unaligned_relro_cannot_protect_mutable_tail(self):
        with self.assertRaisesRegex(ValueError, "non-RELRO writable bytes"):
            inspect_elf(elf(header(PT_LOAD, PAGE, PAGE), relro(PAGE, 4096)))

    def test_aligned_end_does_not_hide_mutable_prefix(self):
        with self.assertRaisesRegex(ValueError, "non-RELRO writable bytes"):
            inspect_elf(elf(header(PT_LOAD, PAGE, PAGE), relro(PAGE + 2048, PAGE - 2048)))

    def test_rounding_cannot_protect_another_writable_load(self):
        for extra_address in (0x5000, 0x6000, 0x7FF0):
            with self.subTest(extra_address=extra_address):
                with self.assertRaisesRegex(ValueError, "LOAD segment 2"):
                    inspect_elf(elf(
                        header(PT_LOAD, PAGE, 4096), relro(PAGE, 4096),
                        header(PT_LOAD, extra_address, 16),
                    ))

    def test_lld_padding_cannot_disguise_another_writable_load(self):
        with self.assertRaisesRegex(ValueError, "non-RELRO writable bytes"):
            inspect_elf(elf(
                header(PT_LOAD, PAGE, 4096),
                relro(PAGE, 8192, file_size=4096),
                header(PT_LOAD, 0x5800, 16),
            ))

    def test_relro_padding_cannot_reach_an_unmapped_page(self):
        with self.assertRaisesRegex(ValueError, "not contained"):
            inspect_elf(elf(header(PT_LOAD, PAGE, 4096), relro(PAGE, PAGE + 1, file_size=4096)))

    def test_uncontained_aligned_relro_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not contained"):
            inspect_elf(elf(header(PT_LOAD, 0, 1024), relro(PAGE, PAGE, file_size=0)))

    def test_4k_and_non_power_of_two_load_alignment_are_rejected(self):
        for alignment in (4096, 24576):
            with self.subTest(alignment=alignment):
                with self.assertRaisesRegex(ValueError, "LOAD segment"):
                    inspect_elf(elf(header(PT_LOAD, 0, 1024, alignment=alignment)))

    def test_incongruent_load_address_and_offset_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "LOAD segment"):
            inspect_elf(elf(header(PT_LOAD, PAGE, 1024, offset=1)))

    def test_truncated_header_rejected(self):
        with self.assertRaises(ValueError):
            inspect_elf(elf(*graphics_path())[:100])

    def test_truncated_file_extent_rejected(self):
        with self.assertRaisesRegex(ValueError, "file/memory extent"):
            inspect_elf(elf(header(PT_LOAD, 0, 1024))[:-1])

    def test_apk_report_keeps_all_library_names_and_headers_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/good.so", elf(*graphics_path()))
                archive.writestr("lib/arm64-v8a/bad.so", elf(
                    header(PT_LOAD, PAGE, PAGE), relro(PAGE, 4096)))
                archive.writestr("lib/x86_64/malformed.so", b"invalid")
            result = inspect_apk(apk)
            self.assertEqual(result["status"], "FAIL")
            self.assertEqual([lib["status"] for lib in result["libraries"]], ["PASS", "FAIL", "FAIL"])
            bad = result["libraries"][1]
            self.assertEqual(bad["path"], "lib/arm64-v8a/bad.so")
            self.assertEqual(len(bad["sha256"]), 64)
            self.assertEqual(len(bad["load_segments"]), 1)
            self.assertEqual(len(bad["relro_segments"]), 1)

            output = Path(directory) / "alignment.json"
            process = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_apk_page_alignment.py")),
                                      str(apk), "--output", str(output)], capture_output=True, text=True, check=False)
            self.assertEqual(process.returncode, 1)
            self.assertIn("lib/arm64-v8a/bad.so", process.stderr)
            self.assertEqual(json.loads(output.read_text())["status"], "FAIL")

    def test_invalid_zip_still_writes_failure_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, output = Path(directory) / "bad.apk", Path(directory) / "alignment.json"
            apk.write_bytes(b"invalid")
            process = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_apk_page_alignment.py")),
                                      str(apk), "--output", str(output)], capture_output=True, text=True, check=False)
            self.assertEqual(process.returncode, 1)
            self.assertEqual(json.loads(output.read_text())["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
