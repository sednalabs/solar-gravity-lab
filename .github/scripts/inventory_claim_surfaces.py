#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


CLAIM_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("signed", re.compile(r"\b(signed|signature|signatures)\b", re.IGNORECASE)),
    ("attested", re.compile(r"\b(attested|attestation|attestations)\b", re.IGNORECASE)),
    ("approved", re.compile(r"\b(approved|approval|approvals)\b", re.IGNORECASE)),
    ("sealed", re.compile(r"\b(sealed|seal|seals)\b", re.IGNORECASE)),
    ("trusted", re.compile(r"\b(trusted|trust|trusts)\b", re.IGNORECASE)),
    ("verified", re.compile(r"\b(verified|verify|verification|verifies)\b", re.IGNORECASE)),
    ("safe", re.compile(r"\b(safe[A-Z_][A-Za-z0-9_]*|safe|safety)\b", re.IGNORECASE)),
    ("immutable", re.compile(r"\b(immutable|immutability)\b", re.IGNORECASE)),
    ("append_only", re.compile(r"\b(append-only|append only)\b", re.IGNORECASE)),
)

EVIDENCE_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("no_signature_generation_or_verification", re.compile(r"\b(signature|signed|cosign|signing)\b", re.IGNORECASE)),
    ("no_digest_or_manifest_check", re.compile(r"\b(sha256|checksum|digest|manifest).*\b(check|validat|verify|match|mismatch)\b|\b(check|validat|verify|match|mismatch).*\b(sha256|checksum|digest|manifest)\b", re.IGNORECASE)),
    ("no_authz_or_identity_gate", re.compile(r"\b(auth|authorization|token|identity|commit_sha|artifact_name|target_sha|release_target|GITHUB_SHA)\b", re.IGNORECASE)),
    ("no_exact_ref_or_permission_guard", re.compile(r"\b(refs/heads/|github\.sha|GITHUB_SHA|permissions:|persist-credentials:\s*false|release_target)\b", re.IGNORECASE)),
    ("no_release_or_artifact_provenance", re.compile(r"\b(provenance|attestation|release-provenance|build-provenance)\b", re.IGNORECASE)),
    ("no_append_only_open_or_write_guard", re.compile(r"(>>|\bappend\s*\(|\bappend\s*:\s*true\b|\btee\s+-a\b|OpenOptions.*append\s*\(\s*true\s*\))", re.IGNORECASE)),
    ("direct_mutation_or_non_append_open", re.compile(r"(\bwrite_text\s*\(|\bopen\s*\([^)]*['\"]w|\bcreate\s*\(\s*true\s*\)|>)", re.IGNORECASE)),
    ("no_bounds_or_sanitizer_guard", re.compile(r"\b(sanitized|sanitize|coerceIn|coerceAtLeast|clamp|bounds?|validated?)\b", re.IGNORECASE)),
)

CLAIM_TO_MISSING: dict[str, str] = {
    "signed": "no_signature_generation_or_verification",
    "attested": "no_release_or_artifact_provenance",
    "approved": "no_authz_or_identity_gate",
    "sealed": "no_release_or_artifact_provenance",
    "trusted": "no_authz_or_identity_gate",
    "verified": "no_digest_or_manifest_check",
    "safe": "no_bounds_or_sanitizer_guard",
    "immutable": "direct_mutation_or_non_append_open",
    "append_only": "no_append_only_open_or_write_guard",
}

SAFE_ALERT_CONTEXT = re.compile(
    r"\b(auth|authorization|crypto|digest|manifest|provenance|release|security|signed|signature|trusted|verified|verification)\b",
    re.IGNORECASE,
)

INCLUDED_SUFFIXES = {
    ".c",
    ".cc",
    ".cpp",
    ".h",
    ".hpp",
    ".java",
    ".json",
    ".kt",
    ".kts",
    ".md",
    ".proto",
    ".py",
    ".ql",
    ".qll",
    ".qls",
    ".rs",
    ".sh",
    ".toml",
    ".yaml",
    ".yml",
}

SKIP_PARTS = {
    ".git",
    ".gradle",
    ".idea",
    ".tmp",
    "build",
    "dist",
    "target",
    ".trusted-codeql-policy",
}


