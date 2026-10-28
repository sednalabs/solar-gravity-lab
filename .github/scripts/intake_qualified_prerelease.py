#!/usr/bin/env python3
"""Admit an explicitly selected prerelease APK into a hosted interactive session."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from download_interactive_build_artifact import GitHubApiClient, require_token, write_outputs

REPOSITORY = "sednalabs/solar-gravity-lab"
PACKAGE = "com.sednalabs.solarlab.internal"
ACTIVITY = "com.sednalabs.solarlab.MainActivity"
SHA = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
ID = re.compile(r"[1-9][0-9]{0,19}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check_inputs(repository: str, run_id: str, artifact_id: str, source_sha: str) -> None:
    require(repository == REPOSITORY, "repository mismatch")
    require(bool(ID.fullmatch(run_id)) and bool(ID.fullmatch(artifact_id)), "invalid run or artifact ID")
    require(bool(SHA.fullmatch(source_sha)), "invalid expected APK source SHA")


def check_identity(repository: str, run_id: str, artifact_id: str, run: dict, artifact: dict) -> str:
    require(str(run.get("id")) == run_id and run.get("status") == "completed" and run.get("conclusion") == "success", "run mismatch or unsuccessful")
    require(run.get("path") == ".github/workflows/prerelease-apk.yml", "workflow mismatch")
    require(isinstance(run.get("run_attempt"), int) and run["run_attempt"] > 0, "invalid run attempt")
    require(run.get("repository", {}).get("full_name") == repository and run.get("head_repository", {}).get("full_name") == repository, "run repository mismatch")
    head_sha = run.get("head_sha")
    require(isinstance(head_sha, str) and bool(SHA.fullmatch(head_sha)), "invalid producer workflow head")
    require(str(artifact.get("id")) == artifact_id and artifact.get("expired") is False, "artifact mismatch or expired")
    require(isinstance(artifact.get("name"), str) and bool(re.fullmatch(r"[A-Za-z0-9._-]+", artifact["name"])), "invalid artifact name")
    digest = artifact.get("digest")
    require(isinstance(digest, str) and digest.startswith("sha256:") and bool(SHA256.fullmatch(digest[7:])), "missing or invalid artifact digest")
    source = artifact.get("workflow_run", {})
    require(str(source.get("id")) == run_id and source.get("head_sha") == head_sha, "artifact run mismatch")
    return digest[7:]


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def unpack_verified(archive: bytes, run: dict, repository: str, source_sha: str, archive_sha: str) -> tuple[bytes, dict, dict]:
    require(sha256(archive) == archive_sha, "archive digest mismatch")
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        paths = bundle.namelist()
        apk_paths = [p for p in paths if p.endswith(".apk")]
        provenance_paths = [p for p in paths if p.startswith("dist/build-provenance-") and p.endswith(".json")]
        metadata_paths = [p for p in paths if p.endswith("/prerelease/output-metadata.json")]
        require(len(apk_paths) == len(provenance_paths) == len(metadata_paths) == 1, "expected one APK, provenance and metadata")
        apk = bundle.read(apk_paths[0])
        provenance = json.loads(bundle.read(provenance_paths[0]))
        metadata = json.loads(bundle.read(metadata_paths[0]))
    expected = {"schema_version": 1, "provenance_kind": "android-build", "repository": repository,
                "workflow": "prerelease-apk", "run_id": str(run["id"]), "run_attempt": str(run.get("run_attempt")),
                "commit_sha": source_sha, "build_variant": "prerelease", "release_channel": "prerelease",
                "artifact_name": Path(apk_paths[0]).name}
    require(all(provenance.get(k) == v for k, v in expected.items()), "provenance mismatch")
    apk_sha = provenance.get("artifact_sha256")
    require(isinstance(apk_sha, str) and bool(SHA256.fullmatch(apk_sha)) and sha256(apk) == apk_sha, "APK digest mismatch")
    require(isinstance(provenance.get("checkout_ref"), str) and bool(provenance["checkout_ref"]), "missing producer checkout ref")
    require(isinstance(provenance.get("version_name"), str) and bool(provenance["version_name"]), "missing version")
    require(metadata.get("applicationId") == PACKAGE and metadata.get("variantName") == "prerelease", "APK metadata mismatch")
    return apk, provenance, metadata


def stage_apk(output_dir: Path, apk: bytes) -> Path:
    apk_path = output_dir / "qualified-prerelease.apk"
    apk_path.write_bytes(apk)
    return apk_path


def write_intake_outputs(path: Path, values: dict[str, str]) -> None:
    for name, value in values.items():
        require(bool(re.fullmatch(r"[a-z_]+", name)), "invalid output name")
        require(isinstance(value, str) and "\n" not in value and "\r" not in value, "invalid output value")
    write_outputs(path, values)


def check_apk_properties(application_id: str, debuggable: str) -> None:
    require(application_id.strip() == PACKAGE, "APK application ID mismatch")
    require(debuggable.strip() == "false", "APK debug profile mismatch")


def main() -> None:
    repository = os.environ["GITHUB_REPOSITORY"]
    run_id = os.environ["PRERELEASE_RUN_ID"]
    artifact_id = os.environ["PRERELEASE_ARTIFACT_ID"]
    source_sha = os.environ["PRERELEASE_SOURCE_SHA"]
    check_inputs(repository, run_id, artifact_id, source_sha)
    output_dir = Path(sys.argv[1]).resolve()
    analyzer = sys.argv[2]
    client = GitHubApiClient(repository, require_token("GITHUB_TOKEN"))
    run = client.get_json(f"/repos/{repository}/actions/runs/{run_id}")
    artifact = client.get_json(f"/repos/{repository}/actions/artifacts/{artifact_id}")
    archive_sha = check_identity(repository, run_id, artifact_id, run, artifact)
    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / "qualified-artifact.zip"
    with archive_path.open("wb") as output:
        subprocess.run(["gh", "api", f"repos/{repository}/actions/artifacts/{artifact_id}/zip"], stdout=output, check=True)
    apk, provenance, metadata = unpack_verified(archive_path.read_bytes(), run, repository, source_sha, archive_sha)
    apk_path = stage_apk(output_dir, apk)

    def apk_property(name: str) -> str:
        return subprocess.check_output([analyzer, "manifest", name, str(apk_path)], text=True).strip()

    check_apk_properties(apk_property("application-id"), apk_property("debuggable"))
    (output_dir / "build-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    (output_dir / "output-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    record = {"schema_version": 1, "artifact_kind": "qualified-prerelease", "artifact_id": artifact_id,
              "artifact_name": artifact["name"], "archive_sha256": archive_sha,
              "repository": repository, "workflow": "prerelease-apk", "producer_workflow_head_sha": run["head_sha"],
              "checkout_ref": provenance["checkout_ref"], "commit_sha": source_sha,
              "run_id": run_id, "run_attempt": str(run["run_attempt"]),
              "apk_sha256": provenance["artifact_sha256"], "apk_filename": apk_path.name,
              "package_name": PACKAGE, "activity_name": ACTIVITY, "build_variant": "prerelease",
              "android_validation_mode": "prerelease", "interactive_debug_profile": "prerelease-non-debuggable",
              "version_name": provenance["version_name"], "built_at": "unknown",
              "debuggable": False, "provenance": provenance}
    record_path = output_dir / "qualified-prerelease-intake.json"
    record_path.write_text(json.dumps(record, indent=2) + "\n")
    write_intake_outputs(Path(os.environ["GITHUB_OUTPUT"]), {"artifact_name": artifact["name"], "build_run_id": run_id,
        "build_run_attempt": str(run["run_attempt"]), "build_commit_sha": source_sha, "bundle_dir": str(output_dir),
        "manifest_path": str(record_path), "apk_path": str(apk_path), "apk_sha256": provenance["artifact_sha256"],
        "package_name": PACKAGE, "activity_name": ACTIVITY})


if __name__ == "__main__":
    main()
