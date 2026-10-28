#!/usr/bin/env python3
"""Contract checks for the fixed native help-dismissal capture."""
import base64
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest
import xml.etree.ElementTree as ET

import capture_interactive_baseline as capture

PACKAGE = "app.internal"
HELP = f'<node package="{PACKAGE}" enabled="true" content-desc="Camera navigation help" bounds="[100,100][900,500]">'
BUTTON = f'<node package="{PACKAGE}" enabled="true" content-desc="Got it" bounds="[700,340][860,460]"/>'
STAGE = (f'<node package="{PACKAGE}" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">'
         f'<node package="{PACKAGE}" class="android.view.View" NAF="true" enabled="true" clickable="true" '
         'bounds="[0,0][1080,2400]"/></node>')
BASELINE = f'<hierarchy><node package="{PACKAGE}" bounds="[0,0][1080,2400]">{STAGE}{HELP}{BUTTON}</node></node></hierarchy>'
AFTER = f'<hierarchy><node package="{PACKAGE}" bounds="[0,0][1080,2400]"/></hierarchy>'


class CaptureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.original_cwd = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.original_cwd)
        capture.HIERARCHIES.mkdir(parents=True)
        capture.BRIDGE.mkdir(parents=True)
        self.before_xml = capture.HIERARCHIES / "codex-android-observe.xml"
        self.after_xml = capture.HIERARCHIES / "codex-click-after.xml"
        self.before_xml.write_text(BASELINE)
        self.after_xml.write_text(AFTER)
        build = capture.SESSION / "qualified-prerelease/qualified-prerelease-intake.json"
        build.parent.mkdir(parents=True)
        build.write_text(json.dumps({"artifact_kind": "qualified-prerelease", "package_name": PACKAGE}))
        env = {"INTERACTIVE_APP_PACKAGE": PACKAGE, "INTERACTIVE_WORKFLOW_SHA": "a" * 40,
               "INTERACTIVE_CHECKOUT_SHA": "b" * 40, "INTERACTIVE_MCP_ADAPTER_REVISION": capture.CAPTURE_PROVIDER_SHA,
               "INTERACTIVE_TOOLKIT_SHA": "d" * 40}
        self.environment = patch.dict(os.environ, env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.before = self.receipt(False)
        self.after = self.receipt(True)
        self.write_receipts()
        self.png_check = patch.object(capture, "has_decodable_native_png", return_value=True)
        self.png_check.start()
        self.addCleanup(self.png_check.stop)
        self.dimensions = patch.object(capture, "png_dimensions", return_value=(1080, 2400))
        self.dimensions.start()
        self.addCleanup(self.dimensions.stop)

    def receipt(self, step):
        outcome = {"status": "succeeded"}
        if step:
            outcome.update({"actionTypes": ["click"], "postconditionSatisfied": None})
        return {"success": True, "metadata": {"android": {"outcome": outcome}},
                "contentItems": [{"type": "inputImage", "imageUrl": "data:image/png;base64," + base64.b64encode(b"png").decode()}]}

    def write_receipts(self):
        (capture.BRIDGE / "baseline-response.json").write_text(json.dumps(self.before))
        (capture.BRIDGE / "help-response.json").write_text(json.dumps(self.after))

    def test_fixed_transition_publishes_original_evidence(self):
        capture.prepare()
        request = json.loads((capture.BRIDGE / "help-request.json").read_text())
        action = request["arguments"]["actions"][0]
        self.assertEqual((action["x"], action["y"]), (780, 400))
        self.assertEqual(set(action), {"type", "x", "y"})
        self.assertEqual(action["type"], "click")
        capture.finish()
        output = Path("dist/interactive-capture")
        self.assertEqual((output / "baseline.png").read_bytes(), b"png")
        self.assertEqual((output / "after-help.png").read_bytes(), b"png")
        self.assertEqual((output / "baseline.xml").read_text(), BASELINE)
        self.assertEqual((output / "after-help.xml").read_text(), AFTER)
        self.assertEqual(json.loads((output / "help-response.json").read_text()), self.after)
        status = json.loads((output / "status.json").read_text())
        self.assertEqual((status["stability"], status["visual_acceptance"]), ("unknown", "pending"))
        self.assertEqual(status["transport_attempts"], "unknown")
        self.assertTrue(status["provider_may_fallback"])

    def assert_no_action(self, xml):
        self.before_xml.write_text(xml)
        with self.assertRaises(ValueError):
            capture.prepare()
        self.assertFalse((capture.BRIDGE / "help-request.json").exists())
        self.assertFalse(Path("dist/interactive-capture").exists())

    def test_missing_or_ambiguous_controls(self):
        self.assert_no_action(BASELINE.replace(BUTTON, ""))
        self.assert_no_action(BASELINE.replace(BUTTON, BUTTON + BUTTON))
        self.assert_no_action(BASELINE.replace(HELP, HELP + HELP).replace('</node></node></hierarchy>', '</node></node></node></hierarchy>'))

    def test_wrong_package_disabled_and_bad_bounds(self):
        self.assert_no_action(BASELINE.replace('package="app.internal" enabled="true" content-desc="Got it"', 'package="other" enabled="true" content-desc="Got it"'))
        self.assert_no_action(BASELINE.replace('enabled="true" content-desc="Got it"', 'enabled="false" content-desc="Got it"'))
        self.assert_no_action(BASELINE.replace('[700,340][860,460]', '[700,340][700,460]'))
        self.assert_no_action(BASELINE.replace('[700,340][860,460]', '[1000,340][1200,460]'))
        self.assert_no_action(BASELINE.replace('[700,340][860,460]', '[50,50][200,200]'))
        overlapping = f'<node package="{PACKAGE}" clickable="true" text="Other control" bounds="[700,340][860,460]"/>'
        self.assert_no_action(BASELINE.replace(HELP, overlapping + HELP))
        # A different actionable child of the help overlay is not the target.
        self.assert_no_action(BASELINE.replace(BUTTON, BUTTON + overlapping))
        # A full-screen leaf from another package or in front of help is not the stage.
        self.assert_no_action(BASELINE.replace(STAGE, STAGE.replace(f'package="{PACKAGE}" class="android.view.View"', 'package="other" class="android.view.View"')))
        self.assert_no_action(BASELINE.replace(STAGE, "").replace(BUTTON + '</node>', BUTTON + '</node>' + STAGE))
        self.assert_no_action(BASELINE.replace(STAGE, STAGE + STAGE))

    def test_wrong_provider_and_failed_outcome(self):
        with patch.dict(os.environ, {"INTERACTIVE_MCP_ADAPTER_REVISION": "c" * 40}):
            with self.assertRaises(ValueError):
                capture.prepare()
        self.before["metadata"]["android"]["outcome"]["status"] = "observe_degraded"
        self.write_receipts()
        with self.assertRaises(ValueError):
            capture.prepare()
        self.assertFalse((capture.BRIDGE / "help-request.json").exists())

    def test_failed_postcondition_or_help_remaining_publishes_nothing(self):
        capture.prepare()
        self.after["metadata"]["android"]["outcome"]["status"] = "postcondition_failed"
        self.write_receipts()
        with self.assertRaises(ValueError):
            capture.finish()
        self.after["metadata"]["android"]["outcome"]["status"] = "succeeded"
        self.write_receipts()
        self.after_xml.write_text(BASELINE)
        with self.assertRaises(ValueError):
            capture.finish()
        self.after_xml.write_text(f'<hierarchy><node package="{PACKAGE}" text="Got it"/></hierarchy>')
        with self.assertRaises(ValueError):
            capture.finish()
        self.after_xml.write_text('<hierarchy><node package="other"/></hierarchy>')
        with self.assertRaises(ValueError):
            capture.finish()
        self.assertFalse(Path("dist/interactive-capture").exists())

    def test_malformed_or_empty_hierarchy(self):
        self.assert_no_action('<hierarchy/>')
        with self.assertRaises(ET.ParseError):
            self.assert_no_action('<hierarchy>')


if __name__ == "__main__":
    unittest.main()
