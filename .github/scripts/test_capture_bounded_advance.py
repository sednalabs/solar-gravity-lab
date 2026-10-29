#!/usr/bin/env python3
"""Hosted guards for the private bounded-advance capture."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

with patch.dict(os.environ, {'INTERACTIVE_APP_PACKAGE': 'example.app', 'ANDROID_SERIAL': 'emulator-5554',
                            'INTERACTIVE_MCP_WORKSPACE_DIR': '/tmp/provider'}):
    import capture_bounded_advance as capture


def tree(*nodes):
    return ET.fromstring('<hierarchy>' + ''.join(nodes) + '</hierarchy>')


def node(label, bounds='[0,0][100,100]'):
    return (f'<node package="example.app" enabled="true" content-desc="{label}" '
            f'bounds="{bounds}"/>')


class AdvanceGuards(unittest.TestCase):
    def test_preparing_is_not_progress_and_cancel_alone_is_not_enough(self):
        root = tree(node('Preparing 0/0'), node('Cancel advance'))
        self.assertFalse(capture.progress(root))
        with self.assertRaises(ValueError):
            capture.postcondition('large', root)

    def test_large_requires_real_progress_and_cancel(self):
        root = tree(node('Advancing 64/2592000'), node('Cancel advance'))
        capture.postcondition('large', root)
        with self.assertRaises(ValueError):
            capture.postcondition('large', tree(node('Advancing 64/2592000')))

    def test_cancel_requires_outcome_and_paused_state(self):
        with self.assertRaises(ValueError):
            capture.postcondition('cancel', tree(node('Advance cancelled')))
        capture.postcondition('cancel', tree(node('Advance cancelled'), node('Start')))
        with self.assertRaises(ValueError):
            capture.postcondition('cancel', tree(node('Advance cancelled'), node('Start'), node('Cancel advance')))

    def test_clipped_target_is_not_visible(self):
        root = tree('<node package="example.app" scrollable="true" bounds="[0,100][100,300]">' +
                    node('Step', '[0,250][100,350]') + '</node>')
        target = list(root.iter('node'))[1]
        self.assertFalse(capture.fully_visible(root, target, (400, 400)))

    def test_empty_clipped_labels_are_not_motion_witnesses_or_targets(self):
        root = tree('<node package="example.app" class="android.widget.ScrollView" enabled="true" '
                    'scrollable="true" bounds="[0,1680][1080,2400]">'
                    '<node package="example.app" class="android.view.View" content-desc="Scenario" '
                    'enabled="true" clickable="false" bounds="[0,0][0,0]"/>' +
                    node('Less', '[380,1730][611,1856]') +
                    node('Offscreen', '[0,2500][100,2600]') + '</node>')
        self.assertEqual(capture.scroll_labels(root, (1080, 2400)), {'Less': (380, 1730, 611, 1856)})
        with patch.object(capture.base, 'target_center', return_value=(495, 1793)) as center:
            self.assertEqual(capture.find_target(root, 'Less', (1080, 2400)), (495, 1793))
            self.assertIsNone(capture.find_target(root, 'Scenario', (1080, 2400)))
            center.assert_called_once()

    def test_duplicate_visible_target_still_fails(self):
        root = tree(node('Less', '[0,100][100,200]'), node('Less', '[100,100][200,200]'))
        with self.assertRaisesRegex(ValueError, 'ambiguous visible target'):
            capture.find_target(root, 'Less', (400, 400))

    def test_malformed_target_bounds_still_fail_closed(self):
        root = tree(node('Less', 'invalid'))
        with self.assertRaisesRegex(ValueError, 'invalid control bounds'):
            capture.find_target(root, 'Less', (400, 400))

    def test_empty_other_actionable_node_does_not_obscure_visible_target(self):
        root = tree('<node class="android.widget.FrameLayout" package="example.app" bounds="[0,0][400,400]">'
                    '<node class="android.widget.FrameLayout" package="example.app" bounds="[0,0][400,400]">'
                    '<node class="android.view.View" package="example.app" NAF="true" enabled="true" '
                    'clickable="true" bounds="[0,0][400,400]"/></node>'
                    '<node class="android.view.View" package="example.app" enabled="true" '
                    'clickable="true" content-desc="Less" bounds="[100,100][200,200]"/>'
                    '<node class="android.view.View" package="example.app" enabled="true" '
                    'clickable="true" content-desc="Scenario" bounds="[0,0][0,0]"/>'
                    '</node>')
        self.assertEqual(capture.find_target(root, 'Less', (400, 400)), (150, 150))

    def test_scroll_retains_actual_adb_receipt_and_distinct_provider_observation(self):
        root = tree('<node package="example.app" class="android.widget.ScrollView" enabled="true" '
                    'scrollable="true" bounds="[0,1680][1080,2400]">' +
                    node('Less', '[0,1900][100,2000]') + '</node>')
        observed_root = tree('<node package="example.app" text="Earth"/>',
                             '<node package="example.app" class="android.widget.ScrollView" enabled="true" '
                             'scrollable="true" bounds="[0,1680][1080,2400]">' +
                             node('Less', '[0,1700][100,1800]') + '</node>')
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / 'scroll-0'
            with patch.object(capture, 'foreground'), \
                 patch.object(capture.subprocess, 'run', return_value=Mock(returncode=0)) as run, \
                 patch.object(capture, 'provider', side_effect=lambda request, path: path.write_bytes(b'provider response')) as provider, \
                 patch.object(capture, 'observation', return_value=(b'provider response', b'png', b'<hierarchy/>', observed_root)) as observation:
                capture.scroll(root, (1080, 2400), 1, directory)
            run.assert_called_once_with(['adb', '-s', 'emulator-5554', 'shell', 'input', 'swipe',
                                         '540', '2220', '540', '1860', '500'],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                        check=False, timeout=20)
            receipt = json.loads((directory / 'adb-receipt.json').read_text())
            self.assertEqual(receipt['exit_code'], 0)
            self.assertEqual(receipt['physical_effect'], 'unknown')
            self.assertEqual(json.loads((directory / 'observation-request.json').read_text())['tool'], 'android_observe')
            provider.assert_called_once()
            observation.assert_called_once_with(directory / 'observation-response.json')
            self.assertEqual((directory / 'observation-response.json').read_bytes(), b'provider response')
            self.assertEqual((directory / 'after/receipt.json').read_bytes(), b'provider response')
            self.assertEqual((directory / 'after/screen.png').read_bytes(), b'png')

    def test_unchanged_scroll_retains_observation_and_stops(self):
        root = tree('<node package="example.app" class="android.widget.ScrollView" enabled="true" '
                    'scrollable="true" bounds="[0,1680][1080,2400]">' +
                    node('Less', '[0,1900][100,2000]') + '</node>')
        after = tree('<node package="example.app" text="Earth"/>',
                     '<node package="example.app" class="android.widget.ScrollView" enabled="true" '
                     'scrollable="true" bounds="[0,1680][1080,2400]">' +
                     node('Less', '[0,1900][100,2000]') + '</node>')
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / 'scroll-0'
            with patch.object(capture, 'foreground'), \
                 patch.object(capture.subprocess, 'run', return_value=Mock(returncode=0)) as run, \
                 patch.object(capture, 'provider', side_effect=lambda request, path: path.write_bytes(b'response')), \
                 patch.object(capture, 'observation', return_value=(b'response', b'png', b'<hierarchy/>', after)):
                with self.assertRaisesRegex(ValueError, 'movement not observed'):
                    capture.scroll(root, (1080, 2400), 1, directory)
            self.assertEqual(run.call_count, 1)
            self.assertEqual((directory / 'after/screen.png').read_bytes(), b'png')
            self.assertEqual((directory / 'observation-response.json').read_bytes(), b'response')

    def test_scroll_timeout_records_unknown_effect_and_never_observes_or_retries(self):
        root = tree('<node package="example.app" class="android.widget.ScrollView" enabled="true" '
                    'scrollable="true" bounds="[0,1680][1080,2400]"/>')
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / 'scroll-0'
            with patch.object(capture, 'foreground'), \
                 patch.object(capture.subprocess, 'run', side_effect=subprocess.TimeoutExpired('adb', 20)) as run, \
                 patch.object(capture, 'provider') as provider:
                with self.assertRaises(subprocess.TimeoutExpired):
                    capture.scroll(root, (1080, 2400), 1, directory)
            self.assertEqual(run.call_count, 1)
            provider.assert_not_called()
            receipt = json.loads((directory / 'adb-receipt.json').read_text())
            self.assertIsNone(receipt['exit_code'])
            self.assertTrue(receipt['timed_out'])
            self.assertEqual(receipt['physical_effect'], 'unknown')

    def test_ineffective_collapse_is_not_accepted(self):
        with self.assertRaises(ValueError):
            capture.postcondition('collapse', tree(node('Less'), node('Home'), node('Frame'), node('Help')))


if __name__ == '__main__':
    unittest.main()
