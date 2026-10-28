#!/usr/bin/env python3
"""Capture one verified native Android observation without a live tunnel."""
import base64
import json
import os
import sys
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

from validate_interactive_png import has_decodable_native_png


CAPTURE_PROVIDER_SHA = "0e5a3dc692f39022fa84" + "d88c927e4a59801673e4"


def require_capture_provider(value: str) -> None:
    if value != CAPTURE_PROVIDER_SHA:
        raise ValueError("capture provider revision does not match the inspected contract")


def main() -> None:
    require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    session = Path("dist/interactive-session")
    output = Path("dist/interactive-capture")
    response_path = session / "codex-bridge/baseline-response.json"
    if response_path.stat().st_size > 20_000_000:
        raise ValueError("observation exceeds capture bound")
    response = response_path.read_bytes()
    if len(response) > 20_000_000:
        raise ValueError("observation exceeds capture bound")
    receipt = json.loads(response)
    outcome = receipt.get("metadata", {}).get("android", {}).get("outcome", {})
    items = receipt.get("contentItems")
    if receipt.get("success") is not True or outcome.get("status") != "succeeded" or not has_decodable_native_png(items):
        raise ValueError("adapter observation outcome or native image check failed")
    image_items = [item for item in items if isinstance(item, dict) and item.get("type") == "inputImage"]
    if len(image_items) != 1:
        raise ValueError("expected one native image")
    png = base64.b64decode(image_items[0]["imageUrl"].partition(",")[2], validate=True)
    hierarchy_path = session / "android-computer-use-mcp-artifacts/uiautomator/codex-android-observe.xml"
    xml = hierarchy_path.read_bytes()
    if len(xml) > 2_000_000:
        raise ValueError("hierarchy exceeds capture bound")
    root = ET.fromstring(xml)
    if root.tag != "hierarchy" or not list(root.iter("node")):
        raise ValueError("native hierarchy missing or empty")
    build = json.loads(Path(os.environ["INTERACTIVE_BUILD_MANIFEST"]).read_text())
    if build.get("artifact_kind") != "qualified-prerelease" or build.get("package_name") != os.environ["INTERACTIVE_APP_PACKAGE"]:
        raise ValueError("unexpected capture build")
    provenance = {
        "workflow_sha": os.environ["INTERACTIVE_WORKFLOW_SHA"],
        "checkout_sha": os.environ["INTERACTIVE_CHECKOUT_SHA"],
        "provider_sha": os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"],
        "toolkit_sha": os.environ["INTERACTIVE_TOOLKIT_SHA"],
        "build": build,
        "foreground_package": os.environ["INTERACTIVE_APP_PACKAGE"],
        "response_session_identity": "unknown",
        "stability": "unknown",
        "adapter_outcome": outcome.get("status"),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "capture"
        staging.mkdir()
        (staging / "baseline.png").write_bytes(png)
        (staging / "baseline.xml").write_bytes(xml)
        (staging / "observation.json").write_bytes(response)
        (staging / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        (staging / "status.json").write_text(json.dumps({"status": "captured", "adapter_outcome": outcome.get("status"), "stability": "unknown", "response_session_identity": "unknown", "visual_acceptance": "pending"}) + "\n")
        staging.rename(output)


if __name__ == "__main__":
    if sys.argv[1:] == ["--check-provider"]:
        require_capture_provider(os.environ["INTERACTIVE_MCP_ADAPTER_REVISION"])
    else:
        main()
