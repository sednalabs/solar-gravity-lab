#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DOWNLOAD_SCRIPT = ROOT / ".github" / "scripts" / "download_interactive_build_artifact.py"
sys.dont_write_bytecode = True


def load_download_module():
    spec = importlib.util.spec_from_file_location(
        "download_interactive_build_artifact",
        DOWNLOAD_SCRIPT,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {DOWNLOAD_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def assert_system_exit_contains(callable_obj, expected_text: str) -> None:
    try:
        callable_obj()
    except SystemExit as error:
        assert expected_text in str(error), str(error)
        return
    raise AssertionError("Expected SystemExit")


def main() -> int:
    module = load_download_module()
    manifest = {
        "android_validation_mode": "stage-first-mirror-on",
        "interactive_debug_profile": "hosted-debug-lite",
        "preferred_gpu_backend": "vulkan",
    }

    module.validate_manifest_matches_request(
        manifest,
        expected_android_validation_mode="stage-first-mirror-on",
        expected_interactive_debug_profile="hosted-debug-lite",
        expected_preferred_gpu_backend="vulkan",
    )
    module.validate_manifest_matches_request(
        manifest,
        expected_android_validation_mode=None,
        expected_interactive_debug_profile=None,
        expected_preferred_gpu_backend=None,
    )
    assert module.validate_repository("sednalabs/solar-gravity-lab") == "sednalabs/solar-gravity-lab"
    assert module.validate_run_id("123456") == "123456"

    assert_system_exit_contains(
        lambda: module.validate_manifest_matches_request(
            manifest,
            expected_android_validation_mode="shell-v2",
            expected_interactive_debug_profile="hosted-debug-lite",
            expected_preferred_gpu_backend="vulkan",
        ),
        "android_validation_mode",
    )
    assert_system_exit_contains(
        lambda: module.validate_manifest_matches_request(
            manifest,
            expected_android_validation_mode="stage-first-mirror-on",
            expected_interactive_debug_profile="hosted-debug-lite",
            expected_preferred_gpu_backend="none",
        ),
        "preferred_gpu_backend",
    )
    assert_system_exit_contains(
        lambda: module.validate_repository("https://example.com/sednalabs/solar-gravity-lab"),
        "Invalid GitHub repository",
    )
    assert_system_exit_contains(
        lambda: module.validate_run_id("run-123"),
        "Invalid workflow run id",
    )

    print("interactive build artifact manifest tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
