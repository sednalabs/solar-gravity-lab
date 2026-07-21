#!/usr/bin/env python3
"""Verify that R8 preserves all Kotlin DTO names constructed by Rust JNI."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FFI_SOURCE = ROOT / "engine/ffi/src/lib.rs"
RUNTIME_PREFIX = "com.sednalabs.solarlab.runtime.Native"
JNI_CLASS_CONSTANT = re.compile(
    r'const\s+CLASS_NATIVE_[A-Z0-9_]+:\s*&str\s*=\s*"(?P<name>[^"]+)";'
)
MAPPING_CLASS = re.compile(r"^(?P<source>\S+) -> (?P<target>\S+):$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--ffi-source", type=Path, default=DEFAULT_FFI_SOURCE)
    return parser.parse_args()


def required_jni_dto_classes(ffi_source: Path) -> list[str]:
    source = ffi_source.read_text(encoding="utf-8")
    classes = sorted(
        {
            match.group("name").replace("/", ".")
            for match in JNI_CLASS_CONSTANT.finditer(source)
            if match.group("name").replace("/", ".").startswith(RUNTIME_PREFIX)
        }
    )
    if not classes:
        raise ValueError(f"No Rust JNI DTO class constants found in {ffi_source}")
    return classes


def mapped_class_names(mapping: Path) -> dict[str, str]:
    mappings: dict[str, str] = {}
    for line in mapping.read_text(encoding="utf-8").splitlines():
        match = MAPPING_CLASS.match(line)
        if match is not None:
            mappings[match.group("source")] = match.group("target")
    return mappings


def renamed_jni_dto_classes(ffi_source: Path, mapping: Path) -> dict[str, str | None]:
    required = required_jni_dto_classes(ffi_source)
    mapped = mapped_class_names(mapping)
    return {
        class_name: mapped.get(class_name)
        for class_name in required
        if mapped.get(class_name) != class_name
    }


def main() -> int:
    args = parse_args()
    if not args.mapping.is_file():
        print(f"R8 mapping is missing: {args.mapping}", file=sys.stderr)
        return 1
    if not args.ffi_source.is_file():
        print(f"Rust FFI source is missing: {args.ffi_source}", file=sys.stderr)
        return 1

    try:
        required = required_jni_dto_classes(args.ffi_source)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    renamed = renamed_jni_dto_classes(args.ffi_source, args.mapping)
    if renamed:
        details = "\n".join(
            f"- {class_name}: {target if target is not None else 'missing from mapping'}"
            for class_name, target in renamed.items()
        )
        print(
            "R8 changed or removed Kotlin DTO names constructed by Rust JNI:\n" + details,
            file=sys.stderr,
        )
        return 1

    print(f"JNI/R8 DTO contract passed for {len(required)} runtime classes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
