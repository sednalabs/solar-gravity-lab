#!/usr/bin/env python3
"""Contract checks for curated capture publication."""
import base64
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest

import capture_interactive_baseline as capture


class CaptureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.original_cwd = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.original_cwd)
        hierarchy = Path("dist/interactive-session/android-computer-use-mcp-artifacts/uiautomator/codex-android-observe.xml")
        hierarchy.parent.mkdir(parents=True)
        hierarchy.write_text('<hierarchy><node text="screen"/></hierarchy>')
        self.build = Path("dist/interactive-session/qualified-prerelease/qualified-prerelease-intake.json")
        self.build.parent.mkdir(parents=True)
        self.build.write_text(json.dumps({"artifact_kind": "qualified-prerelease", "package_name": "app.internal"}))
        env = {"INTERACTIVE_MCP_WORKSPACE_DIR": "provider", "INTERACTIVE_BUILD_MANIFEST": str(self.build),
               "INTERACTIVE_APP_PACKAGE": "app.internal", "INTERACTIVE_WORKFLOW_SHA": "a" * 40,
               "INTERACTIVE_CHECKOUT_SHA": "b" * 40, "INTERACTIVE_MCP_ADAPTER_REVISION": capture.CAPTURE_PROVIDER_SHA,
               "INTERACTIVE_TOOLKIT_SHA": "d" * 40}
        self.environment = patch.dict(os.environ, env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.receipt = {"success": True, "metadata": {"android": {"outcome": {"status": "succeeded"}}},
                        "contentItems": [{"type": "inputImage", "imageUrl": "data:image/png;base64," + base64.b64encode(b"png").decode()}]}

    def run_capture(self):
        response = Path("dist/interactive-session/codex-bridge/baseline-response.json")
        response.parent.mkdir(parents=True, exist_ok=True)
        response.write_text(json.dumps(self.receipt))
        with patch.object(capture, "has_decodable_native_png", return_value=True):
            capture.main()

    def test_publishes_original_image_hierarchy_and_receipt(self):
        self.run_capture()
        output = Path("dist/interactive-capture")
        self.assertEqual((output / "baseline.png").read_bytes(), b"png")
        self.assertEqual(json.loads((output / "observation.json").read_text()), self.receipt)
        self.assertEqual((output / "baseline.xml").read_text(), '<hierarchy><node text="screen"/></hierarchy>')
        status = json.loads((output / "status.json").read_text())
        self.assertEqual(status["visual_acceptance"], "pending")
        self.assertEqual(status["stability"], "unknown")
        self.assertEqual(status["adapter_outcome"], "succeeded")
        self.assertEqual(json.loads((output / "provenance.json").read_text())["stability"], "unknown")

    def test_other_provider_is_rejected(self):
        with patch.dict(os.environ, {"INTERACTIVE_MCP_ADAPTER_REVISION": "c" * 40}):
            with self.assertRaises(ValueError):
                self.run_capture()
        self.assertFalse(Path("dist/interactive-capture").exists())

    def test_non_success_adapter_outcome_publishes_nothing(self):
        self.receipt["metadata"]["android"]["outcome"]["status"] = "observe_degraded"
        with self.assertRaises(ValueError):
            self.run_capture()
        self.assertFalse(Path("dist/interactive-capture").exists())

    def test_invalid_hierarchy_publishes_nothing(self):
        path = Path("dist/interactive-session/android-computer-use-mcp-artifacts/uiautomator/codex-android-observe.xml")
        path.write_text('<hierarchy/>')
        with self.assertRaises(ValueError):
            self.run_capture()
        self.assertFalse(Path("dist/interactive-capture").exists())


if __name__ == "__main__":
    unittest.main()
