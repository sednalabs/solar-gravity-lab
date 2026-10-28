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


def read_observation(response_path: Path, hierarchy_path: Path, *, is_step: bool = False):
    if response_path.stat().st_size > 20_000_000 or hierarchy_path.stat().st_size > 2_000_000:
        raise ValueError("observation exceeds capture bound")
    response = response_path.read_bytes()
    receipt = json.loads(response)
    outcome = receipt.get("metadata", {}).get("android", {}).get("outcome", {})
    items = receipt.get("contentItems")
    if receipt.get("success") is not True or outcome.get("status") != "succeeded" or not has_decodable_native_png(items):
        raise ValueError("adapter observation outcome or native image check failed")
    if is_step and outcome.get("actionTypes") != ["click"]:
        raise ValueError("unexpected adapter action")
    image_items = [item for item in items if isinstance(item, dict) and item.get("type") == "inputImage"]
    if len(image_items) != 1:
        raise ValueError("expected one native image")
    png = base64.b64decode(image_items[0]["imageUrl"].partition(",")[2], validate=True)
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
    x, y = (dismiss_bounds[0] + dismiss_bounds[2]) // 2, (dismiss_bounds[1] + dismiss_bounds[3]) // 2
    parents = {child: parent for parent in root.iter() for child in parent}

    def ancestry(node):
        chain = [node]
        while chain[-1] in parents:
            chain.append(parents[chain[-1]])
        return chain

    target_ancestry = set(ancestry(dismiss_node))
    help_chain = ancestry(help_node)

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
        common = next((ancestor for ancestor in stage_chain if ancestor in help_chain), None)
        if common is None or common is node or common is help_node:
            return False
        stage_branch = stage_chain[stage_chain.index(common) - 1]
        help_branch = help_chain[help_chain.index(common) - 1]
        siblings = list(common)
        return siblings.index(stage_branch) < siblings.index(help_branch)

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
        raise ValueError("expected one matching stage backdrop behind help")
    return x, y


def prepare() -> None:
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    _, png, _, root = read_observation(BRIDGE / "baseline-response.json", HIERARCHIES / "codex-android-observe.xml")
    x, y = help_center(root, os.environ["INTERACTIVE_APP_PACKAGE"], png_dimensions(png))
    request = {"tool": "android_step", "arguments": {"actions": [{"type": "click", "x": x, "y": y}],
        "post_observe_scope": "screen_and_ui"}}
    (BRIDGE / "help-request.json").write_text(json.dumps(request) + "\n")


def finish() -> None:
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    before, before_png, before_xml, before_root = read_observation(BRIDGE / "baseline-response.json", HIERARCHIES / "codex-android-observe.xml")
    help_center(before_root, os.environ["INTERACTIVE_APP_PACKAGE"], png_dimensions(before_png))
    after, after_png, after_xml, after_root = read_observation(BRIDGE / "help-response.json", HIERARCHIES / "codex-click-after.xml", is_step=True)
    after_nodes = list(after_root.iter("node"))
    if any(node.get("content-desc") in {HELP, DISMISS} or node.get("text") in {HELP, DISMISS} for node in after_nodes):
        raise ValueError("help remains visible after dismissal")
    if not any(node.get("package") == os.environ["INTERACTIVE_APP_PACKAGE"] for node in after_nodes):
        raise ValueError("post-action hierarchy does not contain the expected package")
    build = json.loads((SESSION / "qualified-prerelease/qualified-prerelease-intake.json").read_text())
    if build.get("artifact_kind") != "qualified-prerelease" or build.get("package_name") != os.environ["INTERACTIVE_APP_PACKAGE"]:
        raise ValueError("unexpected capture build")
    provenance = {"workflow_sha": os.environ["INTERACTIVE_WORKFLOW_SHA"], "checkout_sha": os.environ["INTERACTIVE_CHECKOUT_SHA"],
        "provider_sha": os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"], "toolkit_sha": os.environ["INTERACTIVE_TOOLKIT_SHA"],
        "build": build, "foreground_package": os.environ["INTERACTIVE_APP_PACKAGE"], "response_session_identity": "unknown", "stability": "unknown",
        "transport_attempts": "unknown", "provider_may_fallback": True}
    output = Path("dist/interactive-capture")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "capture"
        staging.mkdir()
        for name, data in (("baseline.png", before_png), ("baseline.xml", before_xml), ("observation.json", before),
                           ("after-help.png", after_png), ("after-help.xml", after_xml), ("help-response.json", after)):
            (staging / name).write_bytes(data)
        (staging / "help-request.json").write_bytes((BRIDGE / "help-request.json").read_bytes())
        (staging / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        (staging / "status.json").write_text(json.dumps({"status": "captured", "transition": "help-dismissed", "stability": "unknown", "response_session_identity": "unknown", "transport_attempts": "unknown", "provider_may_fallback": True, "visual_acceptance": "pending"}) + "\n")
        staging.rename(output)


if __name__ == "__main__":
    if sys.argv[1:] == ["--check-provider"]:
        require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    elif sys.argv[1:] == ["--prepare"]:
        prepare()
    elif sys.argv[1:] == ["--finish"]:
        finish()
    else:
        raise SystemExit("expected --check-provider, --prepare, or --finish")
