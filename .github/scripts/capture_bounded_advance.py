#!/usr/bin/env python3
"""Private fixed capture-only bounded-advance observation; visual review remains pending."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import capture_interactive_baseline as base

PACKAGE = os.environ['INTERACTIVE_APP_PACKAGE']
SERIAL = os.environ['ANDROID_SERIAL']
WORKSPACE = Path(os.environ['INTERACTIVE_MCP_WORKSPACE_DIR'])
CONFIG = base.SESSION / 'openai-loop/config.json'
CLI = WORKSPACE / 'adapters/codex/bin/codex-android-tools.mjs'
PHASES = ('pause', 'preset-1d', 'preset-7d', 'preset-30d', 'large', 'cancel',
          'preset-1s', 'preset-10s', 'preset-1min', 'preset-10min', 'small', 'collapse')
TARGETS = {'pause': 'Pause', 'preset-1d': '6 h', 'preset-7d': '1 d', 'preset-30d': '7 d',
           'large': 'Step', 'cancel': 'Cancel advance', 'preset-1s': '30 d', 'preset-10s': '1 s',
           'preset-1min': '10 s', 'preset-10min': '1 min', 'small': 'Step', 'collapse': 'Less'}
NEXT = {'pause': 'Start', 'preset-1d': '1 d', 'preset-7d': '7 d', 'preset-30d': '30 d',
        'preset-1s': '1 s', 'preset-10s': '10 s', 'preset-1min': '1 min', 'preset-10min': '10 min'}


def provider(request, response):
    try:
        result = subprocess.run(['node', str(CLI), '--config', str(CONFIG), 'call'],
            input=(json.dumps(request) + '\n').encode(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            check=False, timeout=90)
    except subprocess.TimeoutExpired as error:
        response.write_bytes(error.stdout or b'')
        raise
    response.write_bytes(result.stdout)
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, result.args)


def foreground():
    output = subprocess.run(['adb', '-s', SERIAL, 'shell', 'dumpsys', 'activity', 'activities'],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True, timeout=20).stdout.decode(errors='replace')
    if not any(PACKAGE + '/' in line for line in output.splitlines()
               if 'mResumedActivity' in line or 'topResumedActivity' in line):
        raise ValueError('expected foreground activity not observed')


def observation(path, *, step=False, step_type='click'):
    foreground()
    name = 'codex-' + (step_type + '-after.xml' if step else 'android-observe.xml')
    response, png, xml, root = base.read_observation(path, base.HIERARCHIES / name, is_step=step, step_type=step_type)
    base.valid_continuation_view(root, PACKAGE)
    return response, png, xml, root


def save(path, data):
    response, png, xml, root = data
    path.mkdir(parents=True, exist_ok=False)
    (path / 'receipt.json').write_bytes(response)
    (path / 'screen.png').write_bytes(png)
    (path / 'hierarchy.xml').write_bytes(xml)
    if len([n for n in root.iter('node') if n.get('package') == PACKAGE and n.get('text') == 'Earth']) != 1:
        raise ValueError('Earth selection is not uniquely observed')
    return root


def observe(path):
    request = {'tool': 'android_observe', 'arguments': {'scope': 'screen_and_ui'}}
    response = base.BRIDGE / 'bounded-observe.json'
    provider(request, response)
    data = observation(response)
    save(path, data)
    return data


def matching(root, description):
    return [n for n in root.iter('node') if n.get('package') == PACKAGE and n.get('enabled') == 'true'
            and n.get('content-desc') == description]


def fully_visible(root, node, viewport):
    target = base.bounds(node)
    if not base.contains((0, 0, *viewport), target):
        return False
    parents = {child: parent for parent in root.iter() for child in parent}
    parent = parents.get(node)
    while parent is not None:
        if parent.get('scrollable') == 'true' and not base.contains(base.bounds(parent), target):
            return False
        parent = parents.get(parent)
    return True


def find_target(root, label, viewport):
    nodes = [n for n in matching(root, label) if fully_visible(root, n, viewport)]
    if len(nodes) > 1:
        raise ValueError('ambiguous visible target')
    if not nodes:
        return None
    return base.target_center(root, PACKAGE, viewport, nodes[0])


def progress(root):
    result = []
    for n in root.iter('node'):
        for value in (n.get('text', ''), n.get('content-desc', '')):
            match = re.search(r'Advancing\s+(\d+)/(\d+)', value)
            if match:
                result.append(tuple(map(int, match.groups())))
    return any(0 < done <= total and total > 64 for done, total in result)


def scroll_labels(root):
    views = [n for n in root.iter('node') if n.get('package') == PACKAGE and
             n.get('class') == 'android.widget.ScrollView' and n.get('enabled') == 'true' and
             n.get('scrollable') == 'true']
    if len(views) != 1:
        raise ValueError('unique cockpit scroll view unavailable')
    labels = {}
    for node in views[0].iter('node'):
        label = node.get('content-desc', '')
        if label:
            labels.setdefault(label, []).append(base.bounds(node))
    return {label: rects[0] for label, rects in labels.items() if len(rects) == 1}


def scroll(root, viewport, direction, directory):
    candidates = [n for n in root.iter('node') if n.get('package') == PACKAGE and
                  n.get('class') == 'android.widget.ScrollView' and n.get('enabled') == 'true' and
                  n.get('scrollable') == 'true' and base.contains((0, 0, *viewport), base.bounds(n))]
    if len(candidates) != 1:
        raise ValueError('unique visible cockpit scroll view unavailable')
    left, top, right, bottom = base.bounds(candidates[0])
    height = bottom - top
    if height < 240 or right - left < 100:
        raise ValueError('cockpit scroll view too small')
    if direction not in (-1, 1):
        raise ValueError('invalid cockpit scroll direction')
    x = (left + right) // 2
    start_y = top + (3 * height // 4 if direction > 0 else height // 4)
    end_y = top + (height // 4 if direction > 0 else 3 * height // 4)
    command = ['adb', '-s', SERIAL, 'shell', 'input', 'swipe', str(x), str(start_y),
               str(x), str(end_y), '500']
    directory.mkdir(parents=True, exist_ok=False)
    (directory / 'request.json').write_text(json.dumps({
        'tool': 'adb', 'serial': SERIAL, 'arguments': command[3:], 'timeout_seconds': 20,
        'physical_effect': 'unknown'}) + '\n')
    foreground()
    try:
        result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                check=False, timeout=20)
    except subprocess.TimeoutExpired:
        (directory / 'adb-receipt.json').write_text(json.dumps({
            'tool': 'adb', 'serial': SERIAL, 'arguments': command[3:], 'timeout_seconds': 20,
            'exit_code': None, 'timed_out': True, 'physical_effect': 'unknown'}) + '\n')
        raise
    (directory / 'adb-receipt.json').write_text(json.dumps({
        'tool': 'adb', 'serial': SERIAL, 'arguments': command[3:], 'timeout_seconds': 20,
        'exit_code': result.returncode, 'timed_out': False, 'physical_effect': 'unknown'}) + '\n')
    if result.returncode != 0:
        raise ValueError('ADB swipe did not complete on the bound emulator')
    request = {'tool': 'android_observe', 'arguments': {'scope': 'screen_and_ui'}}
    (directory / 'observation-request.json').write_text(json.dumps(request) + '\n')
    foreground()
    response = directory / 'observation-response.json'
    provider(request, response)
    after = save(directory / 'after', observation(response))
    before_labels, after_labels = scroll_labels(root), scroll_labels(after)
    if not any(direction * (after_labels[label][1] - rect[1]) < 0
               for label, rect in before_labels.items() if label in after_labels):
        raise ValueError('cockpit movement not observed; no further swipe permitted')


def postcondition(phase, root):
    if phase in NEXT and len(matching(root, NEXT[phase])) != 1:
        raise ValueError('expected control state was not observed')
    values = [n.get('text', '') + ' ' + n.get('content-desc', '') for n in root.iter('node')]
    if phase == 'large' and (not progress(root) or len(matching(root, 'Cancel advance')) != 1):
        raise ValueError('active progress and Cancel were not observed together')
    if phase in {'cancel', 'small'}:
        label = 'Advance cancelled' if phase == 'cancel' else 'Advance complete'
        if not any(label in text for text in values) or len(matching(root, 'Start')) != 1 or matching(root, 'Cancel advance'):
            raise ValueError('terminal outcome and usable paused state not observed')
    if phase == 'collapse':
        if matching(root, 'Less') or matching(root, 'Search'):
            raise ValueError('expanded controls remain')
        for label in ('Home', 'Frame', 'Help'):
            if len(matching(root, label)) != 1:
                raise ValueError('compact control missing or ambiguous')
        scale = [n for n in root.iter('node') if n.get('package') == PACKAGE and n.get('enabled') == 'true' and
                 n.get('content-desc', '').startswith('Camera scale ') and
                 n.get('content-desc', '').endswith('; open camera controls')]
        if len(scale) != 1:
            raise ValueError('compact camera scale missing or ambiguous')


def run():
    base.require_capture_provider(os.environ['INTERACTIVE_MCP_ADAPTER_REVISION'])
    if not re.fullmatch(r'emulator-[0-9]+', SERIAL):
        raise ValueError('serial is not the bound emulator')
    status = base.current_status()
    if status.get('completed') != ['baseline', 'help', 'frame', 'home', 'controls'] or status.get('status') != 'partial':
        raise ValueError('unexpected baseline sequence')
    completed = status['completed']
    # A real numeric achieved rate must be observed before pausing; preserve each observation.
    for attempt in range(5):
        _, _, _, root = observe(base.OUTPUT / f'playback-rate-{attempt}')
        text = ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '') for n in root.iter('node'))
        if re.search(r'Requested\s+.+?\s+·\s+achieved\s+\d+(?:\.\d+)?\s+sim s/s', text):
            break
        time.sleep(1)
    else:
        raise ValueError('numeric requested and achieved playback rate unavailable')
    for phase in PHASES:
        directory = base.OUTPUT / phase
        directory.mkdir()
        # Fixed direction by the known cockpit layout. Every scroll is separately observed and recorded.
        direction = -1 if phase in {'large', 'cancel', 'small', 'collapse'} else 1
        for attempt in range(7):
            _, png, _, root = observe(directory / f'before-{attempt}')
            if phase == 'cancel' and not progress(root):
                raise ValueError('advance no longer progressing before cancel')
            target = find_target(root, TARGETS[phase], base.png_dimensions(png))
            if target is not None:
                break
            if attempt == 6:
                raise ValueError('target unavailable after bounded cockpit scrolls')
            scroll(root, base.png_dimensions(png), direction, directory / f'scroll-{attempt}')
        request = {'tool': 'android_step', 'arguments': {'actions': [{'type': 'click', 'x': target[0], 'y': target[1]}],
                    'post_observe_scope': 'screen_and_ui'}}
        (directory / 'request.json').write_text(json.dumps(request) + '\n')
        base.write_status(completed, 'partial', in_progress=phase, action_effect='unknown')
        response = base.BRIDGE / 'bounded-action.json'
        foreground()
        provider(request, response)
        save(directory / 'immediate', observation(response, step=True))
        base.write_status(completed, 'partial', in_progress=phase, action_effect='observation_captured')
        time.sleep(1)
        _, _, _, root = observe(directory / 'settled')
        postcondition(phase, root)
        completed = completed + [phase]
        base.write_status(completed, 'captured' if phase == 'collapse' else 'partial')


if __name__ == '__main__':
    try:
        run()
    except Exception:
        if (base.OUTPUT / 'status.json').exists():
            state = base.current_status()
            base.write_status(state.get('completed', []), 'partial', 'inspection_stopped',
                              state.get('in_progress'), state.get('action_effect'), 'bounded-advance')
        raise
