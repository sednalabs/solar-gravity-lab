#!/usr/bin/env python3
"""Refusal-path coverage for publication of the fixed, already-built alpha APK."""
from __future__ import annotations

import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import publish_earth_moon_alpha1 as recovery


def metadata_fixture():
    build = {
        "repository": recovery.REPOSITORY,
        "commit_sha": recovery.SOURCE,
        "checkout_ref": recovery.SOURCE,
        "run_id": str(recovery.PRODUCER_RUN),
        "run_attempt": "1",
        "version_name": recovery.VERSION,
        "version_code": str(recovery.VERSION_CODE),
        "build_variant": "prerelease",
        "release_channel": "prerelease",
        "artifact_name": recovery.APK_NAME,
        "artifact_sha256": recovery.APK_SHA256,
        "provenance_kind": "android-build",
    }
    metadata = {
        "applicationId": "com.sednalabs.solarlab.internal",
        "elements": [{"versionName": recovery.VERSION, "versionCode": recovery.VERSION_CODE}],
    }
    return build, metadata


def evidence_fixture():
    responses = {
        f"actions/runs/{run_id}": {
            "head_sha": recovery.SOURCE, "run_attempt": 1,
            "status": "completed", "conclusion": "success",
        }
        for run_id in recovery.VALIDATION_RUNS
    }
    responses[f"actions/runs/{recovery.PRODUCER_RUN}"] = {
        "head_sha": recovery.SOURCE, "run_attempt": 1,
        "status": "completed", "conclusion": "failure", "event": "push",
        "head_branch": f"release/{recovery.VERSION}",
        "path": ".github/workflows/prerelease-apk.yml",
    }
    responses[f"actions/runs/{recovery.PRODUCER_RUN}/attempts/1/jobs?per_page=100"] = {
        "total_count": 4,
        "jobs": [
            {"id": 101, "name": "Release request gate", "conclusion": "success"},
            {"id": 102, "name": "Build internal dev preview APK (disabled)",
             "conclusion": "success", "steps": [
                 {"name": name, "conclusion": "success"}
                 for name in (
                     "Assemble prerelease APK", "Verify Rust JNI DTO identities after R8",
                     "Emulator launch smoke for packaged APK", "Upload prerelease APK artifact",
                 )
             ]},
            {"id": 103, "name": "ARM64 ISA prerelease proof", "conclusion": "success"},
            {"id": 104, "name": "Publish GitHub release", "conclusion": "failure"},
        ],
    }
    responses[f"git/ref/heads/release/{recovery.VERSION}"] = {
        "object": {"type": "commit", "sha": recovery.SOURCE},
    }
    responses[f"git/ref/tags/{recovery.TAG}"] = {
        "object": {"type": "commit", "sha": recovery.SOURCE},
    }
    responses[f"actions/artifacts/{recovery.ARTIFACT_ID}"] = {
        "expired": False,
        "name": f"solar-gravity-lab-{recovery.VERSION}-internal-dev-preview-{recovery.PRODUCER_RUN}",
        "digest": f"sha256:{recovery.ARCHIVE_SHA256}",
        "workflow_run": {"id": recovery.PRODUCER_RUN, "head_sha": recovery.SOURCE},
    }
    return responses


def fake_api(responses):
    def fetch(path, *, missing_ok=False):
        if path not in responses:
            raise AssertionError(f"Unexpected GitHub request: {path}")
        return copy.deepcopy(responses[path])
    return fetch


class MetadataTests(unittest.TestCase):
    def test_original_metadata_matches_the_pinned_binary_identity(self):
        recovery.verify_metadata(*metadata_fixture())

    def test_each_provenance_field_is_required_and_must_match(self):
        original, metadata = metadata_fixture()
        for field in original:
            for missing in (False, True):
                with self.subTest(field=field, missing=missing):
                    build = dict(original)
                    if missing:
                        del build[field]
                    else:
                        build[field] = "different"
                    with self.assertRaisesRegex(ValueError, "Original build provenance mismatch"):
                        recovery.verify_metadata(build, metadata)

    def test_android_package_and_version_mismatch_or_split_output_is_rejected(self):
        build, original = metadata_fixture()
        variants = []
        wrong_package = copy.deepcopy(original)
        wrong_package["applicationId"] = "com.sednalabs.solarlab"
        variants.append(wrong_package)
        for field, value in (("versionName", "0.1.0-alpha.8"), ("versionCode", 1),
                             ("versionCode", str(recovery.VERSION_CODE))):
            metadata = copy.deepcopy(original)
            metadata["elements"][0][field] = value
            variants.append(metadata)
        variants.extend({**original, "elements": elements}
                        for elements in ([], original["elements"] * 2))
        for metadata in variants:
            with self.subTest(metadata=metadata), self.assertRaises(ValueError):
                recovery.verify_metadata(build, metadata)


