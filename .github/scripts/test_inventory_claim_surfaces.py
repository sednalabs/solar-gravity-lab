from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("inventory_claim_surfaces.py")


def run_inventory(root: Path, *extra_args: str) -> dict[str, object]:
    output = root / "dist" / "claim-surfaces.json"
    command = [
        sys.executable,
        str(SCRIPT),
        "--root",
        str(root),
        "--output",
        str(output),
        "--format",
        "json",
        *extra_args,
    ]
    subprocess.run(command, check=True, text=True, capture_output=True)
    return json.loads(output.read_text(encoding="utf-8"))


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_detects_missing_verified_manifest_evidence() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        write(root / ".github/scripts/download.py", '"""verified artifact downloader"""\nprint("verified artifact")\n')

        payload = run_inventory(root)
        entries = payload["entries"]

        assert any(
            entry["claim_class"] == "verified"
            and entry["missing_evidence"] == "no_digest_or_manifest_check"
            and entry["enforcement_status"] == "missing_evidence"
            for entry in entries
        )


def test_safe_math_name_is_inventory_only() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        write(root / "clients/android/app/src/main/kotlin/Camera.kt", "val safeRadius = radius.coerceAtLeast(1.0)\n")

        payload = run_inventory(root)
        entries = payload["entries"]

        assert any(
            entry["claim_class"] == "safe" and entry["enforcement_status"] == "inventory_only"
            for entry in entries
        )


def test_recognizes_append_only_evidence() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        write(
            root / "services/dev-telemetry-collector/src/main.rs",
            "// append-only telemetry log\nOpenOptions::new().append(true).open(path)?;\n",
        )

        payload = run_inventory(root)
        entries = payload["entries"]

        assert any(
            entry["claim_class"] == "append_only"
            and entry["enforcement_status"] == "recognized_evidence_present"
            for entry in entries
        )


def test_fail_on_new_uses_baseline() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        baseline = root / "dist" / "claim-enforcement-baseline.json"
        write(root / ".github/scripts/download.py", '"""verified artifact downloader"""\n')

        run_inventory(root, "--baseline-output", str(baseline))
        write(root / ".github/scripts/other.py", '"""trusted release helper"""\n')

        proc = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--root",
                str(root),
                "--baseline",
                str(baseline),
                "--fail-on-new",
            ],
            text=True,
            capture_output=True,
        )

        assert proc.returncode == 1
        assert "not present in baseline" in proc.stderr


if __name__ == "__main__":
    for test in [
        test_detects_missing_verified_manifest_evidence,
        test_safe_math_name_is_inventory_only,
        test_recognizes_append_only_evidence,
        test_fail_on_new_uses_baseline,
    ]:
        test()