@dataclass(frozen=True)
class ClaimFinding:
    path: str
    line: int
    claim_class: str
    missing_evidence: str
    enforcement_status: str
    surface: str
    text: str

    def key(self) -> str:
        return self.location_key()

    def location_key(self) -> str:
        return "|".join(
            (
                self.path,
                str(self.line),
                self.claim_class,
                self.missing_evidence,
                self.enforcement_status,
            )
        )

    def content_key(self) -> str:
        return "|".join(
            (
                self.path,
                self.claim_class,
                self.missing_evidence,
                self.enforcement_status,
                " ".join(self.text.split()),
            )
        )

    def to_json(self) -> dict[str, object]:
        return {
            "path": self.path,
            "line": self.line,
            "claim_class": self.claim_class,
            "missing_evidence": self.missing_evidence,
            "enforcement_status": self.enforcement_status,
            "surface": self.surface,
            "text": self.text,
        }


@dataclass(frozen=True)
class BaselineKeys:
    location_keys: frozenset[str]
    content_keys: frozenset[str]

    def contains(self, finding: ClaimFinding) -> bool:
        return finding.location_key() in self.location_keys or finding.content_key() in self.content_keys


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Inventory trust-like claim surfaces.")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline-output", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--fail-on-new", action="store_true")
    parser.add_argument(
        "--fail-on-new-status",
        action="append",
        choices=("missing_evidence", "recognized_evidence_present", "inventory_only"),
        help=(
            "Only fail on new baseline entries with this enforcement status. "
            "Repeat to gate more than one status. Defaults to all statuses."
        ),
    )
    parser.add_argument(
        "--fail-on-new-surface",
        action="append",
        choices=("actions", "c-cpp", "docs", "java-kotlin", "plain-text", "proto", "python", "rust"),
        help=(
            "Only fail on new baseline entries from this surface. Repeat to gate more than one surface. "
            "Defaults to all surfaces."
        ),
    )
    parser.add_argument("--format", default="json", help="Comma-separated output formats: json,github-summary")
    return parser.parse_args()


def is_candidate(path: Path) -> bool:
    if any(part in SKIP_PARTS for part in path.parts):
        return False
    if path.name == "Cargo.lock":
        return False
    return path.suffix in INCLUDED_SUFFIXES or path.name in {"Cargo.toml", "AGENTS.md", "README.md"}


def surface_for(path: str) -> str:
    if path.startswith(".github/workflows/") or path.startswith(".github/actions/"):
        return "actions"
    if path.startswith(".github/scripts/") and path.endswith(".py"):
        return "python"
    if path.endswith(".rs"):
        return "rust"
    if path.endswith((".kt", ".kts", ".java")):
        return "java-kotlin"
    if path.endswith((".c", ".cc", ".cpp", ".h", ".hpp")):
        return "c-cpp"
    if path.endswith(".proto"):
        return "proto"
    if path.endswith(".md"):
        return "docs"
    return "plain-text"


def file_evidence(text: str) -> set[str]:
    return {evidence for evidence, pattern in EVIDENCE_PATTERNS if pattern.search(text)}


def enforcement_status(claim_class: str, missing_evidence: str, evidence: set[str], line_text: str) -> str:
    if claim_class == "safe" and not SAFE_ALERT_CONTEXT.search(line_text):
        return "inventory_only"
    if missing_evidence in evidence:
        return "recognized_evidence_present"
    return "missing_evidence"


def scan_file(root: Path, path: Path) -> list[ClaimFinding]:
    relative = path.relative_to(root).as_posix()
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = path.read_text(encoding="utf-8", errors="replace")

    evidence = file_evidence(text)
    findings: list[ClaimFinding] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = " ".join(line.strip().split())
        if not stripped:
            continue
        for claim_class, pattern in CLAIM_PATTERNS:
            if not pattern.search(line):
                continue
            missing = CLAIM_TO_MISSING[claim_class]
            findings.append(
                ClaimFinding(
                    path=relative,
                    line=line_number,
                    claim_class=claim_class,
                    missing_evidence=missing,
                    enforcement_status=enforcement_status(claim_class, missing, evidence, line),
                    surface=surface_for(relative),
                    text=stripped[:240],
                )
            )
    return findings


