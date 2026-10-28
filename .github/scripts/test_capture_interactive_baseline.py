#!/usr/bin/env python3
"""Contract checks for the fixed native stage inspection."""
import base64
import json
import os
import shutil
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
CONTROLS = (f'<node package="{PACKAGE}" text="Earth" bounds="[200,100][300,150]"/>'
    f'<node package="{PACKAGE}" enabled="true" content-desc="Frame" bounds="[400,2100][500,2200]"/>'
    f'<node package="{PACKAGE}" enabled="true" content-desc="Home" bounds="[200,2100][300,2200]"/>'
    f'<node package="{PACKAGE}" enabled="true" content-desc="Camera scale Wide; open camera controls" bounds="[600,2100][700,2200]"/>')
VIEW = f'<hierarchy><node package="{PACKAGE}" bounds="[0,0][1080,2400]">{STAGE}{CONTROLS}</node></hierarchy>'
LESS = f'<node package="{PACKAGE}" enabled="true" content-desc="Less" bounds="[800,2100][900,2200]"/>'
SEARCH = f'<node package="{PACKAGE}" enabled="true" content-desc="Search" bounds="[100,1900][200,2000]"/>'
EXPANDED = VIEW.replace(CONTROLS, CONTROLS + LESS + SEARCH)
SEARCH_VIEW = VIEW.replace(CONTROLS, f'<node package="{PACKAGE}" class="android.widget.EditText" focused="true" bounds="[100,100][900,200]"/>')


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
        env = {"INTERACTIVE_STOCK_CAPTURE_DIAGNOSTIC": "foreground_checked",
               "INTERACTIVE_APP_PACKAGE": PACKAGE, "INTERACTIVE_WORKFLOW_SHA": "a" * 40,
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

    def test_launcher_wait_is_single_exact_observed_system_action(self):
        launcher = ('<hierarchy><node package="android" resource-id="android:id/parentPanel" bounds="[70,1025][1010,1447]">'
                    '<node package="android" resource-id="android:id/alertTitle" text="Pixel Launcher isn&amp;apos;t responding" enabled="true" bounds="[133,1072][947,1135]"/>'
                    '<node package="android" class="android.widget.Button" resource-id="android:id/aerr_wait" text="Wait" enabled="true" clickable="true" bounds="[70,1300][1010,1426]"/>'
                    '</node></hierarchy>').replace("&amp;apos;", "&apos;")
        self.baseline_xml.write_text(launcher)
        capture.launcher_initial()
        (capture.BRIDGE / "launcher-fresh.json").write_text(json.dumps(self.receipt(False)))
        capture.launcher_decide()
        request = json.loads((capture.LAUNCHER_DIAGNOSTIC / "action-request.json").read_text())
        self.assertEqual(request["arguments"]["actions"], [{"type": "click", "x": 540, "y": 1363}])
        self.assertEqual((capture.LAUNCHER_DIAGNOSTIC / "fresh/hierarchy.xml").read_text(), launcher)
        self.assertEqual((capture.LAUNCHER_DIAGNOSTIC / "initial/screen.png").read_bytes(), b"png")

    def test_launcher_other_anr_never_prepares_action(self):
        other = ('<hierarchy><node package="android" resource-id="android:id/alertTitle" '
                 'text="SolarLab isn&amp;apos;t responding" enabled="true" bounds="[0,0][100,100]"/></hierarchy>').replace("&amp;apos;", "&apos;")
        capture.launcher_initial()
        self.baseline_xml.write_text(other)
        (capture.BRIDGE / "launcher-fresh.json").write_text(json.dumps(self.receipt(False)))
        with self.assertRaises(ValueError):
            capture.launcher_decide()
        self.assertFalse((capture.BRIDGE / "launcher-action-request.json").exists())
        self.assertEqual((capture.LAUNCHER_DIAGNOSTIC / "fresh/hierarchy.xml").read_text(), other)

    def test_launcher_app_owned_fresh_view_skips_action(self):
        capture.launcher_initial()
        self.baseline_xml.write_text(VIEW)
        (capture.BRIDGE / "launcher-fresh.json").write_text(json.dumps(self.receipt(False)))
        capture.launcher_decide()
        status = json.loads((capture.LAUNCHER_DIAGNOSTIC / "status.json").read_text())
        self.assertEqual(status["decision"], "skip_app_visible")
        self.assertFalse((capture.BRIDGE / "launcher-action-request.json").exists())

    def test_wrong_package_retains_only_quarantined_baseline_and_stops(self):
        foreign = '<hierarchy><node package="stock.system" bounds="[0,0][1080,2400]"/></hierarchy>'
        self.baseline_xml.write_text(foreign)
        with self.assertRaises(ValueError):
            capture.capture_baseline()
        with patch.dict(os.environ, {"INTERACTIVE_CAPTURE_FAILURE_STAGE": "baseline_validate"}):
            capture.mark_failed()
        quarantine = capture.OUTPUT / "quarantined-baseline"
        self.assertEqual((quarantine / "screen.png").read_bytes(), b"png")
        self.assertEqual((quarantine / "hierarchy.xml").read_text(), foreign)
        self.assertEqual(json.loads((quarantine / "observation.json").read_text()), self.receipt(False))
        metadata = json.loads((quarantine / "metadata.json").read_text())
        self.assertEqual(metadata["package_validation"], "mismatch")
        self.assertFalse(metadata["app_owned_proof"])
        self.assertEqual(metadata["package_node_counts"], {"stock.system": 1})
        self.assertFalse((capture.OUTPUT / "baseline").exists())
        self.assertFalse((capture.BRIDGE / "help-request.json").exists())
        self.assertFalse((capture.BRIDGE / "search-request.json").exists())
        status = capture.current_status()
        self.assertEqual(status["completed"], [])
        self.assertEqual(status["failure_stage"], "baseline_package")

    def test_fixed_sequence_retains_each_validated_observation(self):
        capture.capture_baseline()
        self.assertFalse((capture.OUTPUT / "quarantined-baseline").exists())
        for phase in ("help", "frame", "home"):
            self.step(phase)
        self.step("controls", EXPANDED)
        (capture.BRIDGE / "search-before.json").write_text(json.dumps(self.receipt(False)))
        self.step("search", SEARCH_VIEW)
        status = json.loads((capture.OUTPUT / "status.json").read_text())
        self.assertEqual(status["completed"], ["baseline", "help", "frame", "home", "controls", "search"])
        self.assertEqual((status["status"], status["stability"], status["visual_acceptance"]), ("partial", "unknown", "pending"))
        self.assertTrue(status["provider_may_fallback"])
        self.assertEqual(json.loads((capture.OUTPUT / "search/focus-observation.json").read_text()),
                         {"edit_text_count": 1, "focused_values": ["true"], "keyboard_visual_observation": "pending"})
        self.assertEqual((capture.OUTPUT / "search-before/hierarchy.xml").read_text(), EXPANDED)
        for phase in ("help", "frame", "home", "controls", "search"):
            step = capture.OUTPUT / phase
            self.assertEqual((step / "immediate/screen.png").read_bytes(), b"png")
            self.assertEqual((step / "settled/screen.png").read_bytes(), b"png")
            self.assertTrue((step / "action-request.json").exists())
            self.assertTrue((step / "immediate/action-response.json").exists())
            self.assertTrue((step / "settled/hierarchy.xml").exists())

    def test_type_requires_focused_empty_field_and_uses_raw_text_action(self):
        capture.capture_baseline()
        for phase in ("help", "frame", "home"):
            self.step(phase)
        self.step("controls", EXPANDED)
        (capture.BRIDGE / "search-before.json").write_text(json.dumps(self.receipt(False)))
        self.step("search", SEARCH_VIEW)
        capture.write_status(["baseline", "help", "frame", "home", "controls", "search", "close-search", "reopen-search"], "partial")
        self.baseline_xml.write_text(SEARCH_VIEW.replace('</node></hierarchy>',
            f'<node package="{PACKAGE}" text="Find a runtime body"/></node></hierarchy>'))
        (capture.BRIDGE / "type-earth-before.json").write_text(json.dumps(self.receipt(False)))
        capture.phase_prepare("type-earth")
        request = json.loads((capture.BRIDGE / "type-earth-request.json").read_text())
        self.assertEqual(request["arguments"]["actions"], [{"type": "type", "text": "earth"}])
        self.assertTrue((capture.OUTPUT / "type-earth-before/screen.png").exists())

    def test_type_refuses_unfocused_field_and_preserves_pre_action_observation(self):
        capture.capture_baseline()
        for phase in ("help", "frame", "home"):
            self.step(phase)
        self.step("controls", EXPANDED)
        (capture.BRIDGE / "search-before.json").write_text(json.dumps(self.receipt(False)))
        self.step("search", SEARCH_VIEW)
        capture.write_status(["baseline", "help", "frame", "home", "controls", "search", "close-search", "reopen-search"], "partial")
        self.baseline_xml.write_text(SEARCH_VIEW.replace('focused="true"', 'focused="false"'))
        (capture.BRIDGE / "type-earth-before.json").write_text(json.dumps(self.receipt(False)))
        with self.assertRaises(ValueError):
            capture.phase_prepare("type-earth")
        self.assertTrue((capture.OUTPUT / "type-earth-before/screen.png").exists())
        self.assertFalse((capture.BRIDGE / "type-earth-request.json").exists())

    def test_close_requires_unique_enabled_clickable_target_in_dialog(self):
        field = f'<node package="{PACKAGE}" class="android.widget.EditText" focused="true"/>'
        title = f'<node package="{PACKAGE}" text="Find a runtime body"/>'
        close = (f'<node package="{PACKAGE}" enabled="true" clickable="true" bounds="[700,600][900,700]">'
                 f'<node package="{PACKAGE}" text="Close"/></node>')
        xml = f'<hierarchy><node package="{PACKAGE}" bounds="[100,100][1000,800]">{field}{title}{close}</node></hierarchy>'
        self.assertEqual(capture.close_search_center(ET.fromstring(xml), PACKAGE, (1080, 2400)), (800, 650))
        for invalid in (xml.replace(close, close + close), xml.replace('clickable="true"', 'clickable="false"'),
                        xml.replace('[100,100][1000,800]', '[100,100][1000,650]')):
            with self.assertRaises(ValueError):
                capture.close_search_center(ET.fromstring(invalid), PACKAGE, (1080, 2400))

    def test_earth_row_focus_requires_unique_visible_clickable_action(self):
        field = f'<node package="{PACKAGE}" class="android.widget.EditText" text="earth"/>'
        button = (f'<node package="{PACKAGE}" clickable="true" enabled="true" bounds="[650,600][850,700]">'
                  f'<node package="{PACKAGE}" text="Focus"/></node>')
        row = (f'<node package="{PACKAGE}" bounds="[100,580][900,720]">'
               f'<node package="{PACKAGE}" text="Earth"/><node package="{PACKAGE}" text="earth · 1 AU"/>{button}</node>')
        scroll = f'<node package="{PACKAGE}" class="android.widget.ScrollView" bounds="[80,500][950,800]">{row}</node>'
        xml = f'<hierarchy><node package="{PACKAGE}">{field}{scroll}</node></hierarchy>'
        self.assertEqual(capture.earth_focus_center(ET.fromstring(xml), PACKAGE, (1080, 2400)), (750, 650))
        for invalid in (xml.replace(row, row + row), xml.replace('clickable="true"', 'clickable="false"'),
                        xml.replace('[80,500][950,800]', '[80,500][950,650]'),
                        xml.replace(button, button + f'<node package="android" clickable="true" bounds="[700,600][800,700]"/>')):
            with self.assertRaises(ValueError):
                capture.earth_focus_center(ET.fromstring(invalid), PACKAGE, (1080, 2400))

    def test_failed_close_postcondition_retains_evidence_and_in_progress(self):
        capture.capture_baseline()
        phase = "close-search"
        expected = ["baseline"] + list(capture.PHASES[:capture.PHASES.index(phase)])
        (capture.OUTPUT / phase).mkdir()
        capture.write_status(expected, "partial", in_progress=phase, action_effect="observation_captured")
        self.baseline_xml.write_text(SEARCH_VIEW.replace('</node></hierarchy>',
            f'<node package="{PACKAGE}" text="Find a runtime body"/></node></hierarchy>'))
        (capture.BRIDGE / f"{phase}-settled.json").write_text(json.dumps(self.receipt(False)))
        with self.assertRaises(ValueError):
            capture.phase_record(phase)
        status = capture.current_status()
        self.assertEqual(status["completed"], expected)
        self.assertEqual((status["in_progress"], status["action_effect"]), (phase, "observation_captured"))
        self.assertEqual((capture.OUTPUT / phase / "settled/screen.png").read_bytes(), b"png")
        self.assertTrue((capture.OUTPUT / phase / "settled/hierarchy.xml").exists())

    def test_ineffective_collapse_does_not_promote_and_keeps_evidence(self):
        capture.capture_baseline()
        phase = "collapse"
        expected = ["baseline"] + list(capture.PHASES[:capture.PHASES.index(phase)])
        (capture.OUTPUT / phase).mkdir()
        capture.write_status(expected, "partial", in_progress=phase, action_effect="observation_captured")
        self.baseline_xml.write_text(EXPANDED)
        (capture.BRIDGE / f"{phase}-settled.json").write_text(json.dumps(self.receipt(False)))
        with self.assertRaises(ValueError):
            capture.phase_record(phase)
        status = capture.current_status()
        self.assertEqual(status["completed"], expected)
        self.assertEqual(status["in_progress"], phase)
        self.assertEqual((capture.OUTPUT / phase / "settled/hierarchy.xml").read_text(), EXPANDED)
        self.assertEqual((capture.OUTPUT / phase / "settled/screen.png").read_bytes(), b"png")

    def test_compact_controls_promote_collapse_after_observation(self):
        capture.capture_baseline()
        phase = "collapse"
        expected = ["baseline"] + list(capture.PHASES[:capture.PHASES.index(phase)])
        (capture.OUTPUT / phase).mkdir()
        capture.write_status(expected, "partial", in_progress=phase, action_effect="observation_captured")
        descriptions = ("Home", "Frame", "Help", "Camera scale Close; open camera controls")
        nodes = "".join(f'<node package="{PACKAGE}" enabled="true" content-desc="{d}"/>' for d in descriptions)
        self.baseline_xml.write_text(f'<hierarchy><node package="{PACKAGE}">{nodes}</node></hierarchy>')
        (capture.BRIDGE / f"{phase}-settled.json").write_text(json.dumps(self.receipt(False)))
        capture.phase_record(phase)
        status = capture.current_status()
        self.assertEqual(status["completed"], expected + [phase])
        self.assertEqual((status["status"], status["visual_acceptance"]), ("captured", "pending"))

    def test_missing_search_target_preserves_fresh_observation(self):
        capture.capture_baseline()
        for phase in ("help", "frame", "home"):
            self.step(phase)
        self.step("controls", EXPANDED)
        (capture.BRIDGE / "search-before.json").write_text(json.dumps(self.receipt(False)))
        self.baseline_xml.write_text(EXPANDED.replace(SEARCH, ""))
        with self.assertRaises(ValueError):
            capture.phase_prepare("search")
        self.assertTrue((capture.OUTPUT / "search-before/screen.png").exists())
        self.assertFalse((capture.BRIDGE / "search-request.json").exists())

    def test_missing_search_focus_keeps_original_captures(self):
        capture.capture_baseline()
        for phase in ("help", "frame", "home"):
            self.step(phase)
        self.step("controls", EXPANDED)
        (capture.BRIDGE / "search-before.json").write_text(json.dumps(self.receipt(False)))
        self.step("search", VIEW)
        focus = json.loads((capture.OUTPUT / "search/focus-observation.json").read_text())
        self.assertEqual(focus["edit_text_count"], 0)
        self.assertTrue((capture.OUTPUT / "search/immediate/screen.png").exists())
        self.assertTrue((capture.OUTPUT / "search/settled/screen.png").exists())

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
            capture.capture_baseline()
            with self.assertRaises(ValueError):
                capture.phase_prepare("help")
            capture.mark_failed()
            self.assertEqual((capture.OUTPUT / "baseline/hierarchy.xml").read_text(), invalid)
            self.assertEqual(capture.current_status()["completed"], ["baseline"])
            self.assertFalse((capture.BRIDGE / "help-request.json").exists())
            shutil.rmtree(capture.OUTPUT)

    def test_invalid_baseline_is_not_promoted_and_stage_is_safe(self):
        cases = (("wrong_package", BASELINE.replace(PACKAGE, "other"), "baseline_package", ValueError),
                 ("invalid_xml", "<hierarchy>", "baseline_hierarchy", ET.ParseError))
        for _, xml, stage, error in cases:
            self.baseline_xml.write_text(xml)
            with self.assertRaises(error):
                capture.capture_baseline()
            with patch.dict(os.environ, {"INTERACTIVE_CAPTURE_FAILURE_STAGE": "baseline_validate"}):
                capture.mark_failed()
            status = capture.current_status()
            self.assertEqual((status["status"], status["failure_stage"]), ("failed", stage))
            self.assertFalse((capture.OUTPUT / "baseline").exists())
            capture.mark_failed()
            self.assertEqual(capture.current_status()["status"], "failed")
            self.assertEqual(capture.current_status()["failure_stage"], stage)
            shutil.rmtree(capture.OUTPUT)
        with patch.object(capture, "has_decodable_native_png", return_value=False):
            with self.assertRaises(ValueError):
                capture.capture_baseline()
        with patch.dict(os.environ, {"INTERACTIVE_CAPTURE_FAILURE_STAGE": "baseline_validate"}):
            capture.mark_failed()
        self.assertEqual(capture.current_status()["failure_stage"], "baseline_image")
        self.assertFalse((capture.OUTPUT / "baseline").exists())

    def test_unrecognized_stage_is_not_copied_to_status(self):
        with patch.dict(os.environ, {"INTERACTIVE_CAPTURE_FAILURE_STAGE": "sensitive\nvalue"}):
            capture.mark_failed()
        self.assertEqual(capture.current_status()["failure_stage"], "unknown")
        self.assertNotIn("sensitive", (capture.OUTPUT / "status.json").read_text())

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
