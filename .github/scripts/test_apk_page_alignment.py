import struct
import unittest
from verify_apk_page_alignment import inspect_elf


def elf(alignment=16384, relro_size=16384):
    data = bytearray(256)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 256, 256, alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 16384, 16384, 0, 0, relro_size, 1)
    return bytes(data)


class AlignmentTest(unittest.TestCase):
    def test_compatible_native_library(self):
        self.assertEqual(inspect_elf(elf())["status"], "PASS")

    def test_4k_load_segment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "LOAD segment"):
            inspect_elf(elf(alignment=4096))

    def test_relro_end_must_also_be_aligned(self):
        with self.assertRaisesRegex(ValueError, "GNU_RELRO"):
            inspect_elf(elf(relro_size=4096))

    def test_truncated_header_rejected(self):
        with self.assertRaises(ValueError):
            inspect_elf(elf()[:100])


if __name__ == "__main__":
    unittest.main()
