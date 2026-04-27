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


def python_candidate_kernel_requirements() -> dict[str, tuple[str, ...]]:
    module = ast.parse(PYTHON_COLLECTOR.read_text(encoding="utf-8"))
    for node in module.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if (
                    isinstance(target, ast.Name)
                    and target.id == "CANDIDATE_KERNEL_REQUIREMENTS"
                ):
                    value = ast.literal_eval(node.value)
                    return {str(path): tuple(requirements) for path, requirements in value.items()}
    raise AssertionError("CANDIDATE_KERNEL_REQUIREMENTS not found in Python collector")


def python_implemented_solver_paths() -> list[str]:
    contents = PYTHON_COLLECTOR.read_text(encoding="utf-8")
    match = re.search(
        r'"implemented_solver_paths":\s*(\[[^\]]*\])',
        contents,
        flags=re.MULTILINE,
    )
    if not match:
        raise AssertionError("implemented_solver_paths not found in Python collector")
    value = ast.literal_eval(match.group(1))
    return list(value)


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


def rust_active_kernel_paths() -> list[str]:
    contents = RUST_PHYSICS.read_text(encoding="utf-8")
    paths = [
        path
        for path, readiness in re.findall(
            r'path_id: "([^"]+)",\s+required_features: &\[[^\]]*\],\s+readiness: Arm64KernelReadiness::([A-Za-z]+),',
            contents,
            flags=re.MULTILINE | re.DOTALL,
        )
        if readiness == "Active"
    ]
    if not paths:
        raise AssertionError("No Rust active Arm64 kernel catalog entries found")
    return paths


def rust_candidate_kernel_requirements() -> dict[str, tuple[str, ...]]:
    contents = RUST_PHYSICS.read_text(encoding="utf-8")
    entries = {
        path: tuple(re.findall(r'"([^"]+)"', required_features))
        for path, required_features, readiness in re.findall(
            r'path_id: "([^"]+)",\s+required_features: &\[([^\]]*)\],\s+readiness: Arm64KernelReadiness::([A-Za-z]+),',
            contents,
            flags=re.MULTILINE | re.DOTALL,
        )
        if readiness == "Candidate"
    }
    if not entries:
        raise AssertionError("No Rust Arm64 candidate kernel catalog entries found")
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
    python_implemented_paths = python_implemented_solver_paths()
    python_candidate_requirements = python_candidate_kernel_requirements()
    rust_features = rust_catalog()
    rust_active_paths = rust_active_kernel_paths()
    rust_candidate_requirements = rust_candidate_kernel_requirements()
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

    if set(python_implemented_paths) != set(rust_active_paths):
        errors.append(
            "Python implemented solver paths differ from Rust active kernel paths: "
            f"python_only={sorted(set(python_implemented_paths) - set(rust_active_paths))} "
            f"rust_only={sorted(set(rust_active_paths) - set(python_implemented_paths))}"
        )

    python_candidate_paths = set(python_candidate_requirements)
    rust_candidate_paths = set(rust_candidate_requirements)
    if python_candidate_paths != rust_candidate_paths:
        errors.append(
            "Python/Rust candidate kernel paths differ: "
            f"python_only={sorted(python_candidate_paths - rust_candidate_paths)} "
            f"rust_only={sorted(rust_candidate_paths - python_candidate_paths)}"
        )
    for path in sorted(python_candidate_paths & rust_candidate_paths):
        if python_candidate_requirements[path] != rust_candidate_requirements[path]:
            errors.append(
                f"Candidate kernel requirements differ for {path}: "
                f"python={python_candidate_requirements[path]} "
                f"rust={rust_candidate_requirements[path]}"
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
        f"{len(python_features)} features aligned across Python/Rust/Kotlin; "
        f"{len(python_implemented_paths)} active kernels aligned across Python/Rust; "
        f"{len(python_candidate_requirements)} candidate kernels aligned across Python/Rust"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
