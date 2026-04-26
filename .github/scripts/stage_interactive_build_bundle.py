#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Stage a canonical interactive Android build bundle."
    )
    parser.add_argument("--apk-path", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--artifact-name", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--checkout-ref", required=True)
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--android-validation-mode", required=True)
    parser.add_argument("--interactive-debug-profile", required=True)
    parser.add_argument("--preferred-gpu-backend", required=True)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--activity-name", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--github-output")
    return parser.parse_args()


def safe_version_label(version_name: str) -> str:
    return "".join(
        character if character.isalnum() or character in "._-" else "-"
        for character in version_name
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_outputs(path: Path | None, payload: dict[str, str]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for key, value in payload.items():
            handle.write(f"{key}={value}\n")


def main() -> None:
    args = parse_args()
    apk_path = Path(args.apk_path).resolve()
    if not apk_path.is_file():
        raise SystemExit(f"APK path does not exist: {apk_path}")

    output_dir = Path(args.output_dir).resolve()
    shutil.rmtree(output_dir, ignore_errors=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    bundle_apk_name = f"solar-gravity-lab-{safe_version_label(args.version_name)}-debug.apk"
    staged_apk_path = output_dir / bundle_apk_name
    shutil.copy2(apk_path, staged_apk_path)
    apk_sha256 = sha256_file(staged_apk_path)

    manifest = {
        "schema_version": 1,
        "artifact_name": args.artifact_name,
        "repository": args.repository,
        "workflow": args.workflow,
        "run_id": args.run_id,
        "run_attempt": args.run_attempt,
        "checkout_ref": args.checkout_ref,
        "commit_sha": args.commit_sha,
        "android_validation_mode": args.android_validation_mode,
        "interactive_debug_profile": args.interactive_debug_profile,
        "preferred_gpu_backend": args.preferred_gpu_backend,
        "package_name": args.package_name,
        "activity_name": args.activity_name,
        "version_name": args.version_name,
        "apk_filename": bundle_apk_name,
        "apk_sha256": apk_sha256,
        "built_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace(
            "+00:00", "Z"
        ),
    }
    manifest_path = output_dir / "interactive-build-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (output_dir / f"{bundle_apk_name}.sha256").write_text(
        f"{apk_sha256}  {bundle_apk_name}\n", encoding="utf-8"
    )

    write_outputs(
        Path(args.github_output) if args.github_output else None,
        {
            "artifact_name": args.artifact_name,
            "bundle_dir": str(output_dir),
            "manifest_path": str(manifest_path),
            "apk_path": str(staged_apk_path),
            "apk_sha256": apk_sha256,
            "version_name": args.version_name,
        },
    )

    print(
        json.dumps(
            {
                "artifact_name": args.artifact_name,
                "bundle_dir": str(output_dir),
                "manifest_path": str(manifest_path),
                "apk_path": str(staged_apk_path),
                "apk_sha256": apk_sha256,
            },
            indent=2,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
