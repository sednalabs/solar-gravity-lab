#!/usr/bin/env python3
"""Collect a hardware capability census for Android/Arm64 proof lanes.

The script is intentionally useful on both GitHub-hosted Arm64 runners and
attached Android devices. Hosted runners prove portable aarch64 behavior; real
Galaxy devices provide the authoritative OEM capability artifact.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import platform
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


SCHEMA_VERSION = "2026-04-27.1"
AT_HWCAP = 16
ADB_SERIAL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
AT_HWCAP2 = 26

FEATURE_ALIASES = {
    "asimd": "neon",
    "neon": "neon",
    "fp": "fp",
    "fphp": "fp16",
    "asimdhp": "fp16",
    "fp16": "fp16",
    "fhm": "fhm",
    "asimdfhm": "fhm",
    "asimddp": "dotprod",
    "dotprod": "dotprod",
    "i8mm": "i8mm",
    "svei8mm": "sve-i8mm",
    "sve_i8mm": "sve-i8mm",
    "sve": "sve",
    "sve2": "sve2",
    "sme": "sme",
    "sme2": "sme2",
    "atomics": "lse",
    "lse": "lse",
    "lse2": "lse2",
    "crc32": "crc",
    "crc": "crc",
    "mops": "mops",
    "aes": "aes",
    "pmull": "pmull",
    "sha1": "sha1",
    "sha2": "sha2",
    "sha3": "sha3",
    "sha512": "sha512",
    "sm3": "sm3",
    "sm4": "sm4",
    "bf16": "bf16",
    "rng": "rng",
    "bti": "bti",
    "mte": "mte",
    "rdm": "rdm",
    "asimdrdm": "rdm",
    "jscvt": "jscvt",
    "fcma": "fcma",
    "flagm": "flagm",
    "flagm2": "flagm2",
    "dit": "dit",
    "sb": "sb",
    "ssbs": "ssbs",
}

FEATURE_ORDER = (
    "neon",
    "fp",
    "fp16",
    "fhm",
    "dotprod",
    "i8mm",
    "sve",
    "sve2",
    "sve-i8mm",
    "sme",
    "sme2",
    "lse",
    "lse2",
    "crc",
    "mops",
    "aes",
    "pmull",
    "sha1",
    "sha2",
    "sha3",
    "sha512",
    "sm3",
    "sm4",
    "bf16",
    "rng",
    "bti",
    "mte",
    "rdm",
    "jscvt",
    "fcma",
    "flagm",
    "flagm2",
    "dit",
    "sb",
    "ssbs",
)

FEATURE_WORKLOADS = {
    "neon": "active authoritative f64 pairwise gravity solver when runtime dispatch selects simd.arm64.neon-f64-pairwise, simd.arm64.neon-f64-tiled-pairwise, or simd.arm64.neon-f64-parallel-tiled-pairwise",
    "fp": "baseline floating-point substrate for authoritative simulation",
    "fp16": "reserved for visualization, quantization, or tracer-assist paths with an explicit precision policy",
    "fhm": "reserved for fp16 visualization/tracer-assist kernels after parity and error-budget proof",
    "dotprod": "reserved for packed/quantized render or assist workloads; not used by f64 gravity",
    "i8mm": "reserved for packed integer or ML-style assist workloads; not used by f64 gravity",
    "sve": "reserved for future wider f64 batch gravity/tracer kernels with runtime dispatch",
    "sve2": "reserved for future wider f64 batch gravity/tracer kernels with runtime dispatch",
    "sve-i8mm": "reserved for future packed assist workloads",
    "sme": "reserved for future matrix/tile workloads; no current SGL kernel claims it",
    "sme2": "reserved for future matrix/tile workloads; no current SGL kernel claims it",
    "lse": "compiler/runtime atomic performance capability; not a named solver path",
    "lse2": "compiler/runtime atomic performance capability; not a named solver path",
    "crc": "detected utility capability; no current SGL hot path claims it",
    "mops": "compiler/runtime memory-operation acceleration capability; not a named solver path",
    "aes": "detected crypto capability; no current SGL hot path claims it",
    "pmull": "detected crypto capability; no current SGL hot path claims it",
    "sha1": "detected crypto capability; no current SGL hot path claims it",
    "sha2": "detected crypto capability; no current SGL hot path claims it",
    "sha3": "detected crypto capability; no current SGL hot path claims it",
    "sha512": "detected crypto capability; no current SGL hot path claims it",
    "sm3": "detected crypto capability; no current SGL hot path claims it",
    "sm4": "detected crypto capability; no current SGL hot path claims it",
    "bf16": "reserved for approximate assist workloads; not authoritative f64 simulation",
    "rng": "detected utility capability; no current SGL hot path claims it",
    "bti": "platform hardening capability; not a solver path",
    "mte": "platform memory-tagging capability; not a solver path",
    "rdm": "SIMD rounding multiply capability; reserved until a kernel uses it",
    "jscvt": "detected conversion capability; no current SGL hot path claims it",
    "fcma": "reserved for complex/vector math workloads if one appears",
    "flagm": "detected control-flow/math support capability; no current SGL hot path claims it",
    "flagm2": "detected control-flow/math support capability; no current SGL hot path claims it",
    "dit": "constant-time execution support capability; no current SGL hot path claims it",
    "sb": "speculation barrier capability; not a solver path",
    "ssbs": "speculative-store-bypass control capability; not a solver path",
}

CANDIDATE_KERNEL_REQUIREMENTS = {
    "simd.arm64.sve-f64-batch-candidate": ("sve",),
    "simd.arm64.sve2-f64-batch-candidate": ("sve2",),
    "simd.arm64.sve-i8mm-packed-assist-candidate": ("sve-i8mm",),
    "simd.arm64.sme-tiled-f64-candidate": ("sme",),
    "simd.arm64.sme2-tiled-f64-candidate": ("sme2",),
    "simd.arm64.dotprod-packed-assist-candidate": ("dotprod",),
    "simd.arm64.i8mm-packed-assist-candidate": ("i8mm",),
    "simd.arm64.bf16-forecast-assist-candidate": ("bf16",),
    "simd.arm64.fp16-visual-assist-candidate": ("fp16",),
    "simd.arm64.fhm-visual-assist-candidate": ("fhm",),
    "simd.arm64.rdm-vector-assist-candidate": ("rdm",),
    "simd.arm64.fcma-vector-assist-candidate": ("fcma",),
}
CANDIDATE_KERNEL_PATHS = tuple(CANDIDATE_KERNEL_REQUIREMENTS)

RESERVED_FEATURES = {
    "fp16",
    "fhm",
    "dotprod",
    "i8mm",
    "sve",
    "sve2",
    "sve-i8mm",
    "sme",
    "sme2",
    "bf16",
    "rdm",
    "fcma",
}

UTILITY_FEATURES = set(FEATURE_ORDER) - {"neon", "fp"} - RESERVED_FEATURES

HWCAP_BITS = {
    "fp": 1 << 0,
    "asimd": 1 << 1,
    "aes": 1 << 3,
    "pmull": 1 << 4,
    "sha1": 1 << 5,
    "sha2": 1 << 6,
    "crc32": 1 << 7,
    "atomics": 1 << 8,
    "fphp": 1 << 9,
    "asimdhp": 1 << 10,
    "asimdrdm": 1 << 12,
    "jscvt": 1 << 13,
    "fcma": 1 << 14,
    "sha3": 1 << 17,
    "sm3": 1 << 18,
    "sm4": 1 << 19,
    "asimddp": 1 << 20,
    "sha512": 1 << 21,
    "sve": 1 << 22,
    "asimdfhm": 1 << 23,
    "dit": 1 << 24,
    "flagm": 1 << 27,
    "ssbs": 1 << 28,
    "sb": 1 << 29,
}

HWCAP2_BITS = {
    "sve2": 1 << 1,
    "flagm2": 1 << 7,
    "svei8mm": 1 << 9,
    "i8mm": 1 << 13,
    "bf16": 1 << 14,
    "rng": 1 << 16,
    "bti": 1 << 17,
    "mte": 1 << 18,
    "sme": 1 << 23,
    "sme2": 1 << 37,
    "mops": 1 << 43,
}


@dataclass(frozen=True)
class Evidence:
    source: str
    token: str


class CommandFailure(RuntimeError):
    """Raised when a required device-census command cannot collect evidence."""


def run_command(
    command: list[str],
    timeout: int = 30,
    *,
    required: bool = False,
    description: str | None = None,
) -> str:
    try:
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        if required:
            label = description or " ".join(command)
            raise CommandFailure(f"Required command not found for {label}: {command[0]}") from exc
        return ""
    except subprocess.TimeoutExpired as exc:
        if required:
            label = description or " ".join(command)
            raise CommandFailure(f"Required command timed out for {label}") from exc
        return ""
    if result.returncode != 0:
        if required:
            label = description or " ".join(command)
            stderr = result.stderr.strip() or result.stdout.strip() or "no output"
            raise CommandFailure(
                f"Required command failed for {label} "
                f"(exit {result.returncode}): {stderr}"
            )
        return ""
    return result.stdout


def adb_command(serial: str, command: str, *, required: bool = False) -> str:
    base = ["adb"]
    if serial:
        base += ["-s", serial]
    return run_command(
        base + ["shell", command],
        required=required,
        description=f"adb shell {command}",
    )


def validate_adb_serial(serial: str) -> str:
    normalized = serial.strip()
    if not normalized:
        raise CommandFailure("ADB serial cannot be empty")
    if normalized.startswith("-") or not ADB_SERIAL_RE.fullmatch(normalized):
        raise CommandFailure(
            "Invalid ADB serial format; expected up to 128 chars of [A-Za-z0-9._:-] "
            "and must not start with '-'"
        )
    return normalized


def parse_cpuinfo_tokens(contents: str) -> set[str]:
    tokens: set[str] = set()
    for line in contents.splitlines():
        if ":" not in line:
            continue
        key, values = line.split(":", 1)
        if key.strip().lower() not in {"features", "flags"}:
            continue
        tokens.update(value.strip().lower() for value in values.split() if value.strip())
    return tokens


def normalize_token(token: str) -> str:
    return FEATURE_ALIASES.get(token.strip().lower(), token.strip().lower())


def read_host_cpuinfo() -> str:
    path = Path("/proc/cpuinfo")
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def get_auxv_value(kind: int) -> int | None:
    try:
        libc = ctypes.CDLL(None)
        getauxval = libc.getauxval
        getauxval.argtypes = [ctypes.c_ulong]
        getauxval.restype = ctypes.c_ulong
        return int(getauxval(kind))
    except Exception:
        return None


def auxv_evidence() -> tuple[dict[str, int | None], list[Evidence]]:
    if platform.machine().lower() not in {"aarch64", "arm64"}:
        return {"AT_HWCAP": None, "AT_HWCAP2": None}, []

    values = {
        "AT_HWCAP": get_auxv_value(AT_HWCAP),
        "AT_HWCAP2": get_auxv_value(AT_HWCAP2),
    }
    evidence: list[Evidence] = []
    hwcap = values["AT_HWCAP"]
    hwcap2 = values["AT_HWCAP2"]

    if hwcap is not None:
        for token, mask in HWCAP_BITS.items():
            if hwcap & mask:
                evidence.append(Evidence("auxv.AT_HWCAP", token))
    if hwcap2 is not None:
        for token, mask in HWCAP2_BITS.items():
            if hwcap2 & mask:
                evidence.append(Evidence("auxv.AT_HWCAP2", token))

    return values, evidence


def collect_props(serial: str | None) -> dict[str, str]:
    if serial is None:
        uname = platform.uname()
        return {
            "surface": "host",
            "system": uname.system,
            "node": "redacted",
            "release": uname.release,
            "machine": uname.machine,
            "processor": uname.processor,
        }

    props: dict[str, str] = {"surface": "adb"}
    raw_props = adb_command(serial, "getprop")
    all_props = {
        match.group(1): match.group(2)
        for match in re.finditer(r"\[(.+?)\]: \[(.*?)\]", raw_props)
    }
    for key in (
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.device",
        "ro.board.platform",
        "ro.soc.manufacturer",
        "ro.soc.model",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.product.cpu.abi",
        "ro.product.cpu.abilist",
    ):
        value = all_props.get(key, "").strip()
        if value:
            props[key] = value
    return props


def feature_state(feature: str, detected: bool) -> str:
    if not detected:
        return "not_detected"
    if feature == "neon":
        return "active_solver_capability_when_simd_arm64_is_selected"
    if feature == "fp":
        return "baseline_floating_point_capability"
    if feature in RESERVED_FEATURES:
        return "detected_reserved_until_kernel_exists"
    if feature in UTILITY_FEATURES:
        return "detected_no_current_sgl_hot_path"
    return "detected_unclassified"


def build_matrix(evidence: Iterable[Evidence]) -> list[dict[str, object]]:
    evidence_by_feature: dict[str, list[str]] = {feature: [] for feature in FEATURE_ORDER}
    for item in evidence:
        canonical = normalize_token(item.token)
        if canonical in evidence_by_feature:
            evidence_by_feature[canonical].append(f"{item.source}:{item.token}")

    rows = []
    for feature in FEATURE_ORDER:
        sources = sorted(set(evidence_by_feature[feature]))
        detected = bool(sources)
        rows.append(
            {
                "feature": feature,
                "detected": detected,
                "state": feature_state(feature, detected),
                "current_workload": FEATURE_WORKLOADS.get(
                    feature, "no workload classification registered"
                ),
                "evidence": sources,
            }
        )
    return rows


def collect_census(args: argparse.Namespace) -> dict[str, object]:
    serial = validate_adb_serial(args.adb_serial) if args.adb_serial else ""
    if serial:
        cpuinfo = adb_command(serial, "cat /proc/cpuinfo", required=True)
        auxv_values: dict[str, int | None] = {"AT_HWCAP": None, "AT_HWCAP2": None}
        auxv_items: list[Evidence] = []
    else:
        cpuinfo = read_host_cpuinfo()
        auxv_values, auxv_items = auxv_evidence()

    cpuinfo_tokens = parse_cpuinfo_tokens(cpuinfo)
    if serial and not cpuinfo_tokens:
        raise CommandFailure(
            "ADB device census collected /proc/cpuinfo but found no Features/Flags tokens"
        )
    evidence = [Evidence("cpuinfo", token) for token in cpuinfo_tokens]
    evidence.extend(auxv_items)
    raw_evidence_tokens = sorted({item.token for item in evidence})
    normalized_tokens = sorted({normalize_token(item.token) for item in evidence})
    uncataloged_tokens = sorted(set(normalized_tokens) - set(FEATURE_ORDER))
    matrix = build_matrix(evidence)
    detected = [row["feature"] for row in matrix if row["detected"]]
    detected_set = set(detected)
    eligible_candidate_kernel_paths = [
        path
        for path, requirements in CANDIDATE_KERNEL_REQUIREMENTS.items()
        if all(requirement in detected_set for requirement in requirements)
    ]
    eligible_candidate_kernel_path_set = set(eligible_candidate_kernel_paths)
    blocked_candidate_kernel_paths = [
        path for path in CANDIDATE_KERNEL_PATHS if path not in eligible_candidate_kernel_path_set
    ]

    return {
        "schema_version": SCHEMA_VERSION,
        "captured_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "capture_surface": args.surface,
        "device_label": args.device_label,
        "device": collect_props(serial),
        "cpu": {
            "architecture": platform.machine() if serial is None else "adb",
            "cpuinfo_tokens_count": len(cpuinfo_tokens),
            "raw_evidence_tokens": raw_evidence_tokens,
            "normalized_tokens": normalized_tokens,
            "uncataloged_detected_tokens": uncataloged_tokens,
            "auxv": auxv_values,
            "features": matrix,
            "detected_features": detected,
        },
        "runtime_truth": {
            "implemented_solver_paths": [
                "simd.arm64.neon-f64-pairwise",
                "simd.arm64.neon-f64-tiled-pairwise",
                "simd.arm64.neon-f64-parallel-tiled-pairwise",
            ],
            "candidate_kernel_paths": list(CANDIDATE_KERNEL_PATHS),
            "eligible_candidate_kernel_paths": eligible_candidate_kernel_paths,
            "blocked_candidate_kernel_paths": blocked_candidate_kernel_paths,
            "active_solver_feature_claims_when_detected": ["neon"],
            "baseline_feature_claims_when_detected": ["fp"],
            "reserved_feature_claims": sorted(RESERVED_FEATURES),
            "utility_feature_claims": sorted(UTILITY_FEATURES),
        },
        "gpu_truth": {
            "expected_s25_platform": "Snapdragon 8 Elite for Galaxy",
            "expected_apis_from_public_specs": ["Vulkan 1.3", "OpenGL ES 3.2", "OpenCL 3.0 FP"],
            "note": "Hosted Arm64 runners do not prove OEM GPU APIs; attach a Galaxy device artifact for S25-specific GPU truth.",
        },
    }


def write_json(path: str | None, payload: object) -> None:
    if not path:
        return
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_summary(path: str | None, census: dict[str, object]) -> None:
    if not path:
        return
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    cpu = census["cpu"]
    detected = cpu["detected_features"]
    lines = [
        "capability-census: collected",
        f"schema-version: {census['schema_version']}",
        f"capture-surface: {census['capture_surface']}",
        f"device-label: {census['device_label']}",
        f"detected-features: {','.join(detected) if detected else 'none'}",
        f"uncataloged-detected-tokens-count: {len(cpu['uncataloged_detected_tokens'])}",
        "implemented-solver-paths: simd.arm64.neon-f64-pairwise,simd.arm64.neon-f64-tiled-pairwise,simd.arm64.neon-f64-parallel-tiled-pairwise",
        "candidate-kernel-paths: " + ",".join(census["runtime_truth"]["candidate_kernel_paths"]),
        "eligible-candidate-kernel-paths: "
        + ",".join(census["runtime_truth"]["eligible_candidate_kernel_paths"]),
        "blocked-candidate-kernel-paths: "
        + ",".join(census["runtime_truth"]["blocked_candidate_kernel_paths"]),
        "reserved-kernel-features: " + ",".join(census["runtime_truth"]["reserved_feature_claims"]),
    ]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_legacy_capabilities(path: str | None, census: dict[str, object]) -> None:
    if not path:
        return
    cpu = census["cpu"]
    matrix = {row["feature"]: row["detected"] for row in cpu["features"]}
    runner_arch = platform.machine()
    if runner_arch.lower() in {"aarch64", "arm64"}:
        runner_arch = "arm64"
    payload = {
        "runner_arch": runner_arch,
        "cpuinfo_tokens_count": cpu["cpuinfo_tokens_count"],
        "normalized_tokens": cpu["normalized_tokens"],
        "capabilities": {
            feature: matrix.get(feature, False)
            for feature in (
                "neon",
                "fp",
                "fp16",
                "fhm",
                "dotprod",
                "i8mm",
                "sve",
                "sve2",
                "sve-i8mm",
                "sme",
                "sme2",
                "lse",
                "lse2",
                "crc",
                "mops",
            )
        },
        "implemented_solver_paths": {
            "active_when_supported": [
                "simd.arm64.neon-f64-pairwise",
                "simd.arm64.neon-f64-tiled-pairwise",
                "simd.arm64.neon-f64-parallel-tiled-pairwise",
            ],
            "reported_but_reserved_until_kernel_exists": [
                "sve",
                "sve2",
                "sve-i8mm",
                "sme",
                "sme2",
            ],
        },
    }
    write_json(path, payload)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="Path for the full census JSON")
    parser.add_argument("--summary-output", help="Path for a compact text summary")
    parser.add_argument(
        "--legacy-capabilities-output",
        help="Optional backward-compatible capabilities.json path",
    )
    parser.add_argument(
        "--surface",
        default=os.environ.get("SGL_CAPABILITY_CENSUS_SURFACE", "github-hosted-arm64"),
        help="Human-readable capture surface label",
    )
    parser.add_argument(
        "--device-label",
        default=os.environ.get("SGL_CAPABILITY_CENSUS_DEVICE_LABEL", "unspecified"),
        help="Human-readable device class label",
    )
    parser.add_argument(
        "--adb-serial",
        default=os.environ.get("ANDROID_SERIAL"),
        help="Optional adb serial for a real Android device census",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        census = collect_census(args)
    except CommandFailure as exc:
        print(f"capability-census error: {exc}", file=sys.stderr)
        return 2
    write_json(args.output, census)
    write_summary(args.summary_output, census)
    write_legacy_capabilities(args.legacy_capabilities_output, census)
    return 0


if __name__ == "__main__":
    sys.exit(main())
