#!/usr/bin/env python3
"""Check native ELF 16 KiB segment/RELRO compatibility, separately from execution."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


class ElfAlignmentError(ValueError):
    """Retain decoded headers so an APK failure identifies the actual library."""

    def __init__(self, report: dict):
        self.report = report
        super().__init__("; ".join(report["errors"]))


def page_end(address: int) -> int:
    return (address + PAGE - 1) // PAGE * PAGE


def covered(start: int, end: int, ranges: list[tuple[int, int]]) -> bool:
    """Whether a byte interval is entirely declared RELRO data (not padding)."""
    cursor = start
    for left, right in sorted(ranges):
        if left > cursor:
            break
        cursor = max(cursor, right)
        if cursor >= end:
            return True
    return cursor >= end


def inspect_elf(data: bytes) -> dict:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 library")
    machine = struct.unpack_from("<H", data, 18)[0]
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if not count or entry_size < 56 or offset + entry_size * count > len(data):
        raise ValueError("Malformed program header table")
    loads, relro, errors = [], [], []
    for i in range(count):
        kind, flags, file_offset, address, _physical, file_size, memory_size, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + i * entry_size)
        segment = {"index": i, "offset": file_offset, "virtual_address": address,
                   "alignment": alignment, "file_bytes": file_size,
                   "memory_bytes": memory_size, "flags": flags,
                   "end": address + memory_size}
        if kind in (PT_LOAD, PT_GNU_RELRO):
            if address + memory_size >= 1 << 64:
                errors.append(f"Segment {i} virtual address range overflows ELF64")
            if file_size > memory_size or file_offset + file_size > len(data):
                errors.append(f"Segment {i} has an invalid file/memory extent")
        if kind == PT_LOAD:
            if alignment < PAGE or alignment & (alignment - 1) or (address - file_offset) % PAGE:
                errors.append(f"LOAD segment {i} is not 16 KiB compatible: align={alignment}")
            loads.append(segment)
        elif kind == PT_GNU_RELRO:
            relro.append(segment)
    if not loads:
        errors.append("ELF contains no LOAD segments")

    # Bionic protects [page_start(vaddr), page_end(vaddr + memsz)). A raw
    # non-16-KiB RELRO end is valid if rounding cannot protect mutable data:
    # https://developer.android.com/guide/practices/page-sizes
    # https://android.googlesource.com/platform/bionic/+/main/linker/linker_phdr.cpp
    # LLD also emits RELRO padding beyond its LOAD's exact end; permit only
    # padding inside that LOAD's mapped final page, never an unmapped gap:
    # https://github.com/llvm/llvm-project/blob/llvmorg-17.0.6/lld/ELF/Writer.cpp
    # (Writer::setPhdrs rounds PT_GNU_RELRO p_memsz to commonPageSize.)
    # Use a second pass: program-header order must not hide a later RW LOAD.
    relro_data = []
    for segment in relro:
        start, end = segment["virtual_address"], segment["end"]
        segment["protection_start"] = start // PAGE * PAGE
        segment["protection_end"] = page_end(end)
        owners = [load for load in loads
                  if load["virtual_address"] <= start < load["end"]
                  and end <= page_end(load["end"])
                  and segment["offset"] - load["offset"] == start - load["virtual_address"]
                  and segment["offset"] + segment["file_bytes"] <= load["offset"] + load["file_bytes"]]
        if len(owners) != 1 or not segment["memory_bytes"]:
            errors.append(f"GNU_RELRO segment {segment['index']} is not contained in one LOAD mapping")
            continue
        owner = owners[0]
        segment["containing_load_index"] = owner["index"]
        segment["load_end"] = owner["end"]
        segment["padding_bytes"] = max(0, end - owner["end"])
        segment["qualification"] = "aligned-end" if end % PAGE == 0 else "isolated-load-tail"
        # Only the owner's actual bytes are declared read-only; do not let
        # linker padding disguise mutable bytes from a different LOAD.
        relro_data.append((start, min(end, owner["end"])))

    for segment in relro:
        for load in loads:
            if not load["flags"] & PF_W:
                continue
            start = max(segment["protection_start"], load["virtual_address"])
            end = min(segment["protection_end"], load["end"])
            if start < end and not covered(start, end, relro_data):
                errors.append(
                    f"GNU_RELRO segment {segment['index']} rounded protection "
                    f"[{segment['protection_start']:#x}, {segment['protection_end']:#x}) "
                    f"overlaps non-RELRO writable bytes in LOAD segment {load['index']} "
                    f"within [{start:#x}, {end:#x})"
                )

    report = {"machine": machine, "load_segments": loads, "relro_segments": relro,
              "status": "FAIL" if errors else "PASS", "errors": errors}
    if errors:
        raise ElfAlignmentError(report)
    return report


def inspect_apk(apk: Path) -> dict:
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                data = archive.read(entry)
                try:
                    result = inspect_elf(data)
                except ElfAlignmentError as error:
                    result = error.report
                except (ValueError, struct.error) as error:
                    result = {"status": "FAIL", "errors": [str(error)]}
                result.update(path=entry.filename, compressed=entry.compress_type != zipfile.ZIP_STORED,
                              bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                libraries.append(result)
    if not libraries:
        raise ValueError("APK contains no native libraries")
    return {"schema_version": 2, "apk": apk.name, "required_page_bytes": PAGE,
            "status": "PASS" if all(lib["status"] == "PASS" for lib in libraries) else "FAIL",
            "libraries": libraries,
            "scope": "ELF structural compatibility; ZIP alignment and device execution are separate gates"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = inspect_apk(args.apk)
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        result = {"schema_version": 2, "apk": args.apk.name, "status": "FAIL", "errors": [str(error)]}
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text, end="")
    if result["status"] != "PASS":
        for library in result.get("libraries", []):
            for error in library.get("errors", []):
                print(f"Native page alignment failed: {library['path']}: {error}", file=sys.stderr)
        for error in result.get("errors", []):
            print(f"Native page alignment failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
