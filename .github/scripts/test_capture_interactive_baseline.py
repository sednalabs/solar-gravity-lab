#!/usr/bin/env python3
"""Contract checks for the fixed native stage inspection."""
import base64
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest

import capture_interactive_baseline as capture

PACKAGE = "app.internal"
HELP = f'<node package="{PACKAGE}" enabled="true" content-desc="Camera navigation help" bounds="[100,100][900,500]">'
BUTTON = f'<node package="{PACKAGE}" enabled="true" content-desc="Got it" bounds="[700,340][860,460]"/>'
STAGE = (f'<node package="{PACKAGE}" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">'
         f'<node package="{PACKAGE}" class="android.view.View" NAF="true" enabled="true" clickable="true" '
         'bounds="[0,0][1080,2400]"/></node>')
BASELINE = f'<hierarchy><node package="{PACKAGE}" bounds="[0,0][1080,2400]">{STAGE}{HELP}{BUTTON}</node></node></hierarchy>'
CONTROLS = (f'<node package="{PACKAGE}" text="Earth" bounds="[200,100][300,150]"/>'
    f'<node package="{PACKAGE}" enabled="true" content-desc="Frame" bounds="[400,2100][500,2200]"/>'
    f'<node package="{PACKAGE}" enabled="true" content-desc="Home" bounds="[200,2100][300,2200]"/>'
    f'<node package="{PACKAGE}" enabled="true" content-desc="Camera scale Wide; open camera controls" bounds="[600,2100][700,2200]"/>')
VIEW = f'<hierarchy><node package="{PACKAGE}" bounds="[0,0][1080,2400]">{STAGE}{CONTROLS}</node></hierarchy>'
LESS = f'<node package="{PACKAGE}" enabled="true" content-desc="Less" bounds="[800,2100][900,2200]"/>'
EXPANDED = VIEW.replace(CONTROLS, CONTROLS + LESS)


class CaptureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.original_cwd = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.original_cwd)
        capture.HIERARCHIES.mkdir(parents=True)
        capture.BRIDGE.mkdir(parents=True)
        self.baseline_xml = capture.HIERARCHIES / "codex-android-observe.xml"
        self.action_xml = capture.HIERARCHIES / "codex-click-after.xml"
        self.baseline_xml.write_text(BASELINE)
        build = capture.SESSION / "qualified-prerelease/qualified-prerelease-intake.json"
        build.parent.mkdir(parents=True)
        build.write_text(json.dumps({"artifact_kind": "qualified-prerelease", "package_name": PACKAGE}))
        env = {"INTERACTIVE_APP_PACKAGE": PACKAGE, "INTERACTIVE_WORKFLOW_SHA": "a" * 40,
               "INTERACTIVE_CHECKOUT_SHA": "b" * 40, "INTERACTIVE_MCP_ADAPTER_REVISION": capture.CAPTURE_PROVIDER_SHA,
               "INTERACTIVE_TOOLKIT_SHA": "d" * 40}
        self.environment = patch.dict(os.environ, env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.png_check = patch.object(capture, "has_decodable_native_png", return_value=True)
        self.png_check.start()
        self.addCleanup(self.png_check.stop)
        self.dimensions = patch.object(capture, "png_dimensions", return_value=(1080, 2400))
        self.dimensions.start()
        self.addCleanup(self.dimensions.stop)
        (capture.BRIDGE / "baseline-response.json").write_text(json.dumps(self.receipt(False)))

    def receipt(self, step):
        outcome = {"status": "succeeded"}
        if step:
            outcome["actionTypes"] = ["click"]
        return {"success": True, "metadata": {"android": {"outcome": outcome}},
                "contentItems": [{"type": "inputImage", "imageUrl": "data:image/png;base64," + base64.b64encode(b"png").decode()}]}

    def step(self, phase, settled=VIEW):
        capture.phase_prepare(phase)
        request = json.loads((capture.BRIDGE / f"{phase}-request.json").read_text())
        self.assertEqual(set(request["arguments"]["actions"][0]), {"type", "x", "y"})
        (capture.BRIDGE / f"{phase}-response.json").write_text(json.dumps(self.receipt(True)))
        self.action_xml.write_text(settled)
        capture.phase_snapshot(phase)
        (capture.BRIDGE / f"{phase}-settled.json").write_text(json.dumps(self.receipt(False)))
        self.baseline_xml.write_text(settled)
        capture.phase_record(phase)

    def test_fixed_sequence_retains_each_validated_observation(self):
        capture.capture_baseline()
        for phase in ("help", "frame", "home"):
            self.step(phase)
        self.step("controls", EXPANDED)
        status = json.loads((capture.OUTPUT / "status.json").read_text())
        self.assertEqual(status["completed"], ["baseline", "help", "frame", "home", "controls"])
        self.assertEqual((status["status"], status["stability"], status["visual_acceptance"]), ("captured", "unknown", "pending"))
        self.assertTrue(status["provider_may_fallback"])
        for phase in ("help", "frame", "home", "controls"):
            step = capture.OUTPUT / phase
            self.assertEqual((step / "immediate/screen.png").read_bytes(), b"png")
            self.assertEqual((step / "settled/screen.png").read_bytes(), b"png")
            self.assertTrue((step / "action-request.json").exists())
            self.assertTrue((step / "immediate/action-response.json").exists())
            self.assertTrue((step / "settled/hierarchy.xml").exists())

    def test_partial_on_missing_expanded_control_retains_observed_evidence(self):
        capture.capture_baseline()
        for phase in ("help", "frame", "home"):
            self.step(phase)
        with self.assertRaises(ValueError):
            self.step("controls")
        capture.mark_failed()
        status = json.loads((capture.OUTPUT / "status.json").read_text())
        self.assertEqual((status["status"], status["stop_reason"]), ("partial", "inspection_stopped"))
        self.assertTrue((capture.OUTPUT / "controls/settled/screen.png").exists())

    def test_ambiguous_or_wrong_control_stops_before_action(self):
        capture.capture_baseline()
        self.step("help")
        hierarchy = capture.OUTPUT / "help/settled/hierarchy.xml"
        hierarchy.write_text(VIEW.replace('content-desc="Frame"', 'content-desc="Other"'))
        with self.assertRaises(ValueError):
            capture.phase_prepare("frame")
        self.assertFalse((capture.BRIDGE / "frame-request.json").exists())
        hierarchy.write_text(VIEW.replace('content-desc="Frame"', 'content-desc="Home"'))
        with self.assertRaises(ValueError):
            capture.phase_prepare("frame")
        hierarchy.write_text(VIEW.replace('package="app.internal" enabled="true" content-desc="Frame"', 'package="other" enabled="true" content-desc="Frame"'))
        with self.assertRaises(ValueError):
            capture.phase_prepare("frame")

    def test_original_help_overlap_guard_rejects_foreign_controls(self):
        extra = f'<node package="{PACKAGE}" clickable="true" text="Other" bounds="[700,340][860,460]"/>'
        for invalid in (BASELINE.replace(BUTTON, BUTTON + extra),
                        BASELINE.replace(STAGE, STAGE.replace(f'package="{PACKAGE}" class="android.view.View"', 'package="other" class="android.view.View"')),
                        BASELINE.replace(STAGE, "").replace(BUTTON + '</node>', BUTTON + '</node>' + STAGE)):
            self.baseline_xml.write_text(invalid)
            with self.assertRaises(ValueError):
                capture.capture_baseline()
            self.assertFalse(capture.OUTPUT.exists())

    def test_failed_action_retains_only_prior_validated_evidence(self):
        capture.capture_baseline()
        capture.phase_prepare("help")
        (capture.BRIDGE / "help-response.json").write_text(json.dumps({"success": False}))
        self.action_xml.write_text(VIEW)
        with self.assertRaises(ValueError):
            capture.phase_snapshot("help")
        capture.mark_failed()
        self.assertTrue((capture.OUTPUT / "baseline/screen.png").exists())
        self.assertTrue((capture.OUTPUT / "help/action-request.json").exists())
        self.assertFalse((capture.OUTPUT / "help/immediate").exists())
        status = json.loads((capture.OUTPUT / "status.json").read_text())
        self.assertEqual((status["stop_reason"], status["action_effect"]), ("inspection_stopped", "unknown"))

    def prepare_immediate(self):
        capture.capture_baseline()
        capture.phase_prepare("help")
        response = json.dumps(self.receipt(True))
        (capture.BRIDGE / "help-response.json").write_text(response)
        self.action_xml.write_text(VIEW)
        capture.phase_snapshot("help")
        return response

    def assert_checkpoint(self, response):
        status = json.loads((capture.OUTPUT / "status.json").read_text())
        self.assertEqual((status["status"], status["in_progress"], status["action_effect"]),
                         ("partial", "help", "observation_captured"))
        self.assertEqual((capture.OUTPUT / "help/immediate/action-response.json").read_text(), response)
        self.assertEqual((capture.OUTPUT / "help/immediate/screen.png").read_bytes(), b"png")
        self.assertEqual((capture.OUTPUT / "help/immediate/hierarchy.xml").read_text(), VIEW)
        self.assertFalse((capture.OUTPUT / "help/settled").exists())

    def test_valid_immediate_survives_stop_before_followup(self):
        response = self.prepare_immediate()
        # Cleanup after a foreground or delayed-call failure preserves custody.
        capture.mark_failed()
        self.assert_checkpoint(response)
        with self.assertRaises(ValueError):
            capture.phase_prepare("help")

    def test_invalid_followup_does_not_promote_immediate(self):
        response = self.prepare_immediate()
        (capture.BRIDGE / "help-settled.json").write_text(json.dumps({"success": False}))
        self.baseline_xml.write_text(VIEW)
        with self.assertRaises(ValueError):
            capture.phase_record("help")
        capture.mark_failed()
        self.assert_checkpoint(response)


if __name__ == "__main__":
    unittest.main()
