#!/usr/bin/env python3
"""Regression coverage for the Rust JNI/R8 identity boundary."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VERIFY_SCRIPT = REPOSITORY_ROOT / ".github/scripts/verify_prerelease_jni_r8_contract.py"
PROGUARD_RULES = REPOSITORY_ROOT / "clients/android/app/proguard-rules.pro"
APP_BUILD = REPOSITORY_ROOT / "clients/android/app/build.gradle.kts"
ANDROID_LAUNCH_SMOKE = REPOSITORY_ROOT / ".github/scripts/android_launch_smoke.sh"


def load_verifier():
    spec = importlib.util.spec_from_file_location("verify_prerelease_jni_r8_contract", VERIFY_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {VERIFY_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PrereleaseJniR8ContractTest(unittest.TestCase):
    def test_every_rust_constructed_runtime_dto_is_covered_by_the_keep_rule(self) -> None:
        verifier = load_verifier()
        dto_classes = verifier.required_jni_dto_classes(verifier.DEFAULT_FFI_SOURCE)
        rules = PROGUARD_RULES.read_text(encoding="utf-8")

        self.assertGreater(len(dto_classes), 0)
        self.assertTrue(
            all(class_name.startswith(verifier.RUNTIME_PREFIX) for class_name in dto_classes)
        )
        self.assertIn(
            "-keep class com.sednalabs.solarlab.runtime.Native* {\n    <init>(...);\n}",
            rules,
        )

    def test_dex_class_parser_detects_a_renamed_native_dto(self) -> None:
        verifier = load_verifier()
        dto_classes = verifier.required_jni_dto_classes(verifier.DEFAULT_FFI_SOURCE)

        dex_output = "\n".join(
            f"C d 1 1 1 {class_name}"
            for class_name in dto_classes
        )
        defined_classes = verifier.defined_dex_classes_from_output(dex_output)
        self.assertEqual(
            set(dto_classes),
            defined_classes,
        )

        renamed_output = "\n".join(
            f"C d 1 1 1 {'a.b' if class_name == dto_classes[0] else class_name}"
            for class_name in dto_classes
        )
        missing = verifier.missing_jni_dto_classes(
            verifier.DEFAULT_FFI_SOURCE,
            verifier.defined_dex_classes_from_output(renamed_output),
        )
        self.assertEqual({dto_classes[0]}, missing)

    def test_dex_enumeration_uses_the_fixed_sdk_command(self) -> None:
        verifier = load_verifier()
        completed = Mock(
            returncode=0,
            stdout="C d 1 1 1 com.sednalabs.solarlab.runtime.NativeResult\n",
            stderr="",
        )

        with patch.object(verifier.subprocess, "run", return_value=completed) as run:
            classes = verifier.defined_dex_classes(Path("candidate.apk"))

        self.assertEqual({"com.sednalabs.solarlab.runtime.NativeResult"}, classes)
        self.assertEqual(
            ["apkanalyzer", "dex", "packages", "--defined-only", "candidate.apk"],
            run.call_args.args[0],
        )

    def test_dex_enumeration_reports_a_missing_sdk_command(self) -> None:
        verifier = load_verifier()

        with patch.object(
            verifier.subprocess,
            "run",
            side_effect=FileNotFoundError("apkanalyzer"),
        ):
            with self.assertRaisesRegex(RuntimeError, "apkanalyzer could not start"):
                verifier.defined_dex_classes(Path("candidate.apk"))

    def test_minified_prerelease_uses_the_jni_keep_rules(self) -> None:
        build_file = APP_BUILD.read_text(encoding="utf-8")
        prerelease_block = build_file.split('create("prerelease") {', 1)[1].split(
            '\n        release {',
            1,
        )[0]

        self.assertIn("proguardFiles(", prerelease_block)
        self.assertIn('getDefaultProguardFile("proguard-android-optimize.txt")', prerelease_block)
        self.assertIn('"proguard-rules.pro"', prerelease_block)

    def test_runtime_readiness_smoke_preserves_bridge_records(self) -> None:
        smoke_script = ANDROID_LAUNCH_SMOKE.read_text(encoding="utf-8")

        self.assertIn("adb logcat -d -v threadtime", smoke_script)
        self.assertIn(
            '| grep -F " SolarLabRuntimeBridge:" > "$runtime_bridge_log" || true',
            smoke_script,
        )
        self.assertNotIn(
            "adb logcat -d -s SolarLabRuntimeBridge:I SolarLabRuntimeBridge:E '*:S'",
            smoke_script,
        )
        self.assertIn(
            "connect\\.initial-refresh\\.render\\.refresh\\.result .*lease=ready",
            smoke_script,
        )
        self.assertIn('rm -f "$runtime_ui_dump" || true', smoke_script)
        self.assertIn('adb shell rm -f /sdcard/solar-launch-smoke-window.xml', smoke_script)
        self.assertIn('rm -f "$runtime_bridge_log" || true', smoke_script)
        self.assertIn("App process died while waiting for runtime to become ready", smoke_script)


if __name__ == "__main__":
    unittest.main()
