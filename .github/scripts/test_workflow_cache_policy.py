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
EVENT_SOURCE = "${{ github.sha }}"
VALIDATION_SOURCE = "${{ github.event.pull_request.head.sha || github.sha }}"
QUALIFIED_TOOL_CHECKOUTS = {
    "KhronosGroup/glslang": ("e1b562a8bed273a02f30b59b66a5d499793cede5", ".hardware/glslang"),
    "KhronosGroup/Vulkan-Headers": ("88487f687f3100ebe1d3559a8a34d971ee85b977", ".hardware/vulkan-headers"),
}


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


def job(path: Path, name: str) -> str:
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [A-Za-z0-9_-]+:\n|\Z)", path.read_text())
    if match is None:
        raise AssertionError(f"Missing job {name} in {path}")
    return match.group(0)


def checkout_identity(step: str, allow_qualified_tools: bool = False) -> str:
    """Product source is event-bound; only named, pinned tools use another repository."""
    for key in ("repository", "ref", "path", "persist-credentials"):
        if len(re.findall(rf"(?m)^\s*{re.escape(key)}:", step)) > 1:
            raise AssertionError("Duplicate checkout identity field")
    repository = field(step, "repository")
    if not repository:
        if field(step, "ref") != EVENT_SOURCE:
            raise AssertionError("Product checkout must use the workflow event source")
        return "product"
    if not allow_qualified_tools or repository not in QUALIFIED_TOOL_CHECKOUTS:
        raise AssertionError("Unapproved external checkout repository")
    expected_ref, expected_path = QUALIFIED_TOOL_CHECKOUTS[repository]
    pinned = re.fullmatch(r"([0-9a-f]{40})(?:[ \t]+#.*)?", field(step, "ref"))
    if pinned is None or pinned.group(1) != expected_ref:
        raise AssertionError("Tool checkout must use its approved immutable commit")
    if field(step, "path") != expected_path:
        raise AssertionError("Tool checkout must use its isolated approved directory")
    if field(step, "persist-credentials") != "false":
        raise AssertionError("Tool checkout must not retain credentials")
    return repository


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
        self.assertEqual(9, writers)

    def test_manual_and_pr_runs_can_restore_the_same_composite_cache_keys(self) -> None:
        for path in ACTIONS:
            cache_steps = [s for s in steps(path, 4) if field(s, "uses").startswith("actions/cache")]
            self.assertEqual(2, len(cache_steps), path)
            writable, readonly = cache_steps
            self.assertTrue(field(readonly, "uses").startswith("actions/cache/restore@"), path)
            self.assertEqual("${{ " + READ_ONLY + " }}", field(readonly, "if"), path)
            self.assertEqual(writable.split("      with:\n")[1].strip(),
                             readonly.split("      with:\n")[1].strip(), path)

    def test_shader_tool_restore_does_not_write_and_save_uses_the_restored_key(self) -> None:
        release_steps = steps(WORKFLOWS[1], 6)
        restore = next(s for s in release_steps if field(s, "id") == "benchmark-glslang-cache")
        save = next(s for s in release_steps if field(s, "name") == "Save the qualified QCOM compiler cache")
        self.assertTrue(field(restore, "uses").startswith("actions/cache/restore@"))
        self.assertEqual("needs.route.outputs.build_variant == 'benchmark'", field(restore, "if"))
        self.assertTrue(field(save, "uses").startswith("actions/cache/save@"))
        self.assertEqual("${{ " + WRITE + " && needs.route.outputs.build_variant == 'benchmark' && "
                         "steps.benchmark-glslang-cache.outputs.cache-hit != 'true' }}", field(save, "if"))
        self.assertEqual(".hardware/glslang-build", field(restore, "path"))
        self.assertEqual(field(restore, "path"), field(save, "path"))
        self.assertEqual("${{ steps.benchmark-glslang-cache.outputs.cache-primary-key }}", field(save, "key"))

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

    def test_validation_checkouts_match_the_original_event_source_contract(self) -> None:
        validation = WORKFLOWS[0].read_text()
        self.assertIn("EVENT_SHA: " + VALIDATION_SOURCE, validation)
        self.assertIn('echo "checkout_ref=${EVENT_SHA}" >> "$GITHUB_OUTPUT"', validation)
        checkout_steps = [s for s in steps(WORKFLOWS[0], 6)
                          if field(s, "uses").startswith("actions/checkout@")]
        # The metadata job keeps its event checkout (including a PR merge ref).
        # Its consumers qualify the PR head/event SHA directly, as before.
        self.assertEqual("", field(checkout_steps[0], "ref"))
        self.assertEqual([VALIDATION_SOURCE] * 13, [field(s, "ref") for s in checkout_steps[1:]])

    def test_release_and_interactive_checkouts_cannot_select_another_source(self) -> None:
        for path, count in ((WORKFLOWS[1], 5), (WORKFLOWS[2], 1)):
            checkout_steps = [s for s in steps(path, 6)
                              if field(s, "uses").startswith("actions/checkout@")]
            identities = [checkout_identity(s, allow_qualified_tools=path == WORKFLOWS[1]) for s in checkout_steps]
            self.assertEqual(count, identities.count("product"), path)
            self.assertCountEqual(QUALIFIED_TOOL_CHECKOUTS if path == WORKFLOWS[1] else [],
                                  [identity for identity in identities if identity != "product"], path)

    def test_external_checkout_guard_rejects_dynamic_unapproved_mutable_or_relocated_tools(self) -> None:
        release_steps = steps(WORKFLOWS[1], 6)
        tool = next(s for s in release_steps if field(s, "repository") == "KhronosGroup/glslang")
        self.assertEqual("KhronosGroup/glslang", checkout_identity(tool, allow_qualified_tools=True))
        with self.assertRaisesRegex(AssertionError, "Unapproved external"):
            checkout_identity(tool)
        for repository in ("${{ inputs.repository }}", "unapproved/glslang", "KhronosGroup/Vulkan-Headers"):
            with self.subTest(repository=repository), self.assertRaises(AssertionError):
                checkout_identity(tool.replace("repository: KhronosGroup/glslang", "repository: " + repository), True)
        original_ref = QUALIFIED_TOOL_CHECKOUTS["KhronosGroup/glslang"][0]
        for ref in ("main", "16.6.0", original_ref[:8], "f" * 40, "${{ inputs.ref }}"):
            with self.subTest(ref=ref), self.assertRaisesRegex(AssertionError, "approved immutable"):
                checkout_identity(tool.replace(original_ref, ref), True)
        for path in (".", "../tool", "${{ inputs.path }}", ".hardware/vulkan-headers"):
            with self.subTest(path=path), self.assertRaisesRegex(AssertionError, "isolated approved"):
                checkout_identity(tool.replace("path: .hardware/glslang", "path: " + path), True)
        with self.assertRaisesRegex(AssertionError, "retain credentials"):
            checkout_identity(tool.replace("persist-credentials: false", "persist-credentials: true"), True)
        with self.assertRaisesRegex(AssertionError, "Duplicate checkout identity"):
            checkout_identity(tool + "\n          repository: ${{ inputs.repository }}", True)

    def test_product_checkout_cannot_borrow_an_external_tool_exemption(self) -> None:
        product = "      - uses: actions/checkout@pinned\n        with:\n          ref: " + EVENT_SOURCE
        self.assertEqual("product", checkout_identity(product, allow_qualified_tools=True))
        for source in ("${{ inputs.ref }}", "main", "f" * 40):
            with self.subTest(source=source), self.assertRaisesRegex(AssertionError, "workflow event source"):
                checkout_identity(product.replace(EVENT_SOURCE, source), allow_qualified_tools=True)

    def test_manual_alias_verification_precedes_build_actions(self) -> None:
        release_steps = steps(WORKFLOWS[1], 6)
        source_guard = next(s for s in release_steps if field(s, "id") == "source")
        self.assertEqual("${{ steps.route.outputs.release_requested == 'true' }}", field(source_guard, "if"))
        self.assertEqual("${{ steps.route.outputs.checkout_ref }}", field(source_guard, "REQUESTED_SOURCE"))
        self.assertEqual(EVENT_SOURCE, field(source_guard, "WORKFLOW_SOURCE"))
        self.assertIn('verify_workflow_source.py --ref="$REQUESTED_SOURCE" --workflow-sha="$WORKFLOW_SOURCE"', source_guard)
        self.assertNotIn("continue-on-error", source_guard)
        route = job(WORKFLOWS[1], "route")
        self.assertLess(route.index("Resolve release request"), route.index("verify_workflow_source.py"))
        self.assertLess(route.index("verify_workflow_source.py"), route.index("Summarize release gate"))
        build = job(WORKFLOWS[1], "build-prerelease-apk")
        self.assertIn("needs: route", build)
        self.assertIn("if: ${{ needs.route.result == 'success' && needs.route.outputs.release_requested == 'true' }}", build)

        interactive_steps = steps(WORKFLOWS[2], 6)
        # The event checkout is immediately followed by the alias guard.
        guard = interactive_steps[1]
        self.assertIn("verify_workflow_source.py", guard)
        self.assertEqual("${{ inputs.ref }}", field(guard, "REQUESTED_SOURCE"))
        self.assertEqual(EVENT_SOURCE, field(guard, "WORKFLOW_SOURCE"))
        self.assertNotIn("continue-on-error", guard)
        self.assertEqual("Resolve interactive build plan", field(interactive_steps[2], "name"))

    def test_exact_build_proof_and_publication_identity_remains_checked(self) -> None:
        release_steps = steps(WORKFLOWS[1], 6)
        metadata = next(s for s in release_steps if field(s, "id") == "git_meta")
        self.assertEqual(EVENT_SOURCE, field(metadata, "WORKFLOW_SOURCE"))
        self.assertIn('head_sha="$(git rev-parse HEAD)"', metadata)
        self.assertIn('if [[ "$head_sha" != "$WORKFLOW_SOURCE" ]]', metadata)
        self.assertLess(metadata.index("exit 1"), metadata.index('echo "head_sha=$head_sha"'))
        for name, guard_name in (
            ("isa-prerelease-proof", "Require proof source to equal packaged commit"),
            ("publish-prerelease-apk", "Require publication source to equal packaged commit"),
        ):
            body = job(WORKFLOWS[1], name)
            guard = next(s for s in release_steps if field(s, "name") == guard_name)
            self.assertEqual("${{ needs.build-prerelease-apk.outputs.head_sha }}", field(guard, "BUILT_SOURCE"))
            self.assertEqual(EVENT_SOURCE, field(guard, "WORKFLOW_SOURCE"))
            self.assertIn('"$BUILT_SOURCE" != "$WORKFLOW_SOURCE" || "$(git rev-parse HEAD)" != "$BUILT_SOURCE"', guard)
            self.assertIn("exit 1", guard)
            self.assertNotIn("continue-on-error", guard)
            self.assertLess(body.index(guard_name), body.index("./.github/actions/") if "./.github/actions/" in body
                            else body.index("Resolve desktop proof from the identical source"))
        publish = job(WORKFLOWS[1], "publish-prerelease-apk")
        self.assertIn("needs.build-prerelease-apk.result == 'success'", publish)
        self.assertIn("needs.isa-prerelease-proof.result == 'success'", publish)
        self.assertIn("RELEASE_SOURCE: ${{ needs.build-prerelease-apk.outputs.head_sha }}", publish)

    def test_rejected_route_still_summarizes_from_event_source(self) -> None:
        summary = job(WORKFLOWS[1], "summary")
        self.assertIn("if: always()", summary)
        self.assertIn("ref: " + EVENT_SOURCE, summary)
        self.assertNotIn("ref: ${{ needs.", summary)
        self.assertIn("--job route=${{ needs.route.result }}", summary)
        self.assertIn('CHECKOUT_REF: ${{ needs.build-prerelease-apk.outputs.head_sha || needs.route.outputs.checkout_ref || github.sha }}', summary)
        self.assertIn('--checkout-ref="$CHECKOUT_REF"', summary)
        self.assertNotIn('--checkout-ref "${{', summary)
        route = job(WORKFLOWS[1], "route")
        self.assertIn('"${SOURCE_OUTCOME}" == "success"', route)
        self.assertIn('decision="reject"', route)

    def test_source_aliases_remain_data_in_bundle_provenance_and_summaries(self) -> None:
        # A same-commit alias is not shell or Python source. Keep it in env,
        # even after source identity matches, and let the reporting tools encode it.
        for path in WORKFLOWS[1:]:
            for step in steps(path, 6):
                run = step.partition("        run:")[2]
                self.assertNotRegex(run, r"\$\{\{[^}]*\b(?:checkout_ref|inputs\.ref|github\.ref_name)\b", (path, field(step, "name")))
        interactive = steps(WORKFLOWS[2], 6)
        bundle = next(s for s in interactive if field(s, "id") == "bundle")
        self.assertEqual("${{ steps.plan.outputs.checkout_ref }}", field(bundle, "CHECKOUT_REF"))
        self.assertIn('--checkout-ref="$CHECKOUT_REF"', bundle)
        for name in ("Generate build provenance", "Generate release provenance"):
            provenance = next(s for s in steps(WORKFLOWS[1], 6) if field(s, "name") == name)
            self.assertTrue(field(provenance, "CHECKOUT_REF"))
            self.assertIn('"checkout_ref": os.environ["CHECKOUT_REF"]', provenance)


if __name__ == "__main__":
    unittest.main()
