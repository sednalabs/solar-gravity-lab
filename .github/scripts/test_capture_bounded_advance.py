#!/usr/bin/env python3
"""Hosted guards for the private bounded-advance capture."""
import os
import unittest
from unittest.mock import patch
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

    def test_ineffective_collapse_is_not_accepted(self):
        with self.assertRaises(ValueError):
            capture.postcondition('collapse', tree(node('Less'), node('Home'), node('Frame'), node('Help')))


if __name__ == '__main__':
    unittest.main()
