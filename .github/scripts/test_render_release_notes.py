from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import render_release_notes as notes
from generate_release_changelog import EMPTY_TREE


SCRIPT = Path(__file__).with_name("render_release_notes.py").resolve()
HEAD = "a" * 40


def inventory(commits: list[dict] | None = None, baseline: str = "v0.1.0") -> dict:
    rows = commits or []
    return {
        "version": "0.2.0-alpha.1",
        "baseline_tag": baseline,
        "head": HEAD,
        "commit_count": len(rows),
        "subsystem_summary": {},
        "commits": rows,
    }


def commit(index: int, subject: str, path: str = "engine/runtime/src/lib.rs") -> dict:
    sha = f"{index:040x}"
    return {
        "sha": sha,
        "short_sha": sha[-12:],
        "subject": subject,
        "paths": [path],
        "changed_files_count": 1,
    }


class ReleaseNotesTests(unittest.TestCase):
    def test_empty_inventory_keeps_existing_format_and_fallbacks(self) -> None:
        self.assertEqual(notes.build_markdown(inventory(), "owner/repo"), f"""## Overview
This release does not contain enough subsystem data to generate a focus summary.

## Highlights
- No substantive highlights could be inferred from the git inventory.

## Product and runtime changes
- No product/runtime changes were inferred from this release inventory.

## Developer platform and CI changes
- No CI or build-automation changes were inferred from this release inventory.

## Release range
- Baseline: `v0.1.0`
- Head: [`{HEAD[:12]}`](https://github.com/owner/repo/commit/{HEAD})
- Commits in range: `0`

## Full inventory
The release assets include the authoritative `changelog-*.json` and `changelog-*.md` inventory files for the raw commit and path-level detail.
""")

    def test_normal_subjects_keep_normalization_deduplication_and_links(self) -> None:
        rows = [commit(1, "fix(jni): repair api bridge"), commit(2, "fix(jni): repair api bridge")]
        rendered = notes.build_markdown(inventory(rows), "owner/repo")
        self.assertEqual(rendered.count("JNI: repair API bridge"), 1)
        self.assertIn(f"https://github.com/owner/repo/commit/{rows[0]['sha']}", rendered)
        self.assertNotIn("…", rendered)

    def test_breaking_subjects_keep_their_normalized_scope(self) -> None:
        payload = inventory([commit(1, "fix(runtime): remove obsolete checkpoint format")])
        rendered = notes.build_markdown(payload, "owner/repo")
        self.assertIn(
            "## Breaking or migration-sensitive changes\n- Runtime: remove obsolete checkpoint format\n",
            rendered,
        )

    def test_subject_utf8_boundary_and_shortening(self) -> None:
        at_limit = "🔭" * (notes.MAX_SUBJECT_BYTES // 4)
        self.assertEqual(notes.shorten_subject(at_limit), at_limit)
        shortened = notes.shorten_subject(at_limit + "x")
        self.assertLessEqual(len(shortened.encode("utf-8")), notes.MAX_SUBJECT_BYTES)
        self.assertTrue(shortened.endswith("…"))
        self.assertNotIn("�", shortened)

    def test_giant_unicode_and_ascii_subjects_keep_summary_and_full_range(self) -> None:
        rows = [commit(index, f"fix: change {index}") for index in range(667)]
        rows[0] = commit(0, "fix!: remove " + "x" * 200_000)
        rows[1] = commit(1, "ci: migrate " + "🌕地球" * 30_000, ".github/workflows/build.yml")
        rows[2] = commit(2, "docs: update " + "é" * 200_000, "docs/lab.md")
        payload = inventory(rows, EMPTY_TREE)
        original = json.dumps(payload, ensure_ascii=False)
        rendered = notes.build_markdown(payload, "owner/repo")
        self.assertLessEqual(len(rendered.encode("utf-8")), notes.MAX_RELEASE_BODY_BYTES)
        self.assertIn(f"- Baseline: `{EMPTY_TREE}`", rendered)
        self.assertIn("- Commits in range: `667`", rendered)
        self.assertIn("## Breaking or migration-sensitive changes", rendered)
        self.assertIn("## Documentation", rendered)
        self.assertIn("## Full inventory", rendered)
        self.assertIn("…", rendered)
        self.assertNotIn("�", rendered)
        self.assertEqual(json.dumps(payload, ensure_ascii=False), original)

    def test_range_is_not_rebased_to_another_published_version(self) -> None:
        for baseline in (EMPTY_TREE, "v0.1.1-alpha.8-on-another-history"):
            with self.subTest(baseline=baseline):
                payload = inventory([commit(1, "fix: resume laboratory")], baseline)
                rendered = notes.build_markdown(payload, "owner/repo")
                self.assertIn(f"- Baseline: `{baseline}`", rendered)
                self.assertIn(f"/commit/{HEAD}", rendered)
                self.assertIn("- Commits in range: `1`", rendered)

    def test_total_body_boundary_preserves_footer_and_complete_lines(self) -> None:
        footer = "## Release range\n- Baseline: `original`\n\n## Full inventory\nAttached.\n"
        body = "x" * (notes.MAX_RELEASE_BODY_BYTES - len(footer.encode("utf-8")))
        self.assertEqual(notes.bound_release_body(body, footer), body + footer)
        for oversized in (body + "x", "## Overview\n" + "🌕" * 100_000 + "\n"):
            with self.subTest(unicode="🌕" in oversized):
                rendered = notes.bound_release_body(oversized, footer)
                self.assertLessEqual(len(rendered.encode("utf-8")), notes.MAX_RELEASE_BODY_BYTES)
                self.assertTrue(rendered.endswith(footer))
                self.assertIn("detail was omitted", rendered)
                self.assertNotIn("�", rendered)

    def test_oversized_range_metadata_falls_back_without_inventing_a_range(self) -> None:
        payload = inventory(baseline="🌕" * 100_000)
        rendered = notes.build_markdown(payload, "owner/repo")
        self.assertLessEqual(len(rendered.encode("utf-8")), notes.MAX_RELEASE_BODY_BYTES)
        self.assertIn("Range metadata exceeds", rendered)
        self.assertIn("exact baseline, head, and commit count", rendered)
        self.assertIn("## Full inventory", rendered)
        self.assertNotIn("- Baseline:", rendered)

    def test_cli_keeps_large_authoritative_assets_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inventory_path = root / "changelog-0.2.0-alpha.1.json"
            full_markdown_path = root / "changelog-0.2.0-alpha.1.md"
            payload = inventory([commit(1, "fix: " + "🌕" * 100_000)], EMPTY_TREE)
            inventory_bytes = (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")
            full_markdown_bytes = ("# Complete inventory\n" + "full path detail\n" * 20_000).encode("utf-8")
            inventory_path.write_bytes(inventory_bytes)
            full_markdown_path.write_bytes(full_markdown_bytes)
            result = subprocess.run([
                sys.executable, str(SCRIPT), "--inventory", inventory_path.name,
                "--repo", "owner/repo", "--output", "release-notes-0.2.0-alpha.1.md",
            ], cwd=root, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            rendered = (root / "release-notes-0.2.0-alpha.1.md").read_bytes()
            self.assertLessEqual(len(rendered), notes.MAX_RELEASE_BODY_BYTES)
            self.assertIn(b"## Full inventory", rendered)
            self.assertEqual(inventory_path.read_bytes(), inventory_bytes)
            self.assertEqual(full_markdown_path.read_bytes(), full_markdown_bytes)


if __name__ == "__main__":
    unittest.main()
