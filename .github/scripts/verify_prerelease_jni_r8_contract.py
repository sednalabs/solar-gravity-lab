#!/usr/bin/env python3
"""Verify that the packaged APK preserves Kotlin DTO names constructed by Rust JNI."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FFI_SOURCE = ROOT / "engine/ffi/src/lib.rs"
RUNTIME_PREFIX = "com.sednalabs.solarlab.runtime.Native"
JNI_CLASS_CONSTANT = re.compile(
    r'const\s+CLASS_NATIVE_[A-Z0-9_]+:\s*&str\s*=\s*"(?P<name>[^"]+)";'
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
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


def defined_dex_classes_from_output(output: str) -> set[str]:
    classes = {
        line.split()[-1]
        for line in output.splitlines()
        if line.startswith("C ") and len(line.split()) >= 6
    }
    if not classes:
        raise RuntimeError("apkanalyzer found no DEX class declarations")
    return classes


def defined_dex_classes(apk: Path) -> set[str]:
    try:
        completed = subprocess.run(
            ["apkanalyzer", "dex", "packages", "--defined-only", str(apk)],
            check=False,
            capture_output=True,
            encoding="utf-8",
        )
    except OSError as error:
        raise RuntimeError(f"apkanalyzer could not start: {error}") from error
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "no diagnostic output"
        raise RuntimeError(f"apkanalyzer failed for {apk}: {detail}")

    try:
        return defined_dex_classes_from_output(completed.stdout)
    except RuntimeError as error:
        raise RuntimeError(f"{error} in {apk}") from error


def missing_jni_dto_classes(ffi_source: Path, defined_classes: set[str]) -> set[str]:
    return set(required_jni_dto_classes(ffi_source)).difference(defined_classes)


def main() -> int:
    args = parse_args()
    if not args.apk.is_file():
        print(f"Packaged APK is missing: {args.apk}", file=sys.stderr)
        return 1
    if not args.ffi_source.is_file():
        print(f"Rust FFI source is missing: {args.ffi_source}", file=sys.stderr)
        return 1

    try:
        required = required_jni_dto_classes(args.ffi_source)
        defined_classes = defined_dex_classes(args.apk)
    except (RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    missing = missing_jni_dto_classes(args.ffi_source, defined_classes)
    if missing:
        details = "\n".join(f"- {class_name}" for class_name in sorted(missing))
        print(
            "The packaged APK is missing Kotlin DTO names constructed by Rust JNI:\n" + details,
            file=sys.stderr,
        )
        return 1

    print(f"JNI/R8 DTO contract passed for {len(required)} runtime classes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
