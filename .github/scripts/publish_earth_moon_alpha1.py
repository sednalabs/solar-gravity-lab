#!/usr/bin/env python3
"""One-time publication of the unchanged, qualified Earth–Moon alpha APK.

The producer's build and ISA jobs passed; its publication alone failed because
the full historical changelog exceeded GitHub's release-body limit. This script
does not rebuild, sign, retag, replace a release, or overwrite an asset.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone

REPOSITORY = "sednalabs/solar-gravity-lab"
SOURCE = "1ea632a92d6f46aa05545d7d08f96882a18290b7"
VERSION = "0.2.0-alpha.1"
VERSION_CODE = 2001001
TAG = f"v{VERSION}"
RECOVERY_REF = "refs/heads/release-publish/0.2.0-alpha.1"
PRODUCER_RUN = 43783497749
VALIDATION_RUNS = (43782411299, 43782411391, 43782411179)
ARTIFACT_ID = 14189597721
ARCHIVE_SHA256 = "ed1dab9d33a1f77c0fdd0fe11e12b753781b5db90978ec680bce701f167b9238"
APK_SHA256 = "262ae930c9225160d6c6252a7cd25dc30bb8494a5f1322c25732db11636facd7"
APK_NAME = f"solar-gravity-lab-{VERSION}-internal-dev-preview.apk"
BUILD_NAME = f"build-provenance-{VERSION}.json"
PUBLIC_NAMES = (
    APK_NAME, APK_NAME + ".sha256", BUILD_NAME,
    f"changelog-{VERSION}.json", f"changelog-{VERSION}.md", "output-metadata.json",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def api(path: str, *, missing_ok: bool = False):
    result = subprocess.run(
        ["gh", "api", f"repos/{REPOSITORY}/{path}"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        if missing_ok and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub read failed for {path}: {result.stderr[:500]}")
    return json.loads(result.stdout)


def verify_release_tag(fetch=api) -> None:
    target = fetch(f"git/ref/tags/{TAG}")["object"]
    for _ in range(5):
        if target["type"] != "tag":
            break
        target = fetch(f"git/tags/{target['sha']}")["object"]
    require(target["type"] == "commit" and target["sha"] == SOURCE,
            "Existing release tag does not resolve to the qualified source")


def verify_evidence(fetch=api) -> dict:
    for run_id in VALIDATION_RUNS:
        run = fetch(f"actions/runs/{run_id}")
        require(run["head_sha"] == SOURCE and run["run_attempt"] == 1,
                f"Qualification source/attempt mismatch: {run_id}")
        require(run["status"] == "completed" and run["conclusion"] == "success",
                f"Qualification did not pass: {run_id}")

    producer = fetch(f"actions/runs/{PRODUCER_RUN}")
    require(producer["head_sha"] == SOURCE and producer["run_attempt"] == 1,
            "Producer source/attempt mismatch")
    require(producer["event"] == "push" and producer["head_branch"] == f"release/{VERSION}",
            "Producer was not the promoted release push")
    require(producer["path"] == ".github/workflows/prerelease-apk.yml",
            "Unexpected producer workflow")
    require(producer["status"] == "completed" and producer["conclusion"] == "failure",
            "Expected the recorded publication-only producer failure")
    job_page = fetch(f"actions/runs/{PRODUCER_RUN}/attempts/1/jobs?per_page=100")
    jobs = job_page["jobs"]
    require(job_page["total_count"] == len(jobs), "Producer jobs were truncated")
    failed = [job["name"] for job in jobs if job["conclusion"] != "success"]
    require(failed == ["Publish GitHub release"], "Producer has a failure beyond publication")
    build = [job for job in jobs if job["name"].startswith("Build internal dev preview APK")]
    isa = [job for job in jobs if job["name"] == "ARM64 ISA prerelease proof"]
    require(len(build) == len(isa) == 1, "Required producer proof jobs are missing or ambiguous")
    steps = {step["name"]: step["conclusion"] for step in build[0]["steps"]}
    for name in ("Assemble prerelease APK", "Verify Rust JNI DTO identities after R8",
                 "Emulator launch smoke for packaged APK", "Upload prerelease APK artifact"):
        require(steps.get(name) == "success", f"Required packaged proof did not pass: {name}")

    branch = fetch(f"git/ref/heads/release/{VERSION}")
    require(branch["object"]["sha"] == SOURCE, "Promoted source branch changed")
    verify_release_tag(fetch)

    artifact = fetch(f"actions/artifacts/{ARTIFACT_ID}")
    require(not artifact["expired"], "Qualified artifact expired")
    require(artifact["workflow_run"]["id"] == PRODUCER_RUN and
            artifact["workflow_run"]["head_sha"] == SOURCE, "Artifact producer mismatch")
    require(artifact["name"] == f"solar-gravity-lab-{VERSION}-internal-dev-preview-{PRODUCER_RUN}",
            "Unexpected artifact name")
    require(artifact["digest"] == f"sha256:{ARCHIVE_SHA256}", "Artifact archive identity changed")
    return {"producer_build_job": build[0]["id"], "producer_isa_job": isa[0]["id"]}


def verify_metadata(build: dict, metadata: dict) -> None:
    expected = {
        "repository": REPOSITORY, "commit_sha": SOURCE, "checkout_ref": SOURCE,
        "run_id": str(PRODUCER_RUN), "run_attempt": "1", "version_name": VERSION,
        "version_code": str(VERSION_CODE), "build_variant": "prerelease",
        "release_channel": "prerelease", "artifact_name": APK_NAME,
        "artifact_sha256": APK_SHA256, "provenance_kind": "android-build",
    }
    for name, value in expected.items():
        require(build.get(name) == value, f"Original build provenance mismatch: {name}")
    require(metadata["applicationId"] == "com.sednalabs.solarlab.internal", "Package ID mismatch")
    require(len(metadata["elements"]) == 1, "Expected one universal APK")
    element = metadata["elements"][0]
    require(element["versionName"] == VERSION and element["versionCode"] == VERSION_CODE,
            "Android output version mismatch")


def verify_artifact(archive: Path, destination: Path) -> list[Path]:
    require(digest(archive) == ARCHIVE_SHA256, "Downloaded archive digest mismatch")
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zipped:
        # Select explicit basenames and write them ourselves; never extract paths.
        for name in PUBLIC_NAMES:
            matches = [entry for entry in zipped.infolist()
                       if not entry.is_dir() and Path(entry.filename).name == name]
            require(len(matches) == 1, f"Missing or ambiguous public artifact: {name}")
            require(matches[0].file_size < 32 * 1024 * 1024, "Unexpected artifact size")
            (destination / name).write_bytes(zipped.read(matches[0]))
    require(digest(destination / APK_NAME) == APK_SHA256, "APK identity changed")
    require((destination / (APK_NAME + ".sha256")).read_text().split()[0] == APK_SHA256,
            "Original checksum sidecar mismatch")
    verify_metadata(json.loads((destination / BUILD_NAME).read_text()),
                    json.loads((destination / "output-metadata.json").read_text()))
    with zipfile.ZipFile(destination / APK_NAME) as apk:
        for abi in ("arm64-v8a", "x86_64"):
            native = apk.read(f"lib/{abi}/libsolarlab_v2.so")
            require(SOURCE.encode() in native, f"Embedded native source mismatch: {abi}")
        maps = {
            "earth-nasa.png": "d1d3cb36ae6ae452c43cdf7ae0ffc13a959e8a5a2358f58b05e5f623c15119f9",
            "moon-nasa.jpg": "b246064f217f8d479df78c49c7c8595a8f5fbda008a72fd539978d2e121e0109",
        }
        for name, expected in maps.items():
            raw = apk.read(f"assets/solarlab/surfaces/{name}")
            require(hashlib.sha256(raw).hexdigest() == expected, f"Bundled map mismatch: {name}")
    return [destination / name for name in PUBLIC_NAMES]


def publish(archive: Path, destination: Path) -> None:
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Unexpected repository")
    require(os.environ.get("GITHUB_REF") == RECOVERY_REF, "Unexpected recovery branch")
    evidence = verify_evidence()
    require(api(f"releases/tags/{TAG}", missing_ok=True) is None, "Refuses to replace an existing release")
    with archive.open("wb") as output:
        subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{ARTIFACT_ID}/zip"],
                       stdout=output, check=True)
    assets = verify_artifact(archive, destination)
    notes = Path(__file__).resolve().parents[1] / "release-notes" / "earth-moon-alpha1.md"
    require(len(notes.read_bytes()) < 100_000, "Release body exceeds the conservative byte limit")
    provenance = {
        "schema_version": 1, "provenance_kind": "github-release-recovery",
        "repository": REPOSITORY, "workflow": os.environ["GITHUB_WORKFLOW"],
        "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
        "publisher_commit_sha": os.environ["GITHUB_SHA"],
        "release_tag": TAG, "release_target": SOURCE, "checkout_ref": SOURCE,
        "version_name": VERSION, "version_code": str(VERSION_CODE),
        "build_variant": "prerelease", "release_channel": "prerelease",
        "artifact_name": APK_NAME, "artifact_sha256": APK_SHA256,
        "apk_signer_certificate_sha256": "4fa5ee40f02d150bdf5f3e37445723f2ff4c7500bff19e1545226f549dbafede",
        "source_artifact_id": ARTIFACT_ID, "source_archive_sha256": ARCHIVE_SHA256,
        "source_run_id": PRODUCER_RUN, "source_run_attempt": 1,
        "source_run_conclusion": "failure", "source_failure_job": "Publish GitHub release",
        "qualification_run_ids": list(VALIDATION_RUNS),
        "launch_smoke_outcome": "success", "arm64_isa_proof": "success",
        "original_build_provenance": BUILD_NAME, **evidence,
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    provenance_path = destination / f"release-provenance-{VERSION}.json"
    provenance_path.write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    assets.append(provenance_path)
    # Check again immediately before the only creation. Never clobber assets.
    verify_release_tag()
    require(api(f"releases/tags/{TAG}", missing_ok=True) is None, "Release appeared during verification")
    subprocess.run(["gh", "release", "create", TAG, "--repo", REPOSITORY,
                    "--verify-tag", "--target", SOURCE, "--draft", "--prerelease",
                    "--title", f"Solar Gravity Lab {VERSION} (Earth–Moon Laboratory Preview)",
                    "--notes-file", str(notes)], check=True)
    subprocess.run(["gh", "release", "upload", TAG, "--repo", REPOSITORY,
                    *map(str, assets)], check=True)
    with tempfile.TemporaryDirectory(prefix="release-download-check-") as temp:
        subprocess.run(["gh", "release", "download", TAG, "--repo", REPOSITORY,
                        "--dir", temp], check=True)
        for original in assets:
            require(digest(Path(temp) / original.name) == digest(original),
                    f"Uploaded asset digest mismatch: {original.name}")
    verify_release_tag()
    subprocess.run(["gh", "release", "edit", TAG, "--repo", REPOSITORY,
                    "--draft=false", "--prerelease"], check=True)
    released = api(f"releases/tags/{TAG}")
    require(not released["draft"] and released["prerelease"], "Release visibility/channel mismatch")
    verify_release_tag()
    print(json.dumps({"release_url": released["html_url"], "source": SOURCE,
                      "apk_sha256": APK_SHA256, "unchanged_producer_run": PRODUCER_RUN}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--verify-local-only", action="store_true")
    args = parser.parse_args()
    if args.verify_local_only:
        print(json.dumps([str(path) for path in verify_artifact(args.archive, args.destination)]))
    else:
        publish(args.archive, args.destination)


if __name__ == "__main__":
    main()