def scan(root: Path) -> list[ClaimFinding]:
    findings: list[ClaimFinding] = []
    for path in sorted(root.rglob("*")):
        if path.is_file() and is_candidate(path.relative_to(root)):
            findings.extend(scan_file(root, path))
    return findings


def baseline_payload(findings: list[ClaimFinding]) -> dict[str, object]:
    return {
        "schema_version": 2,
        "entries": [finding.to_json() for finding in findings],
    }


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def load_baseline_keys(path: Path) -> BaselineKeys:
    payload = json.loads(path.read_text(encoding="utf-8"))
    entries = payload.get("entries", [])
    if not isinstance(entries, list):
        raise SystemExit(f"baseline entries must be a list: {path}")
    location_keys: set[str] = set()
    content_keys: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        location_keys.add(
            "|".join(
                (
                    str(entry.get("path", "")),
                    str(entry.get("line", "")),
                    str(entry.get("claim_class", "")),
                    str(entry.get("missing_evidence", "")),
                    str(entry.get("enforcement_status", "")),
                )
            )
        )
        content_keys.add(
            "|".join(
                (
                    str(entry.get("path", "")),
                    str(entry.get("claim_class", "")),
                    str(entry.get("missing_evidence", "")),
                    str(entry.get("enforcement_status", "")),
                    " ".join(str(entry.get("text", "")).split()),
                )
            )
        )
    return BaselineKeys(frozenset(location_keys), frozenset(content_keys))


def summary_text(findings: list[ClaimFinding]) -> str:
    by_claim = Counter(finding.claim_class for finding in findings)
    by_status = Counter(finding.enforcement_status for finding in findings)
    by_surface = Counter(finding.surface for finding in findings)
    lines: list[str] = []

    lines.append("## Claim enforcement inventory")
    lines.append("")
    lines.append(f"- total claim surfaces: `{len(findings)}`")
    lines.append(f"- missing evidence: `{by_status.get('missing_evidence', 0)}`")
    lines.append(f"- recognized evidence present: `{by_status.get('recognized_evidence_present', 0)}`")
    lines.append(f"- inventory-only: `{by_status.get('inventory_only', 0)}`")
    lines.append("")
    lines.append("### Claim classes")
    for claim, count in sorted(by_claim.items()):
        lines.append(f"- `{claim}`: `{count}`")
    lines.append("")
    lines.append("### Surfaces")
    for surface, count in sorted(by_surface.items()):
        lines.append(f"- `{surface}`: `{count}`")
    return "\n".join(lines) + "\n"


def emit_summary(findings: list[ClaimFinding]) -> None:
    text = summary_text(findings)
    print(text, end="")


def main() -> None:
    args = parse_args()
    root = args.root.resolve()
    formats = {item.strip() for item in args.format.split(",") if item.strip()}
    findings = scan(root)
    payload = baseline_payload(findings)

    if args.output:
        write_json(args.output, payload)
    if args.baseline_output:
        write_json(args.baseline_output, payload)
    if "json" in formats and not args.output:
        print(json.dumps(payload, indent=2, sort_keys=True))
    if "github-summary" in formats:
        emit_summary(findings)

    if args.fail_on_new:
        if not args.baseline:
            raise SystemExit("--fail-on-new requires --baseline")
        baseline_keys = load_baseline_keys(args.baseline)
        gated_statuses = set(args.fail_on_new_status or ())
        gated_surfaces = set(args.fail_on_new_surface or ())
        new_findings = [
            finding
            for finding in findings
            if (not gated_statuses or finding.enforcement_status in gated_statuses)
            and (not gated_surfaces or finding.surface in gated_surfaces)
            and not baseline_keys.contains(finding)
        ]
        if new_findings:
            print(f"Found {len(new_findings)} claim surfaces not present in baseline.", file=sys.stderr)
            if gated_statuses:
                print(f"gated_statuses={','.join(sorted(gated_statuses))}", file=sys.stderr)
            if gated_surfaces:
                print(f"gated_surfaces={','.join(sorted(gated_surfaces))}", file=sys.stderr)
            for finding in new_findings[:20]:
                print(
                    f"{finding.path}:{finding.line}: {finding.claim_class} "
                    f"{finding.missing_evidence} {finding.enforcement_status}",
                    file=sys.stderr,
                )
            raise SystemExit(1)


if __name__ == "__main__":
    main()
