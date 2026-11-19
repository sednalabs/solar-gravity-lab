"""Static trust-boundary checks; never execute a selected source or a workflow."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


GITHUB = Path(__file__).resolve().parents[1]
WORKFLOWS = [GITHUB / "workflows" / name for name in (
    "validation-lab.yml", "prerelease-apk.yml", "interactive-android-build.yml",
)]
ACTIONS = [GITHUB / "actions" / name / "action.yml" for name in (
    "rust-shared-cache", "prepare-rust-android-toolchain", "install-sccache",
)]
WRITE = "github.event_name == 'push' && github.ref == 'refs/heads/main'"
READ_ONLY = "github.event_name != 'push' || github.ref != 'refs/heads/main'"


def steps(path: Path, indent: int) -> list[str]:
    """Read step blocks without introducing a YAML dependency in the plan job."""
    result: list[str] = []
    current: list[str] = []
    for line in path.read_text().splitlines():
        starts_step = line.startswith(" " * indent + "- ")
        leaves_step = line.strip() and len(line) - len(line.lstrip()) <= indent
        if starts_step or leaves_step:
            if current:
                result.append("\n".join(current))
                current = []
        if starts_step or current:
            current.append(line)
    if current:
        result.append("\n".join(current))
    return result


def field(step: str, key: str) -> str:
    match = re.search(rf"(?m)^\s*(?:- )?{re.escape(key)}: (.*)$", step)
    return match.group(1) if match else ""


class WorkflowCachePolicyTests(unittest.TestCase):
    def test_all_automatic_and_explicit_cache_writes_require_main_push(self) -> None:
        writers = 0
        for path in WORKFLOWS + ACTIONS:
            for step in steps(path, 6 if path in WORKFLOWS else 4):
                if not re.match(r"actions/cache(?:/save)?@", field(step, "uses")):
                    continue
                writers += 1
                condition = field(step, "if")
                self.assertTrue(condition.startswith("${{ " + WRITE), (path, condition))
                # Additional conditions may narrow this policy, never bypass it.
                self.assertNotIn("||", condition, (path, condition))
        self.assertEqual(8, writers)

    def test_manual_and_pr_runs_can_restore_the_same_composite_cache_keys(self) -> None:
        for path in ACTIONS:
            cache_steps = [s for s in steps(path, 4) if field(s, "uses").startswith("actions/cache")]
            self.assertEqual(2, len(cache_steps), path)
            writable, readonly = cache_steps
            self.assertTrue(field(readonly, "uses").startswith("actions/cache/restore@"), path)
            self.assertEqual("${{ " + READ_ONLY + " }}", field(readonly, "if"), path)
            self.assertEqual(writable.split("      with:\n")[1].strip(),
                             readonly.split("      with:\n")[1].strip(), path)

    def test_gradle_caches_are_read_only_outside_main_push(self) -> None:
        count = 0
        for path in WORKFLOWS:
            for step in steps(path, 6):
                if field(step, "uses").startswith("gradle/actions/setup-gradle@"):
                    count += 1
                    self.assertEqual("${{ " + READ_ONLY + " }}", field(step, "cache-read-only"), path)
        self.assertEqual(5, count)

    def test_remote_write_credentials_are_not_passed_to_selected_manual_sources(self) -> None:
        sensitive = (
            "SCCACHE_AWS_ACCESS_KEY_ID", "SCCACHE_AWS_SECRET_ACCESS_KEY", "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY", "GRADLE_REMOTE_CACHE_PASSWORD",
        )
        for path in WORKFLOWS:
            text = path.read_text()
            keys = sensitive + (("cache-encryption-key",) if path in WORKFLOWS[:2] else ())
            for key in keys:
                values = re.findall(rf"(?m)^\s*{re.escape(key)}: (.*)$", text)
                self.assertTrue(values, (path, key))
                for value in values:
                    self.assertTrue(value.startswith("${{ " + WRITE + " && ("), (path, key))
                    self.assertTrue(value.endswith(") || '' }}"), (path, key))
            for value in re.findall(r"(?m)^\s*GRADLE_REMOTE_CACHE_PUSH: (.*)$", text):
                self.assertEqual("${{ " + WRITE + " && 'true' || 'false' }}", value, path)

    def test_checkout_credentials_are_never_retained_in_source_jobs(self) -> None:
        for path in WORKFLOWS:
            for step in steps(path, 6):
                if field(step, "uses").startswith("actions/checkout@"):
                    self.assertEqual("false", field(step, "persist-credentials"), (path, step))
        writeback = next(s for s in steps(WORKFLOWS[0], 6) if "git push origin" in s)
        self.assertEqual("${{ github.event_name == 'workflow_dispatch' && inputs.write_wrapper }}",
                         field(writeback, "if"))
        self.assertEqual("${{ github.token }}", field(writeback, "GH_TOKEN"))

    def test_manual_source_selection_and_exact_release_proof_are_preserved(self) -> None:
        validation, release, interactive = [p.read_text() for p in WORKFLOWS]
        self.assertIn("EVENT_SHA: ${{ github.event.pull_request.head.sha || github.sha }}", validation)
        self.assertIn("ref: ${{ needs.metadata.outputs.checkout_ref }}", validation)
        self.assertIn("ref: ${{ github.event_name == 'push' && github.sha || needs.route.outputs.checkout_ref || github.sha }}", release)
        self.assertIn("ref: ${{ github.event_name == 'push' && github.sha || inputs.ref || github.sha }}", interactive)
        self.assertEqual(2, release.count("ref: ${{ needs.build-prerelease-apk.outputs.head_sha }}"))
        self.assertIn("needs.build-prerelease-apk.result == 'success'", release)
        self.assertIn("needs.isa-prerelease-proof.result == 'success'", release)


if __name__ == "__main__":
    unittest.main()
