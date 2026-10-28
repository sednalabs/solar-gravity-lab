#!/usr/bin/env python3
"""Contract fixtures for reusable prerelease identity and APK intake."""
import copy
import io
import json
from pathlib import Path
import tempfile
import zipfile

import intake_qualified_prerelease as intake


def rejected(fn):
    try:
        fn()
    except (ValueError, zipfile.BadZipFile, json.JSONDecodeError):
        return
    raise AssertionError("invalid intake accepted")


def bundle(provenance, metadata, apks):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("dist/build-provenance-fixture.json", json.dumps(provenance))
        z.writestr("clients/app/build/outputs/apk/prerelease/output-metadata.json", json.dumps(metadata))
        for path, value in apks.items():
            z.writestr(path, value)
    return buffer.getvalue()


def fixture(run_id, artifact_id, source_sha, workflow_sha, apk):
    repo = intake.REPOSITORY
    run = {"id": int(run_id), "status": "completed", "conclusion": "success", "path": ".github/workflows/prerelease-apk.yml", "head_sha": workflow_sha, "run_attempt": 1, "repository": {"full_name": repo}, "head_repository": {"full_name": repo}}
    provenance = {"schema_version": 1, "provenance_kind": "android-build", "repository": repo, "workflow": "prerelease-apk", "run_id": run_id, "run_attempt": "1", "commit_sha": source_sha, "checkout_ref": "selected-branch", "build_variant": "prerelease", "release_channel": "prerelease", "version_name": "1.0", "artifact_name": "app.apk", "artifact_sha256": intake.sha256(apk)}
    metadata = {"applicationId": intake.PACKAGE, "variantName": "prerelease"}
    archive = bundle(provenance, metadata, {"dist/app.apk": apk})
    artifact = {"id": int(artifact_id), "name": "artifact-" + artifact_id, "expired": False, "digest": "sha256:" + intake.sha256(archive), "workflow_run": {"id": int(run_id), "head_sha": workflow_sha}}
    return run, artifact, provenance, metadata, archive


def main():
    repo = intake.REPOSITORY
    # The second valid fixture has a different producer workflow head and APK
    # source, as supported by dispatching a producer workflow with a ref input.
    cases = [
        ("90001", "80001", "a" * 40, "a" * 40, b"fixture-one"),
        ("90002", "80002", "b" * 40, "c" * 40, b"fixture-two"),
    ]
    for run_id, artifact_id, source_sha, workflow_sha, apk in cases:
        run, artifact, provenance, metadata, archive = fixture(run_id, artifact_id, source_sha, workflow_sha, apk)
        intake.check_inputs(repo, run_id, artifact_id, source_sha)
        digest = intake.check_identity(repo, run_id, artifact_id, run, artifact)
        assert intake.unpack_verified(archive, run, repo, source_sha, digest)[0] == apk

    run_id, artifact_id, source_sha, workflow_sha, apk = cases[1]
    run, artifact, provenance, metadata, archive = fixture(run_id, artifact_id, source_sha, workflow_sha, apk)
    for path, value in [("id", 7), ("conclusion", "failure"), ("path", "other")]:
        bad = copy.deepcopy(run)
        bad[path] = value
        rejected(lambda: intake.check_identity(repo, run_id, artifact_id, bad, artifact))
    bad = copy.deepcopy(run)
    bad["head_repository"]["full_name"] = "other/repo"
    rejected(lambda: intake.check_identity(repo, run_id, artifact_id, bad, artifact))
    for path, value in [("id", 7), ("digest", "sha256:bad"), ("expired", True)]:
        bad = copy.deepcopy(artifact)
        bad[path] = value
        rejected(lambda: intake.check_identity(repo, run_id, artifact_id, run, bad))
    bad = copy.deepcopy(artifact)
    bad["workflow_run"]["head_sha"] = "d" * 40
    rejected(lambda: intake.check_identity(repo, run_id, artifact_id, run, bad))
    rejected(lambda: intake.check_inputs("other/repo", run_id, artifact_id, source_sha))
    rejected(lambda: intake.check_inputs(repo, "bad", artifact_id, source_sha))
    rejected(lambda: intake.check_inputs(repo, run_id, artifact_id, "bad"))

    def verify(prov=provenance, meta=metadata, apks=None):
        data = bundle(prov, meta, apks if apks is not None else {"dist/app.apk": apk})
        return intake.unpack_verified(data, run, repo, source_sha, intake.sha256(data))
    bad = copy.deepcopy(provenance)
    bad["commit_sha"] = "d" * 40
    rejected(lambda: verify(prov=bad))
    bad = copy.deepcopy(provenance)
    bad["build_variant"] = "debug"
    rejected(lambda: verify(prov=bad))
    bad = copy.deepcopy(metadata)
    bad["applicationId"] = "wrong.package"
    rejected(lambda: verify(meta=bad))
    bad = copy.deepcopy(metadata)
    bad["variantName"] = "debug"
    rejected(lambda: verify(meta=bad))
    rejected(lambda: verify(apks={"dist/app.apk": apk, "dist/extra.apk": apk}))
    rejected(lambda: verify(apks={"dist/app.apk": b"wrong-apk"}))
    rejected(lambda: intake.unpack_verified(archive, run, repo, source_sha, "0" * 64))
    with tempfile.TemporaryDirectory() as temp:
        temp_dir = Path(temp)
        sentinel = temp_dir / "executed"
        for index, filename in enumerate(("$(touch executed).apk", "`touch executed`.apk", "bad\ninjected=value.apk")):
            malicious = copy.deepcopy(provenance)
            malicious["artifact_name"] = filename
            data = bundle(malicious, metadata, {"dist/" + filename: apk})
            verified, actual_provenance, _ = intake.unpack_verified(
                data, run, repo, source_sha, intake.sha256(data)
            )
            staged = intake.stage_apk(temp_dir, verified)
            assert staged == temp_dir / "qualified-prerelease.apk"
            assert staged.read_bytes() == apk
            assert actual_provenance["artifact_name"] == filename
            output = temp_dir / f"outputs-{index}"
            intake.write_intake_outputs(output, {"apk_path": str(staged)})
            assert output.read_text() == f"apk_path={staged}\n"
            assert not sentinel.exists()
        rejected(lambda: intake.write_intake_outputs(temp_dir / "bad-output", {"apk_path": "bad\ninjected=value"}))
        assert not (temp_dir / "bad-output").exists()
    intake.check_apk_properties(intake.PACKAGE, "false")
    rejected(lambda: intake.check_apk_properties("wrong.package", "false"))
    rejected(lambda: intake.check_apk_properties(intake.PACKAGE, "true"))
    print("qualified prerelease intake tests passed")


if __name__ == "__main__":
    main()
