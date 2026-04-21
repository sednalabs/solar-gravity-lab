#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Write the hosted interactive-session OpenAI loop config."
    )
    parser.add_argument("--mcp-url", required=True)
    parser.add_argument("--mcp-health-url", required=True)
    parser.add_argument("--session-root", required=True)
    parser.add_argument("--build-manifest", required=True)
    parser.add_argument("--mcp-workspace-dir", required=True)
    parser.add_argument("--default-model", required=True)
    parser.add_argument("--default-serial", required=True)
    parser.add_argument("--default-package-name", required=True)
    parser.add_argument("--default-activity", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--output-json", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    output_path = Path(args.output_json)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "schema_version": 1,
        "mcp_url": args.mcp_url,
        "mcp_health_url": args.mcp_health_url,
        "session_root": args.session_root,
        "build_manifest_path": args.build_manifest,
        "mcp_workspace_dir": args.mcp_workspace_dir,
        "default_model": args.default_model,
        "default_serial": args.default_serial,
        "default_package_name": args.default_package_name,
        "default_activity": args.default_activity,
        "output_root": args.output_root,
        "notes": [
            "Set OPENAI_API_KEY in the live shell before invoking the helper wrapper.",
            "The helper talks to the runner-local MCP endpoint instead of exposing raw artifact paths to the model.",
            "Hosted runs prefer uploaded OpenAI file ids for screenshots and XML/log artifacts when the key is available.",
        ],
    }

    output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
