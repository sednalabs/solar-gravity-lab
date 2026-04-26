#!/usr/bin/env python3
"""Verify Arm64 capability catalogs stay aligned across tooling layers."""

from __future__ import annotations

import ast
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PYTHON_COLLECTOR = ROOT / ".github/scripts/collect_android_capability_census.py"
RUST_PHYSICS = ROOT / "engine/physics/src/lib.rs"
KOTLIN_RUNTIME_BRIDGE = (
    ROOT / "clients/android/app/src/main/kotlin/com/sednalabs/solarlab/runtime/RuntimeBridge.kt"
)


def python_feature_order() -> list[str]:
    module = ast.parse(PYTHON_COLLECTOR.read_text(encoding="utf-8"))
    for node in module.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id == "FEATURE_ORDER":
                    value = ast.literal_eval(node.value)
                    return list(value)
    raise AssertionError("FEATURE_ORDER not found in Python collector")


def rust_catalog() -> dict[str, int]:
    contents = RUST_PHYSICS.read_text(encoding="utf-8")
    constants = {
        name: int(bit)
        for name, bit in re.findall(
            r"pub const (CPU_FEATURE_[A-Z0-9_]+): u64 = 1 << (\d+);", contents
        )
    }
    entries: dict[str, int] = {}
    for name, flag in re.findall(
        r'canonical_name: "([^"]+)",\s+flag: (CPU_FEATURE_[A-Z0-9_]+),',
        contents,
        flags=re.MULTILINE,
    ):
        if flag not in constants:
            raise AssertionError(f"Rust catalog entry {name} references unknown {flag}")
        entries[name] = constants[flag]
    if not entries:
        raise AssertionError("No Rust Arm64 CPU feature catalog entries found")
    return entries


def kotlin_summary_catalog() -> dict[str, int]:
    contents = KOTLIN_RUNTIME_BRIDGE.read_text(encoding="utf-8")
    constants = {
        name: int(bit)
        for name, bit in re.findall(
            r"private const val (CPU_FEATURE_[A-Z0-9_]+) = 1L shl (\d+)", contents
        )
    }
    entries: dict[str, int] = {}
    for constant, label in re.findall(
        r'addIfPresent\((CPU_FEATURE_[A-Z0-9_]+), "([^"]+)"\)', contents
    ):
        if constant not in constants:
            raise AssertionError(f"Kotlin summary entry {label} references unknown {constant}")
        entries[label] = constants[constant]
    if not entries:
        raise AssertionError("No Kotlin CPU feature summary entries found")
    return entries


def main() -> int:
    python_features = python_feature_order()
    rust_features = rust_catalog()
    kotlin_features = kotlin_summary_catalog()

    python_set = set(python_features)
    rust_set = set(rust_features)
    kotlin_set = set(kotlin_features)

    errors: list[str] = []
    if python_set != rust_set:
        errors.append(
            "Python/Rust feature names differ: "
            f"python_only={sorted(python_set - rust_set)} "
            f"rust_only={sorted(rust_set - python_set)}"
        )
    if rust_set != kotlin_set:
        errors.append(
            "Rust/Kotlin feature names differ: "
            f"rust_only={sorted(rust_set - kotlin_set)} "
            f"kotlin_only={sorted(kotlin_set - rust_set)}"
        )

    for feature in sorted(rust_set & kotlin_set):
        if rust_features[feature] != kotlin_features[feature]:
            errors.append(
                f"Feature bit mismatch for {feature}: "
                f"rust={rust_features[feature]} kotlin={kotlin_features[feature]}"
            )

    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1

    print(
        "arm64 capability catalog sync: "
        f"{len(python_features)} features aligned across Python/Rust/Kotlin"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