class EvidenceTests(unittest.TestCase):
    def test_recorded_publication_failure_is_allowed_only_with_build_and_isa_proof(self):
        self.assertEqual(recovery.verify_evidence(fake_api(evidence_fixture())), {
            "producer_build_job": 102, "producer_isa_job": 103,
        })

    def test_each_qualification_requires_exact_source_attempt_and_success(self):
        for run_id in recovery.VALIDATION_RUNS:
            for field, value in (("head_sha", "f" * 40), ("run_attempt", 2),
                                 ("status", "in_progress"), ("conclusion", "failure"),
                                 ("conclusion", "skipped")):
                with self.subTest(run_id=run_id, field=field, value=value):
                    responses = evidence_fixture()
                    responses[f"actions/runs/{run_id}"][field] = value
                    with self.assertRaisesRegex(ValueError, "Qualification"):
                        recovery.verify_evidence(fake_api(responses))

    def test_wrong_producer_source_attempt_branch_event_or_workflow_is_rejected(self):
        for field, value in (("head_sha", "f" * 40), ("run_attempt", 2),
                             ("head_branch", "main"), ("event", "workflow_dispatch"),
                             ("path", ".github/workflows/other.yml"),
                             ("status", "in_progress"), ("conclusion", "success")):
            with self.subTest(field=field):
                responses = evidence_fixture()
                responses[f"actions/runs/{recovery.PRODUCER_RUN}"][field] = value
                with self.assertRaises(ValueError):
                    recovery.verify_evidence(fake_api(responses))

    def test_missing_ambiguous_failed_or_truncated_producer_proofs_are_rejected(self):
        path = f"actions/runs/{recovery.PRODUCER_RUN}/attempts/1/jobs?per_page=100"
        for mode in ("failed-build", "failed-isa", "skipped-isa", "missing-isa",
                     "duplicate-build", "other-failure", "truncated"):
            with self.subTest(mode=mode):
                responses = evidence_fixture()
                page = responses[path]
                if mode == "failed-build":
                    page["jobs"][1]["conclusion"] = "failure"
                elif mode in ("failed-isa", "skipped-isa"):
                    page["jobs"][2]["conclusion"] = mode.split("-")[0].replace("failed", "failure")
                elif mode == "missing-isa":
                    del page["jobs"][2]
                elif mode == "duplicate-build":
                    page["jobs"].append(copy.deepcopy(page["jobs"][1]))
                elif mode == "other-failure":
                    page["jobs"][0]["conclusion"] = "failure"
                page["total_count"] = len(page["jobs"]) + (mode == "truncated")
                with self.assertRaises(ValueError):
                    recovery.verify_evidence(fake_api(responses))

    def test_packaged_smoke_r8_assembly_and_upload_cannot_be_skipped_or_failed(self):
        path = f"actions/runs/{recovery.PRODUCER_RUN}/attempts/1/jobs?per_page=100"
        for index in range(4):
            for conclusion in ("failure", "skipped", None):
                with self.subTest(step=index, conclusion=conclusion):
                    responses = evidence_fixture()
                    responses[path]["jobs"][1]["steps"][index]["conclusion"] = conclusion
                    with self.assertRaisesRegex(ValueError, "Required packaged proof did not pass"):
                        recovery.verify_evidence(fake_api(responses))

    def test_branch_or_lightweight_tag_must_still_point_to_qualified_source(self):
        for ref in (f"heads/release/{recovery.VERSION}", f"tags/{recovery.TAG}"):
            responses = evidence_fixture()
            responses[f"git/ref/{ref}"]["object"]["sha"] = "f" * 40
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                recovery.verify_evidence(fake_api(responses))

    def test_annotated_tag_is_resolved_and_wrong_or_cyclic_targets_are_rejected(self):
        responses = evidence_fixture()
        responses[f"git/ref/tags/{recovery.TAG}"]["object"] = {"type": "tag", "sha": "a" * 40}
        responses[f"git/tags/{'a' * 40}"] = {"object": {"type": "commit", "sha": recovery.SOURCE}}
        recovery.verify_evidence(fake_api(responses))
        for target in ({"type": "commit", "sha": "b" * 40},
                       {"type": "tree", "sha": recovery.SOURCE},
                       {"type": "tag", "sha": "a" * 40}):
            with self.subTest(target=target):
                responses[f"git/tags/{'a' * 40}"]["object"] = target
                with self.assertRaisesRegex(ValueError, "Existing release tag"):
                    recovery.verify_evidence(fake_api(responses))

    def test_expired_renamed_replaced_or_wrong_producer_artifact_is_rejected(self):
        path = f"actions/artifacts/{recovery.ARTIFACT_ID}"
        mutations = [
            ("expired", True), ("name", "another-artifact"), ("digest", "sha256:" + "f" * 64),
            ("workflow_run", {"id": recovery.PRODUCER_RUN + 1, "head_sha": recovery.SOURCE}),
            ("workflow_run", {"id": recovery.PRODUCER_RUN, "head_sha": "f" * 40}),
        ]
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                responses = evidence_fixture()
                responses[path][field] = value
                with self.assertRaises(ValueError):
                    recovery.verify_evidence(fake_api(responses))


class PublicationBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "original.zip"
        self.destination = self.root / "assets"
        self.environment = {
            "GITHUB_REPOSITORY": recovery.REPOSITORY, "GITHUB_REF": recovery.RECOVERY_REF,
            "GITHUB_WORKFLOW": "Publish qualified Earth–Moon preview", "GITHUB_RUN_ID": "999999",
            "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": "c" * 40,
        }

    def test_bad_archive_digest_fails_before_any_destination_write(self):
        self.archive.write_bytes(b"not the qualified archive")
        with self.assertRaisesRegex(ValueError, "Downloaded archive digest mismatch"):
            recovery.verify_artifact(self.archive, self.destination)
        self.assertFalse(self.destination.exists())

    def test_wrong_repository_or_recovery_branch_stops_before_api_or_subprocess(self):
        for field in ("GITHUB_REPOSITORY", "GITHUB_REF"):
            environment = {**self.environment, field: "wrong"}
            with self.subTest(field=field), mock.patch.dict(os.environ, environment, clear=True), \
                    mock.patch.object(recovery, "verify_evidence") as evidence, \
                    mock.patch.object(recovery, "api") as api, \
                    mock.patch.object(recovery.subprocess, "run") as command:
                with self.assertRaises(ValueError):
                    recovery.publish(self.archive, self.destination)
                evidence.assert_not_called()
                api.assert_not_called()
                command.assert_not_called()

    def test_failed_source_proof_prevents_download_and_publication(self):
        responses = evidence_fixture()
        responses[f"actions/runs/{recovery.VALIDATION_RUNS[0]}"]["head_sha"] = "f" * 40
        original_verify = recovery.verify_evidence
        with mock.patch.dict(os.environ, self.environment, clear=True), \
                mock.patch.object(recovery, "verify_evidence", side_effect=lambda: original_verify(fake_api(responses))), \
                mock.patch.object(recovery, "api") as api, \
                mock.patch.object(recovery.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "Qualification source"):
                recovery.publish(self.archive, self.destination)
            api.assert_not_called()
            command.assert_not_called()
        self.assertFalse(self.archive.exists())

    def test_existing_release_is_refused_before_downloading_anything(self):
        with mock.patch.dict(os.environ, self.environment, clear=True), \
                mock.patch.object(recovery, "verify_evidence", return_value={}), \
                mock.patch.object(recovery, "api", return_value={"id": 123}), \
                mock.patch.object(recovery.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "Refuses to replace"):
                recovery.publish(self.archive, self.destination)
            command.assert_not_called()
        self.assertFalse(self.archive.exists())

    def test_corrupt_download_cannot_reach_a_github_write(self):
        commands = []

        def command(argv, **kwargs):
            commands.append(argv)
            self.assertEqual(argv[:3], ["gh", "api", f"repos/{recovery.REPOSITORY}/actions/artifacts/{recovery.ARTIFACT_ID}/zip"])
            kwargs["stdout"].write(b"corrupted download")
            return subprocess.CompletedProcess(argv, 0)

        with mock.patch.dict(os.environ, self.environment, clear=True), \
                mock.patch.object(recovery, "verify_evidence", return_value={}), \
                mock.patch.object(recovery, "api", return_value=None), \
                mock.patch.object(recovery.subprocess, "run", side_effect=command):
            with self.assertRaisesRegex(ValueError, "Downloaded archive digest mismatch"):
                recovery.publish(self.archive, self.destination)
        self.assertEqual(len(commands), 1)
        self.assertFalse(self.destination.exists())

    def test_release_appearing_during_verification_is_not_replaced(self):
        def verified_artifact(*args):
            self.destination.mkdir()
            return []

        with mock.patch.dict(os.environ, self.environment, clear=True), \
                mock.patch.object(recovery, "verify_evidence", return_value={}), \
                mock.patch.object(recovery, "api", side_effect=[None, {"id": 123}]), \
                mock.patch.object(recovery, "verify_release_tag", return_value=None), \
                mock.patch.object(recovery, "verify_artifact", side_effect=verified_artifact), \
                mock.patch.object(recovery.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "Release appeared during verification"):
                recovery.publish(self.archive, self.destination)
            self.assertEqual(command.call_count, 1)
            self.assertEqual(command.call_args.args[0][:2], ["gh", "api"])

    def test_assets_and_tag_are_verified_before_draft_becomes_public(self):
        for mode in ("success", "corrupt-upload", "changed-tag"):
            with self.subTest(mode=mode):
                commands, uploaded = [], []

                def verified_artifact(*args):
                    self.destination.mkdir(exist_ok=True)
                    asset = self.destination / recovery.APK_NAME
                    asset.write_bytes(b"unchanged verified APK fixture")
                    return [asset]

                def command(argv, **kwargs):
                    commands.append(argv)
                    if argv[1] == "api":
                        kwargs["stdout"].write(b"download fixture")
                    elif argv[2] == "upload":
                        uploaded.extend(Path(path) for path in argv[6:])
                    elif argv[2] == "download":
                        target = Path(argv[argv.index("--dir") + 1])
                        for original in uploaded:
                            raw = original.read_bytes()
                            if mode == "corrupt-upload" and original.name == recovery.APK_NAME:
                                raw += b"changed"
                            (target / original.name).write_bytes(raw)
                    return subprocess.CompletedProcess(argv, 0)

                tag_checks = [None, None, None]
                if mode == "changed-tag":
                    tag_checks[1] = ValueError("Existing release tag changed")
                final_release = {"draft": False, "prerelease": True, "html_url": "https://example.invalid/release"}
                with mock.patch.dict(os.environ, self.environment, clear=True), \
                        mock.patch.object(recovery, "verify_evidence", return_value={"producer_build_job": 102, "producer_isa_job": 103}), \
                        mock.patch.object(recovery, "api", side_effect=[None, None, final_release]), \
                        mock.patch.object(recovery, "verify_release_tag", side_effect=tag_checks), \
                        mock.patch.object(recovery, "verify_artifact", side_effect=verified_artifact), \
                        mock.patch.object(recovery.subprocess, "run", side_effect=command), \
                        contextlib.redirect_stdout(io.StringIO()):
                    if mode == "success":
                        recovery.publish(self.archive, self.destination)
                    else:
                        with self.assertRaisesRegex(ValueError, "Uploaded asset digest|Existing release tag"):
                            recovery.publish(self.archive, self.destination)
                release_commands = [argv for argv in commands if argv[1] == "release"]
                self.assertIn("--draft", release_commands[0])
                self.assertIn("--verify-tag", release_commands[0])
                self.assertFalse(any("--clobber" in argv for argv in commands))
                self.assertEqual(any(argv[2] == "edit" for argv in release_commands), mode == "success")
                provenance = json.loads((self.destination / f"release-provenance-{recovery.VERSION}.json").read_text())
                self.assertEqual(provenance["source_run_conclusion"], "failure")
                self.assertEqual(provenance["release_target"], recovery.SOURCE)
                self.assertEqual(provenance["publisher_commit_sha"], self.environment["GITHUB_SHA"])
                self.assertNotEqual(provenance["publisher_commit_sha"], provenance["release_target"])
                self.assertEqual(provenance["original_build_provenance"], recovery.BUILD_NAME)

    def test_local_verify_success_never_contacts_github_or_publishes(self):
        paths = [self.destination / recovery.APK_NAME]
        argv = ["publish", "--archive", str(self.archive), "--destination", str(self.destination), "--verify-local-only"]
        output = io.StringIO()
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(recovery, "verify_artifact", return_value=paths) as verify, \
                mock.patch.object(recovery, "publish") as publish, \
                mock.patch.object(recovery, "api") as api, \
                mock.patch.object(recovery.subprocess, "run") as command, \
                contextlib.redirect_stdout(output):
            recovery.main()
            verify.assert_called_once_with(self.archive, self.destination)
            publish.assert_not_called()
            api.assert_not_called()
            command.assert_not_called()
        self.assertEqual(json.loads(output.getvalue()), [str(path) for path in paths])

    def test_local_verify_failure_cannot_fall_through_to_publication(self):
        self.archive.write_bytes(b"wrong archive")
        argv = ["publish", "--archive", str(self.archive), "--destination", str(self.destination), "--verify-local-only"]
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(recovery, "publish") as publish, \
                mock.patch.object(recovery, "api") as api, \
                mock.patch.object(recovery.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "Downloaded archive digest mismatch"):
                recovery.main()
            publish.assert_not_called()
            api.assert_not_called()
            command.assert_not_called()
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
