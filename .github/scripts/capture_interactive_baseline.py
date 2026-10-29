#!/usr/bin/env python3
"""Capture a baseline and one verified native help-dismissal transition."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from validate_interactive_png import has_decodable_native_png

CAPTURE_PROVIDER_SHA = "0e5a3dc692f39022fa84" + "d88c927e4a59801673e4"
SESSION = Path("dist/interactive-session")
BRIDGE = SESSION / "codex-bridge"
HIERARCHIES = SESSION / "android-computer-use-mcp-artifacts/uiautomator"
HELP = "Camera navigation help"
DISMISS = "Got it"


def require_capture_provider(value: str) -> None:
    if value != CAPTURE_PROVIDER_SHA:
        raise ValueError("capture provider revision does not match the inspected contract")


def read_observation(response_path: Path, hierarchy_path: Path, *, is_step: bool = False, step_type: str = "click", mark=None):
    if mark:
        mark("baseline_receipt")
    if response_path.stat().st_size > 20_000_000 or hierarchy_path.stat().st_size > 2_000_000:
        raise ValueError("observation exceeds capture bound")
    response = response_path.read_bytes()
    receipt = json.loads(response)
    outcome = receipt.get("metadata", {}).get("android", {}).get("outcome", {})
    items = receipt.get("contentItems")
    if receipt.get("success") is not True or outcome.get("status") != "succeeded":
        raise ValueError("adapter observation outcome check failed")
    if mark:
        mark("baseline_image")
    if not has_decodable_native_png(items):
        raise ValueError("adapter observation outcome or native image check failed")
    if is_step and outcome.get("actionTypes") != [step_type]:
        raise ValueError("unexpected adapter action")
    image_items = [item for item in items if isinstance(item, dict) and item.get("type") == "inputImage"]
    if len(image_items) != 1:
        raise ValueError("expected one native image")
    png = base64.b64decode(image_items[0]["imageUrl"].partition(",")[2], validate=True)
    if mark:
        mark("baseline_hierarchy")
    xml = hierarchy_path.read_bytes()
    root = ET.fromstring(xml)
    if root.tag != "hierarchy" or not list(root.iter("node")):
        raise ValueError("native hierarchy missing or empty")
    return response, png, xml, root


def png_dimensions(png: bytes) -> tuple[int, int]:
    dimensions = subprocess.check_output(
        ["identify", "-regard-warnings", "-format", "%w %h", "png:-"], input=png, timeout=30,
    ).decode("ascii").split()
    if len(dimensions) != 2:
        raise ValueError("image dimensions unavailable")
    return int(dimensions[0]), int(dimensions[1])


def optional_bounds(node: ET.Element) -> tuple[int, int, int, int] | None:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise ValueError("invalid control bounds")
    left, top, right, bottom = map(int, match.groups())
    if right < left or bottom < top:
        raise ValueError("invalid control bounds")
    if right == left or bottom == top:
        return None
    return left, top, right, bottom


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    rect = optional_bounds(node)
    if rect is None:
        raise ValueError("empty control bounds")
    return rect


def contains(outer, inner) -> bool:
    return outer[0] <= inner[0] and outer[1] <= inner[1] and inner[2] <= outer[2] and inner[3] <= outer[3]


def help_center(root: ET.Element, package: str, viewport: tuple[int, int]) -> tuple[int, int]:
    nodes = list(root.iter("node"))
    matches = lambda description: [n for n in nodes if n.get("content-desc") == description]
    help_nodes, dismiss_nodes = matches(HELP), matches(DISMISS)
    if len(help_nodes) != 1 or len(dismiss_nodes) != 1:
        raise ValueError("help or dismiss control is missing or ambiguous")
    help_node, dismiss_node = help_nodes[0], dismiss_nodes[0]
    if any(n.get("package") != package or n.get("enabled") != "true" for n in (help_node, dismiss_node)):
        raise ValueError("help controls have an unexpected package or state")
    if dismiss_node not in list(help_node.iter("node")):
        raise ValueError("dismiss control is outside help hierarchy")
    help_bounds, dismiss_bounds = bounds(help_node), bounds(dismiss_node)
    width, height = viewport
    if width <= 0 or height <= 0 or not contains((0, 0, width, height), help_bounds) or not contains(help_bounds, dismiss_bounds):
        raise ValueError("help or dismiss control is outside the viewport")
    return target_center(root, package, viewport, dismiss_node)


def target_center(root: ET.Element, package: str, viewport: tuple[int, int], target: ET.Element) -> tuple[int, int]:
    nodes = list(root.iter("node"))
    width, height = viewport
    target_bounds = bounds(target)
    if target.get("package") != package or target.get("enabled") != "true" or width <= 0 or height <= 0 or not contains((0, 0, width, height), target_bounds):
        raise ValueError("invalid target state or bounds")
    x, y = (target_bounds[0] + target_bounds[2]) // 2, (target_bounds[1] + target_bounds[3]) // 2
    parents = {child: parent for parent in root.iter() for child in parent}

    def ancestry(node):
        chain = [node]
        while chain[-1] in parents:
            chain.append(parents[chain[-1]])
        return chain

    target_ancestry = set(ancestry(target))
    target_chain = ancestry(target)

    def is_observed_backdrop(node, node_bounds):
        parent = parents.get(node)
        if not (
            node_bounds == (0, 0, width, height)
            and node.get("package") == package
            and node.get("class") == "android.view.View"
            and node.get("NAF") == "true"
            and node.get("enabled") == "true"
            and node.get("clickable") == "true"
            and not node.get("text") and not node.get("content-desc") and not node.get("resource-id")
            and len(node) == 0
            and parent is not None
            and parent.get("class") == "android.widget.FrameLayout"
            and parent.get("package") == package
            and bounds(parent) == (0, 0, width, height)
        ):
            return False
        stage_chain = ancestry(node)
        common = next((ancestor for ancestor in stage_chain if ancestor in target_chain), None)
        if common is None or common is node or common is target:
            return False
        stage_branch = stage_chain[stage_chain.index(common) - 1]
        target_branch = target_chain[target_chain.index(common) - 1]
        siblings = list(common)
        return siblings.index(stage_branch) < siblings.index(target_branch)

    backdrops = []
    for node in nodes:
        if node in target_ancestry:
            continue
        if node.get("clickable") != "true" and node.get("focusable") != "true":
            continue
        node_bounds = optional_bounds(node)
        if node_bounds is None:
            continue
        if not (node_bounds[0] <= x < node_bounds[2] and node_bounds[1] <= y < node_bounds[3]):
            continue
        if is_observed_backdrop(node, node_bounds):
            backdrops.append(node)
            continue
        raise ValueError("another actionable control overlaps the help target")
    if len(backdrops) != 1:
        raise ValueError("expected one matching stage backdrop behind target")
    return x, y



OUTPUT = Path("dist/interactive-capture")
PHASES = ("help", "frame", "home", "controls", "search", "close-search", "reopen-search",
          "type-earth", "focus-earth", "reopen-search-again", "type-earth-again", "focus-earth-again", "collapse")


def valid_app_view(root: ET.Element, package: str) -> None:
    nodes = list(root.iter("node"))
    if not any(node.get("package") == package for node in nodes):
        raise ValueError("hierarchy does not contain the expected package")
    if any(node.get("content-desc") in {HELP, DISMISS} or node.get("text") in {HELP, DISMISS} for node in nodes):
        raise ValueError("help remains visible after dismissal")


def valid_continuation_view(root: ET.Element, package: str) -> None:
    valid_app_view(root, package)
    if any(n.get("package") != package for n in root.iter("node")):
        raise ValueError("unexpected window in continuation hierarchy")


def write_status(completed: list[str], status: str, stop_reason: str | None = None,
                 in_progress: str | None = None, action_effect: str | None = None,
                 failure_stage: str | None = None) -> None:
    payload = {"status": status, "completed": completed, "stop_reason": stop_reason,
               "in_progress": in_progress, "action_effect": action_effect, "failure_stage": failure_stage,
               "stability": "unknown", "response_session_identity": "unknown",
               "transport_attempts": "unknown", "provider_may_fallback": True, "visual_acceptance": "pending"}
    temporary = OUTPUT / "status.tmp"
    temporary.write_text(json.dumps(payload) + "\n")
    temporary.replace(OUTPUT / "status.json")


def current_status() -> dict:
    return json.loads((OUTPUT / "status.json").read_text())


def baseline_stage(stage: str) -> None:
    (BRIDGE / "baseline-stage").write_text(stage)


# Private, stock-emulator-only diagnostic evidence; never an app-owned capture.
LAUNCHER_DIAGNOSTIC = Path("dist/interactive-launcher-recovery")


def launcher_observation(name: str, response_path: Path, hierarchy_path: Path) -> ET.Element:
    if os.environ.get("INTERACTIVE_STOCK_CAPTURE_DIAGNOSTIC") != "foreground_checked":
        raise ValueError("stock capture diagnostic context is required")
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    response, png, xml, root = read_observation(response_path, hierarchy_path)
    LAUNCHER_DIAGNOSTIC.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / name
        staging.mkdir()
        (staging / "observation.json").write_bytes(response)
        (staging / "screen.png").write_bytes(png)
        (staging / "hierarchy.xml").write_bytes(xml)
        staging.rename(LAUNCHER_DIAGNOSTIC / name)
    return root


def launcher_initial() -> None:
    launcher_observation("initial", BRIDGE / "baseline-response.json", HIERARCHIES / "codex-android-observe.xml")
    (LAUNCHER_DIAGNOSTIC / "status.json").write_text(json.dumps({
        "status": "partial", "decision": "pending", "app_owned_proof": False,
        "stability": "unknown", "response_session_identity": "unknown", "visual_acceptance": "pending",
    }) + "\n")


def launcher_decide() -> None:
    root = launcher_observation("fresh", BRIDGE / "launcher-fresh.json", HIERARCHIES / "codex-android-observe.xml")
    nodes = list(root.iter("node"))
    app = os.environ["INTERACTIVE_APP_PACKAGE"]
    titles = [n for n in nodes if n.get("resource-id") == "android:id/alertTitle"]
    if any(n.get("package") == app for n in nodes) and not titles and all(n.get("package") == app for n in nodes):
        decision = "skip_app_visible"
    else:
        title = [n for n in titles if n.get("package") == "android" and
                 n.get("text") == "Pixel Launcher isn't responding" and n.get("enabled") == "true"]
        waits = [n for n in nodes if n.get("package") == "android" and
                 n.get("resource-id") == "android:id/aerr_wait" and n.get("text") == "Wait" and
                 n.get("class") == "android.widget.Button" and n.get("enabled") == "true" and n.get("clickable") == "true"]
        panels = [n for n in nodes if n.get("package") == "android" and n.get("resource-id") == "android:id/parentPanel"]
        if len(titles) != 1 or len(title) != 1 or len(waits) != 1 or len(panels) != 1 or any(n.get("package") != "android" for n in nodes):
            raise ValueError("unexpected or ambiguous system dialog")
        panel = panels[0]
        if title[0] not in list(panel.iter("node")) or waits[0] not in list(panel.iter("node")):
            raise ValueError("launcher dialog controls do not share the observed panel")
        viewport = png_dimensions((LAUNCHER_DIAGNOSTIC / "fresh/screen.png").read_bytes())
        target = bounds(waits[0])
        if not contains((0, 0, *viewport), target) or not contains(bounds(panel), target):
            raise ValueError("launcher wait target is outside the observed panel")
        x, y = (target[0] + target[2]) // 2, (target[1] + target[3]) // 2
        for node in nodes:
            if node is waits[0] or node.get("clickable") != "true":
                continue
            b = bounds(node)
            if b[0] <= x < b[2] and b[1] <= y < b[3]:
                raise ValueError("another clickable control overlaps Wait")
        request = {"tool": "android_step", "arguments": {"actions": [{"type": "click", "x": x, "y": y}],
                   "post_observe_scope": "screen_and_ui"}}
        request_text = json.dumps(request) + "\n"
        (LAUNCHER_DIAGNOSTIC / "action-request.json").write_text(request_text)
        (BRIDGE / "launcher-action-request.json").write_text(request_text)
        decision = "wait_once"
    (LAUNCHER_DIAGNOSTIC / "status.json").write_text(json.dumps({
        "status": "partial", "decision": decision, "action_effect": "unknown" if decision == "wait_once" else "not_applicable",
        "transport_attempts": "unknown", "provider_may_fallback": True,
        "owner_submission_limit": 1 if decision == "wait_once" else 0,
        "app_owned_proof": False, "stability": "unknown", "response_session_identity": "unknown", "visual_acceptance": "pending",
    }) + "\n")
    print(decision)


def launcher_action_receipt() -> None:
    # Preserve the bounded raw receipt even if its outcome or post-action image is invalid.
    path = BRIDGE / "launcher-action-response.json"
    if path.stat().st_size > 20_000_000:
        raise ValueError("launcher response exceeds capture bound")
    (LAUNCHER_DIAGNOSTIC / "action-response.json").write_bytes(path.read_bytes())
    response, png, xml, _ = read_observation(path, HIERARCHIES / "codex-click-after.xml", is_step=True)
    (LAUNCHER_DIAGNOSTIC / "action-screen.png").write_bytes(png)
    (LAUNCHER_DIAGNOSTIC / "action-hierarchy.xml").write_bytes(xml)


def capture_baseline() -> None:
    baseline_stage("baseline_provider")
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    response, png, xml, root = read_observation(BRIDGE / "baseline-response.json", HIERARCHIES / "codex-android-observe.xml", mark=baseline_stage)
    baseline_stage("baseline_package")
    if not any(node.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"] for node in root.iter("node")):
        if os.environ.get("INTERACTIVE_STOCK_CAPTURE_DIAGNOSTIC") != "foreground_checked":
            raise ValueError("stock capture diagnostic context is required")
        baseline_stage("baseline_quarantine")
        package_counts = {}
        for node in root.iter("node"):
            package = node.get("package")
            if package:
                package_counts[package] = package_counts.get(package, 0) + 1
        OUTPUT.mkdir(parents=True, exist_ok=False)
        with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
            quarantine = Path(temporary) / "quarantined-baseline"
            quarantine.mkdir()
            (quarantine / "observation.json").write_bytes(response)
            (quarantine / "screen.png").write_bytes(png)
            (quarantine / "hierarchy.xml").write_bytes(xml)
            (quarantine / "metadata.json").write_text(json.dumps({
                "package_validation": "mismatch", "app_owned_proof": False,
                "foreground_activity_check": "passed_before_capture_validation",
                "package_node_counts": package_counts, "visual_acceptance": "pending",
                "response_session_identity": "unknown", "stability": "unknown",
            }) + "\n")
            quarantine.rename(OUTPUT / "quarantined-baseline")
        baseline_stage("baseline_package")
        raise ValueError("hierarchy does not contain the expected package")
    baseline_stage("baseline_provenance")
    build = json.loads((SESSION / "qualified-prerelease/qualified-prerelease-intake.json").read_text())
    if build.get("artifact_kind") != "qualified-prerelease" or build.get("package_name") != os.environ["INTERACTIVE_APP_PACKAGE"]:
        raise ValueError("unexpected capture build")
    provenance = {"workflow_sha": os.environ["INTERACTIVE_WORKFLOW_SHA"], "checkout_sha": os.environ["INTERACTIVE_CHECKOUT_SHA"],
        "provider_sha": os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"], "toolkit_sha": os.environ["INTERACTIVE_TOOLKIT_SHA"],
        "build": build, "foreground_package": os.environ["INTERACTIVE_APP_PACKAGE"], "response_session_identity": "unknown", "stability": "unknown",
        "transport_attempts": "unknown", "provider_may_fallback": True}
    baseline_stage("baseline_publish")
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / "capture"
        baseline = staging / "baseline"
        baseline.mkdir(parents=True)
        (baseline / "observation.json").write_bytes(response)
        (baseline / "screen.png").write_bytes(png)
        (baseline / "hierarchy.xml").write_bytes(xml)
        (staging / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        (staging / "status.json").write_text(json.dumps({"status": "partial", "completed": ["baseline"],
            "stability": "unknown", "response_session_identity": "unknown", "transport_attempts": "unknown",
            "provider_may_fallback": True, "visual_acceptance": "pending"}) + "\n")
        staging.rename(OUTPUT)


def earth_focus_center(root: ET.Element, package: str, viewport: tuple[int, int]) -> tuple[int, int]:
    nodes = list(root.iter("node"))
    if any(n.get("package") != package for n in nodes):
        raise ValueError("unexpected window in search hierarchy")
    earth = [n for n in nodes if n.get("package") == package and n.get("text") == "Earth"]
    fields = [n for n in nodes if n.get("package") == package and n.get("class") == "android.widget.EditText"]
    if len(earth) != 1 or len(fields) != 1 or fields[0].get("text", "").lower() != "earth":
        raise ValueError("filtered Earth result or query is ambiguous")
    parents = {child: parent for parent in root.iter() for child in parent}
    row = parents.get(earth[0])
    if row is None or row.get("package") != package:
        raise ValueError("Earth result row is missing")
    row_nodes = list(row.iter("node"))
    identifiers = [n for n in row_nodes if n.get("text", "").startswith("earth · ")]
    labels = [n for n in row_nodes if n.get("text") == "Focus" and n.get("package") == package]
    if len(identifiers) != 1 or len(labels) != 1:
        raise ValueError("Earth focus action is ambiguous")
    target = parents.get(labels[0])
    if target is None or target.get("package") != package or target.get("enabled") != "true" or target.get("clickable") != "true":
        raise ValueError("Earth focus action is not enabled and clickable")
    rect = bounds(target)
    if not contains((0, 0, *viewport), rect) or not contains(bounds(row), rect):
        raise ValueError("Earth focus action is clipped")
    scroll = parents.get(row)
    if scroll is None or scroll.get("class") != "android.widget.ScrollView" or not contains(bounds(scroll), rect):
        raise ValueError("Earth focus action is outside visible results")
    x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
    for node in nodes:
        if node is target or node.get("clickable") != "true":
            continue
        b = bounds(node)
        if b[0] <= x < b[2] and b[1] <= y < b[3]:
            raise ValueError("Earth focus action is obstructed")
    return x, y


def close_search_center(root: ET.Element, package: str, viewport: tuple[int, int]) -> tuple[int, int]:
    nodes = list(root.iter("node"))
    titles = [n for n in nodes if n.get("package") == package and n.get("text") == "Find a runtime body"]
    fields = [n for n in nodes if n.get("package") == package and n.get("class") == "android.widget.EditText"]
    labels = [n for n in nodes if n.get("package") == package and n.get("text") == "Close"]
    if len(titles) != 1 or len(fields) != 1 or fields[0].get("focused") != "true" or len(labels) != 1:
        raise ValueError("focused search dialog or Close control is ambiguous")
    parents = {child: parent for parent in root.iter() for child in parent}
    target = parents.get(labels[0])
    dialog = parents.get(target) if target is not None else None
    if (target is None or dialog is None or target.get("package") != package or
            target.get("enabled") != "true" or target.get("clickable") != "true"):
        raise ValueError("Close control is not enabled and clickable")
    rect = bounds(target)
    if not contains((0, 0, *viewport), rect) or not contains(bounds(dialog), rect):
        raise ValueError("Close control is clipped")
    x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
    for node in nodes:
        if node is target or node.get("clickable") != "true":
            continue
        b = bounds(node)
        if b[0] <= x < b[2] and b[1] <= y < b[3]:
            raise ValueError("Close control is obstructed")
    return x, y


def phase_prepare(phase: str) -> None:
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    status = current_status()
    expected = ["baseline"] + list(PHASES[:PHASES.index(phase)])
    if (status.get("status") != "partial" or status.get("completed") != expected
            or status.get("in_progress") is not None or status.get("stop_reason") is not None):
        raise ValueError("unexpected inspection sequence")
    if phase in {"search", "close-search", "reopen-search", "type-earth", "focus-earth",
                 "reopen-search-again", "type-earth-again", "focus-earth-again", "collapse"}:
        response, png, xml, root = read_observation(BRIDGE / f"{phase}-before.json", HIERARCHIES / "codex-android-observe.xml")
        if phase != "search":
            valid_continuation_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
        with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
            staging = Path(temporary) / "before"
            staging.mkdir()
            (staging / "observation.json").write_bytes(response)
            (staging / "screen.png").write_bytes(png)
            (staging / "hierarchy.xml").write_bytes(xml)
            staging.rename(OUTPUT / f"{phase}-before")
    else:
        previous = OUTPUT / expected[-1]
        if expected[-1] != "baseline":
            previous = previous / "settled"
        png = (previous / "screen.png").read_bytes()
        root = ET.fromstring((previous / "hierarchy.xml").read_bytes())
    package = os.environ["INTERACTIVE_APP_PACKAGE"]
    viewport = png_dimensions(png)
    if phase in {"type-earth", "type-earth-again"}:
        valid_app_view(root, package)
        if any(n.get("package") != package for n in root.iter("node")):
            raise ValueError("unexpected window in search hierarchy")
        fields = [n for n in root.iter("node") if n.get("package") == package and n.get("class") == "android.widget.EditText"]
        if len(fields) != 1 or fields[0].get("focused") != "true" or fields[0].get("text", "") != "" or fields[0].get("enabled") != "true" or fields[0].get("clickable") != "true":
            raise ValueError("search field is not uniquely focused and empty")
        matches = [n for n in root.iter("node") if all(n.get(key) == "true" for key in ("focused", "enabled", "clickable"))]
        if len(matches) != 1 or matches[0] is not fields[0]:
            raise ValueError("focused input selector is ambiguous")
        if len([n for n in root.iter("node") if n.get("package") == package and n.get("text") == "Find a runtime body"]) != 1:
            raise ValueError("search dialog is not uniquely observed")
        serial = os.environ.get("ANDROID_SERIAL", "")
        if not re.fullmatch(r"emulator-[0-9]+", serial):
            raise ValueError("type action requires a bound emulator serial")
        action = None
    elif phase in {"focus-earth", "focus-earth-again"}:
        valid_app_view(root, package)
        x, y = earth_focus_center(root, package, viewport)
        serial = os.environ.get("ANDROID_SERIAL", "")
        if not re.fullmatch(r"emulator-[0-9]+", serial):
            raise ValueError("focus action requires a bound emulator serial")
        action = None
    elif phase == "close-search":
        x, y = close_search_center(root, package, viewport)
        action = {"type": "click", "x": x, "y": y}
    elif phase == "help":
        x, y = help_center(root, package, viewport)
        action = {"type": "click", "x": x, "y": y}
    else:
        valid_app_view(root, package)
        if phase in {"frame", "reopen-search", "reopen-search-again", "collapse"}:
            earth = [n for n in root.iter("node") if n.get("text") == "Earth" and n.get("package") == package]
            if len(earth) != 1:
                raise ValueError("Earth selection is not uniquely visible")
        def selected(node):
            description = node.get("content-desc", "")
            if phase == "frame":
                return description == "Frame"
            if phase == "collapse":
                return description == "Less"
            if phase == "home":
                return description == "Home"
            if phase in {"search", "reopen-search", "reopen-search-again"}:
                return description == "Search"
            return description.startswith("Camera scale ") and description.endswith("; open camera controls")
        targets = [n for n in root.iter("node") if selected(n)]
        if len(targets) != 1:
            raise ValueError("inspection target is missing or ambiguous")
        x, y = target_center(root, package, viewport, targets[0])
        action = {"type": "click", "x": x, "y": y}
    if phase in {"type-earth", "type-earth-again"}:
        request = {"tool": "adb", "serial": serial, "arguments": ["shell", "input", "text", "earth"], "timeout_seconds": 20}
    elif phase in {"focus-earth", "focus-earth-again"}:
        request = {"tool": "adb", "serial": serial, "arguments": ["shell", "input", "tap", str(x), str(y)], "timeout_seconds": 20}
    else:
        request = {"tool": "android_step", "arguments": {"actions": [action],
            "post_observe_scope": "screen_and_ui"}}
    request_text = json.dumps(request) + "\n"
    (BRIDGE / f"{phase}-request.json").write_text(request_text)
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / "step"
        staging.mkdir()
        (staging / "action-request.json").write_text(request_text)
        staging.rename(OUTPUT / phase)
    write_status(expected, "partial", in_progress=phase, action_effect="unknown")


def phase_snapshot(phase: str) -> None:
    status = current_status()
    expected = ["baseline"] + list(PHASES[:PHASES.index(phase)])
    if status.get("status") != "partial" or status.get("completed") != expected or status.get("in_progress") != phase:
        raise ValueError("unexpected inspection sequence")
    is_direct = phase in {"type-earth", "type-earth-again", "focus-earth", "focus-earth-again"}
    if is_direct:
        receipt = json.loads((OUTPUT / phase / "adb-receipt.json").read_text())
        if receipt.get("exit_code") != 0 or receipt.get("serial") != os.environ.get("ANDROID_SERIAL"):
            raise ValueError("ADB command did not complete on the bound emulator")
        if phase in {"focus-earth", "focus-earth-again"} and (receipt.get("screencap_exit_code") != 0 or
                (OUTPUT / phase / "native-immediate-screen.png").stat().st_size == 0):
            raise ValueError("immediate native screenshot was not captured")
        response, png, xml, root = read_observation(BRIDGE / f"{phase}-response.json", HIERARCHIES / "codex-android-observe.xml")
    else:
        response, png, xml, root = read_observation(BRIDGE / f"{phase}-response.json", HIERARCHIES / "codex-click-after.xml", is_step=True)
    if phase in {"close-search", "reopen-search", "type-earth", "focus-earth", "reopen-search-again", "type-earth-again", "focus-earth-again", "collapse"}:
        valid_continuation_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
    else:
        valid_app_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / "immediate"
        staging.mkdir()
        (staging / ("observation.json" if is_direct else "action-response.json")).write_bytes(response)
        (staging / "screen.png").write_bytes(png)
        (staging / "hierarchy.xml").write_bytes(xml)
        staging.rename(OUTPUT / phase / "immediate")
    write_status(expected, "partial", in_progress=phase, action_effect="observation_captured")


def phase_record(phase: str) -> None:
    status = current_status()
    expected = ["baseline"] + list(PHASES[:PHASES.index(phase)])
    if (status.get("status") != "partial" or status.get("completed") != expected
            or status.get("in_progress") != phase or status.get("action_effect") != "observation_captured"
            or status.get("stop_reason") is not None):
        raise ValueError("unexpected inspection sequence")
    response, png, xml, root = read_observation(BRIDGE / f"{phase}-settled.json", HIERARCHIES / "codex-android-observe.xml")
    if phase in {"close-search", "reopen-search", "type-earth", "focus-earth", "reopen-search-again", "type-earth-again", "focus-earth-again", "collapse"}:
        valid_continuation_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
    else:
        valid_app_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / "settled"
        staging.mkdir()
        (staging / "observation.json").write_bytes(response)
        (staging / "screen.png").write_bytes(png)
        (staging / "hierarchy.xml").write_bytes(xml)
        staging.rename(OUTPUT / phase / "settled")
    if phase == "controls":
        less = [node for node in root.iter("node") if node.get("content-desc") == "Less" and node.get("enabled") == "true" and node.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"]]
        if len(less) != 1:
            raise ValueError("expanded controls are not uniquely observed")
    if phase == "search":
        fields = [node for node in root.iter("node")
                  if node.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"]
                  and node.get("class") == "android.widget.EditText"]
        focus = {"edit_text_count": len(fields),
                 "focused_values": [node.get("focused") for node in fields],
                 "keyboard_visual_observation": "pending"}
        (OUTPUT / phase / "focus-observation.json").write_text(json.dumps(focus) + "\n")
    if phase == "close-search" and any(n.get("text") == "Find a runtime body" for n in root.iter("node")):
        raise ValueError("search dialog remains after Close")
    if phase in {"type-earth", "type-earth-again"}:
        earth_focus_center(root, os.environ["INTERACTIVE_APP_PACKAGE"], png_dimensions(png))
    if phase in {"focus-earth", "focus-earth-again"}:
        if (len([n for n in root.iter("node") if n.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"] and n.get("text") == "Earth"]) != 1 or
                any(n.get("text") == "Find a runtime body" for n in root.iter("node"))):
            raise ValueError("Earth selection was not uniquely observed")
    if phase == "collapse":
        package = os.environ["INTERACTIVE_APP_PACKAGE"]
        nodes = list(root.iter("node"))
        if any(n.get("content-desc") in {"Less", "Search"} for n in nodes):
            raise ValueError("expanded controls remain after collapse")
        for description in ("Home", "Frame", "Help"):
            matches = [n for n in nodes if n.get("package") == package and
                       n.get("content-desc") == description and n.get("enabled") == "true"]
            if len(matches) != 1:
                raise ValueError("compact camera control is missing or ambiguous")
        scale = [n for n in nodes if n.get("package") == package and n.get("enabled") == "true" and
                 n.get("content-desc", "").startswith("Camera scale ") and
                 n.get("content-desc", "").endswith("; open camera controls")]
        if len(scale) != 1:
            raise ValueError("compact camera scale control is missing or ambiguous")
    completed = expected + [phase]
    write_status(completed, "captured" if phase == "collapse" else "partial")


FAILURE_STAGES = {"startup", "emulator", "provider", "preflight", "baseline_foreground",
    "baseline_config", "baseline_observe", "baseline_provider", "baseline_receipt", "baseline_image",
    "baseline_hierarchy", "baseline_quarantine", "baseline_package", "baseline_provenance", "baseline_publish",
    "bounded-advance", "launcher_recovery", "input_profile", "input_profile_restore", "help", "frame", "home", "controls", "search",
    "close-search", "reopen-search", "type-earth", "focus-earth", "reopen-search-again",
    "type-earth-again", "focus-earth-again", "collapse", "unknown"}


def mark_failed() -> None:
    stage = os.environ.get("INTERACTIVE_CAPTURE_FAILURE_STAGE", "unknown")
    if stage == "baseline_validate":
        try:
            stage = (BRIDGE / "baseline-stage").read_text()
        except OSError:
            stage = "unknown"
    if stage not in FAILURE_STAGES:
        stage = "unknown"
    if (OUTPUT / "status.json").exists():
        status = current_status()
        stage = status.get("failure_stage") or stage
        if stage not in FAILURE_STAGES:
            stage = "unknown"
        if status.get("status") == "failed" and not status.get("completed"):
            write_status([], "failed", "capture_stage_failed", failure_stage=stage)
        else:
            write_status(status.get("completed", []), "partial", "inspection_stopped",
                         status.get("in_progress"), status.get("action_effect"), stage)
    else:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        write_status([], "failed", "capture_stage_failed", failure_stage=stage)


if __name__ == "__main__":
    command = sys.argv[1:]
    if command == ["--check-provider"]:
        require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    elif command == ["--launcher-initial"]:
        launcher_initial()
    elif command == ["--launcher-decide"]:
        launcher_decide()
    elif command == ["--launcher-action-receipt"]:
        launcher_action_receipt()
    elif command == ["--baseline"]:
        capture_baseline()
    elif command == ["--prepare-help"]:
        phase_prepare("help")
    elif command == ["--prepare-frame"]:
        phase_prepare("frame")
    elif command == ["--prepare-home"]:
        phase_prepare("home")
    elif command == ["--prepare-controls"]:
        phase_prepare("controls")
    elif command == ["--prepare-search"]:
        phase_prepare("search")
    elif command == ["--snapshot-help"]:
        phase_snapshot("help")
    elif command == ["--snapshot-frame"]:
        phase_snapshot("frame")
    elif command == ["--snapshot-home"]:
        phase_snapshot("home")
    elif command == ["--snapshot-controls"]:
        phase_snapshot("controls")
    elif command == ["--snapshot-search"]:
        phase_snapshot("search")
    elif command == ["--record-help"]:
        phase_record("help")
    elif command == ["--record-frame"]:
        phase_record("frame")
    elif command == ["--record-home"]:
        phase_record("home")
    elif command == ["--record-controls"]:
        phase_record("controls")
    elif command == ["--record-search"]:
        phase_record("search")
    elif command == ["--prepare-type-earth"]:
        phase_prepare("type-earth")
    elif command == ["--snapshot-type-earth"]:
        phase_snapshot("type-earth")
    elif command == ["--record-type-earth"]:
        phase_record("type-earth")
    elif command == ["--prepare-focus-earth"]:
        phase_prepare("focus-earth")
    elif command == ["--snapshot-focus-earth"]:
        phase_snapshot("focus-earth")
    elif command == ["--record-focus-earth"]:
        phase_record("focus-earth")
    elif command == ["--prepare-collapse"]:
        phase_prepare("collapse")
    elif command == ["--snapshot-collapse"]:
        phase_snapshot("collapse")
    elif command == ["--record-collapse"]:
        phase_record("collapse")
    elif command == ["--prepare-close-search"]:
        phase_prepare("close-search")
    elif command == ["--snapshot-close-search"]:
        phase_snapshot("close-search")
    elif command == ["--record-close-search"]:
        phase_record("close-search")
    elif command == ["--prepare-reopen-search"]:
        phase_prepare("reopen-search")
    elif command == ["--snapshot-reopen-search"]:
        phase_snapshot("reopen-search")
    elif command == ["--record-reopen-search"]:
        phase_record("reopen-search")
    elif command == ["--prepare-reopen-search-again"]:
        phase_prepare("reopen-search-again")
    elif command == ["--snapshot-reopen-search-again"]:
        phase_snapshot("reopen-search-again")
    elif command == ["--record-reopen-search-again"]:
        phase_record("reopen-search-again")
    elif command == ["--prepare-type-earth-again"]:
        phase_prepare("type-earth-again")
    elif command == ["--snapshot-type-earth-again"]:
        phase_snapshot("type-earth-again")
    elif command == ["--record-type-earth-again"]:
        phase_record("type-earth-again")
    elif command == ["--prepare-focus-earth-again"]:
        phase_prepare("focus-earth-again")
    elif command == ["--snapshot-focus-earth-again"]:
        phase_snapshot("focus-earth-again")
    elif command == ["--record-focus-earth-again"]:
        phase_record("focus-earth-again")
    elif command == ["--mark-failed"]:
        mark_failed()
    else:
        raise SystemExit("invalid fixed inspection command")
