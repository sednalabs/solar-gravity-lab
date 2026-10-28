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


def read_observation(response_path: Path, hierarchy_path: Path, *, is_step: bool = False, mark=None):
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
    if is_step and outcome.get("actionTypes") != ["click"]:
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


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise ValueError("invalid control bounds")
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise ValueError("empty control bounds")
    return left, top, right, bottom


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
        node_bounds = bounds(node)
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
PHASES = ("help", "frame", "home", "controls")


def valid_app_view(root: ET.Element, package: str) -> None:
    nodes = list(root.iter("node"))
    if not any(node.get("package") == package for node in nodes):
        raise ValueError("hierarchy does not contain the expected package")
    if any(node.get("content-desc") in {HELP, DISMISS} or node.get("text") in {HELP, DISMISS} for node in nodes):
        raise ValueError("help remains visible after dismissal")


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


def capture_baseline() -> None:
    baseline_stage("baseline_provider")
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    response, png, xml, root = read_observation(BRIDGE / "baseline-response.json", HIERARCHIES / "codex-android-observe.xml", mark=baseline_stage)
    baseline_stage("baseline_package")
    if not any(node.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"] for node in root.iter("node")):
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


def phase_prepare(phase: str) -> None:
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    status = current_status()
    expected = ["baseline"] + list(PHASES[:PHASES.index(phase)])
    if (status.get("status") != "partial" or status.get("completed") != expected
            or status.get("in_progress") is not None or status.get("stop_reason") is not None):
        raise ValueError("unexpected inspection sequence")
    previous = OUTPUT / expected[-1]
    if expected[-1] != "baseline":
        previous = previous / "settled"
    png = (previous / "screen.png").read_bytes()
    root = ET.fromstring((previous / "hierarchy.xml").read_bytes())
    package = os.environ["INTERACTIVE_APP_PACKAGE"]
    viewport = png_dimensions(png)
    if phase == "help":
        x, y = help_center(root, package, viewport)
    else:
        valid_app_view(root, package)
        if phase == "frame":
            earth = [n for n in root.iter("node") if n.get("text") == "Earth" and n.get("package") == package]
            if len(earth) != 1:
                raise ValueError("Earth selection is not uniquely visible")
        def selected(node):
            description = node.get("content-desc", "")
            if phase == "frame":
                return description == "Frame"
            if phase == "home":
                return description == "Home"
            return description.startswith("Camera scale ") and description.endswith("; open camera controls")
        targets = [n for n in root.iter("node") if selected(n)]
        if len(targets) != 1:
            raise ValueError("inspection target is missing or ambiguous")
        x, y = target_center(root, package, viewport, targets[0])
    request = {"tool": "android_step", "arguments": {"actions": [{"type": "click", "x": x, "y": y}],
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
    response, png, xml, root = read_observation(BRIDGE / f"{phase}-response.json", HIERARCHIES / "codex-click-after.xml", is_step=True)
    valid_app_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / "immediate"
        staging.mkdir()
        (staging / "action-response.json").write_bytes(response)
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
    valid_app_view(root, os.environ["INTERACTIVE_APP_PACKAGE"])
    with tempfile.TemporaryDirectory(dir=BRIDGE) as temporary:
        staging = Path(temporary) / "settled"
        staging.mkdir()
        (staging / "observation.json").write_bytes(response)
        (staging / "screen.png").write_bytes(png)
        (staging / "hierarchy.xml").write_bytes(xml)
        staging.rename(OUTPUT / phase / "settled")
    completed = expected + [phase]
    write_status(completed, "partial")
    if phase == "controls":
        less = [node for node in root.iter("node") if node.get("content-desc") == "Less" and node.get("enabled") == "true" and node.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"]]
        if len(less) != 1:
            raise ValueError("expanded controls are not uniquely observed")
        write_status(completed, "captured")


FAILURE_STAGES = {"startup", "emulator", "provider", "preflight", "baseline_foreground",
    "baseline_config", "baseline_observe", "baseline_provider", "baseline_receipt", "baseline_image",
    "baseline_hierarchy", "baseline_package", "baseline_provenance", "baseline_publish",
    "help", "frame", "home", "controls", "unknown"}


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
    elif command == ["--snapshot-help"]:
        phase_snapshot("help")
    elif command == ["--snapshot-frame"]:
        phase_snapshot("frame")
    elif command == ["--snapshot-home"]:
        phase_snapshot("home")
    elif command == ["--snapshot-controls"]:
        phase_snapshot("controls")
    elif command == ["--record-help"]:
        phase_record("help")
    elif command == ["--record-frame"]:
        phase_record("frame")
    elif command == ["--record-home"]:
        phase_record("home")
    elif command == ["--record-controls"]:
        phase_record("controls")
    elif command == ["--mark-failed"]:
        mark_failed()
    else:
        raise SystemExit("invalid fixed inspection command")
