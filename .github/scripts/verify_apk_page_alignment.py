#!/usr/bin/env python3
"""Check native ELF 16 KiB segment/RELRO compatibility, separately from execution."""
import argparse
import json
from pathlib import Path
import struct
import sys
import zipfile

PAGE = 16384


def inspect_elf(data: bytes) -> dict:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 library")
    machine = struct.unpack_from("<H", data, 18)[0]
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if not count or entry_size < 56 or offset + entry_size * count > len(data):
        raise ValueError("Malformed program header table")
    loads, relro = [], []
    for i in range(count):
        kind, flags, file_offset, address, _physical, file_size, memory_size, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + i * entry_size)
        if kind == 1:
            if alignment < PAGE or alignment & (alignment - 1) or (address - file_offset) % PAGE:
                raise ValueError(f"LOAD segment {i} is not 16 KiB compatible: align={alignment}")
            loads.append({"offset": file_offset, "virtual_address": address, "alignment": alignment,
                          "file_bytes": file_size, "memory_bytes": memory_size, "flags": flags})
        elif kind == 0x6474E552:
            end = address + memory_size
            if end % PAGE:
                raise ValueError(f"GNU_RELRO end is not 16 KiB aligned: {end:#x}")
            relro.append({"virtual_address": address, "memory_bytes": memory_size, "end": end})
    if not loads:
        raise ValueError("ELF contains no LOAD segments")
    return {"machine": machine, "load_segments": loads, "relro_segments": relro, "status": "PASS"}


def inspect_apk(apk: Path) -> dict:
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                result = inspect_elf(archive.read(entry))
                result.update(path=entry.filename, compressed=entry.compress_type != zipfile.ZIP_STORED)
                libraries.append(result)
    if not libraries:
        raise ValueError("APK contains no native libraries")
    return {"schema_version": 1, "apk": apk.name, "required_page_bytes": PAGE,
            "status": "PASS", "libraries": libraries,
            "scope": "ELF structural compatibility; ZIP alignment and device execution are separate gates"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = inspect_apk(args.apk)
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        print(f"Native page alignment failed: {error}", file=sys.stderr)
        return 1
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
