"""Packaged smoke identity checks without a device or a local Android SDK."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().with_name("run_prerelease_android_launch_smoke.sh")
ACTIVITY = "com.sednalabs.solarlab.MainActivity"


class PackagedLaunchIdentityTest(unittest.TestCase):
    def run_smoke(self, package, version_code="2004002", label="research-benchmark", sdk_tool=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / ".github/scripts"
            scripts.mkdir(parents=True)
            (scripts / "clear_stale_launcher_anr.sh").write_text("touch preflight-ran\n")
            (scripts / "android_launch_smoke.sh").write_text('printf "%s\\n" "$@" > launch-args.txt\n')
            bin_dir = root / "bin"
            bin_dir.mkdir()
            sdk_root = root / "android-sdk"
            analyzer_dir = sdk_root / "cmdline-tools/latest/bin" if sdk_tool else bin_dir
            analyzer_dir.mkdir(parents=True, exist_ok=True)
            analyzer = analyzer_dir / "apkanalyzer"
            analyzer.write_text(f"#!{sys.executable}\n" + '''
import json, os, pathlib, sys
args = sys.argv[1:]
with pathlib.Path("analyzer-args.jsonl").open("a") as out:
    out.write(json.dumps(args) + "\\n")
assert len(args) == 3 and args[0] == "manifest"
assert pathlib.Path(args[2]).is_file()
if args[1] == "application-id":
    print(os.environ["FAKE_APK_PACKAGE"])
elif args[1] == "version-code":
    print(os.environ["FAKE_APK_VERSION_CODE"])
else:
    raise SystemExit("Unexpected manifest query: " + repr(args))
''')
            analyzer.chmod(0o755)
            adb = bin_dir / "adb"
            adb.write_text(f"#!{sys.executable}\n" + '''
import json, pathlib, sys
args = sys.argv[1:]
with pathlib.Path("adb-args.jsonl").open("a") as out:
    out.write(json.dumps(args) + "\\n")
if args == ["logcat", "-d", "-v", "threadtime"]:
    print("SolarLabBenchmark: ready cpu_cases=61 gpu_cases=12")
elif args == ["exec-out", "screencap", "-p"]:
    sys.stdout.buffer.write(b"fake-png")
else:
    raise SystemExit("Unexpected adb command: " + repr(args))
''')
            adb.chmod(0o755)
            actual_label = label or "internal-dev-preview"
            apk_path = Path(f"dist/solar-gravity-lab-test-{actual_label}.apk")
            (root / apk_path).parent.mkdir()
            (root / apk_path).touch()
            environment = dict(os.environ, PATH=f"{bin_dir}:/usr/bin:/bin",
                ANDROID_SDK_ROOT=str(sdk_root), ANDROID_HOME="",
                FAKE_APK_PACKAGE=package, FAKE_APK_VERSION_CODE=version_code)
            arguments = ["bash", str(SCRIPT), "test"]
            if label is not None:
                arguments.append(label)
            result = subprocess.run(arguments, cwd=root, env=environment,
                capture_output=True, text=True, timeout=10)

            def lines(filename):
                path = root / filename
                return path.read_text().splitlines() if path.exists() else []

            return result, lines("launch-args.txt"), lines("dist/startup-smoke/apk-identity.txt"), \
                [json.loads(line) for line in lines("analyzer-args.jsonl")], \
                [json.loads(line) for line in lines("adb-args.jsonl")], (root / "preflight-ran").exists()

    def test_benchmark_launches_the_apk_version_without_runtime_readiness(self):
        for code in ("2004002", "2004003"):
            with self.subTest(code=code):
                package = f"com.sednalabs.solarlab.benchmark{code}"
                result, launch, identity, analyzer, adb, preflight = self.run_smoke(package, code)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(["--package", package, "--activity", ACTIVITY,
                    "--apk", "dist/solar-gravity-lab-test-research-benchmark.apk",
                    "--out-dir", "dist/startup-smoke"], launch)
                self.assertEqual([f"package={package}", f"version_code={code}"], identity)
                self.assertEqual(["application-id", "version-code"], [args[1] for args in analyzer])
                self.assertIn(["logcat", "-d", "-v", "threadtime"], adb)
                self.assertTrue(preflight)

    def test_preview_default_and_release_keep_their_existing_targets(self):
        for label, package in ((None, "com.sednalabs.solarlab.internal"),
                               ("release", "com.sednalabs.solarlab")):
            with self.subTest(label=label):
                result, launch, _, _, adb, _ = self.run_smoke(package, label=label)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(package, launch[1])
                self.assertEqual(ACTIVITY, launch[3])
                self.assertEqual("--require-runtime-ready", launch[-1])
                self.assertEqual([], adb)

    def test_legacy_wrong_version_and_wrong_variant_are_rejected_before_device_work(self):
        for package, label in (("com.sednalabs.solarlab.benchmark", "research-benchmark"),
                               ("com.sednalabs.solarlab.benchmark2004001", "research-benchmark"),
                               ("com.sednalabs.solarlab.internal", "research-benchmark"),
                               ("com.sednalabs.solarlab.benchmark2004002", "release")):
            with self.subTest(package=package, label=label):
                result, launch, _, _, adb, preflight = self.run_smoke(package, label=label)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("identity mismatch", result.stderr)
                self.assertEqual([], launch)
                self.assertEqual([], adb)
                self.assertFalse(preflight)

    def test_sdk_lookup_works_without_analyzer_on_path(self):
        result, launch, _, _, _, _ = self.run_smoke(
            "com.sednalabs.solarlab.benchmark2004002", sdk_tool=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("com.sednalabs.solarlab.benchmark2004002", launch[1])

    def test_invalid_manifest_version_is_rejected_before_device_work(self):
        result, launch, _, _, adb, preflight = self.run_smoke(
            "com.sednalabs.solarlab.benchmark2004002", version_code="invalid")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("invalid versionCode", result.stderr)
        self.assertEqual([], launch)
        self.assertEqual([], adb)
        self.assertFalse(preflight)


if __name__ == "__main__":
    unittest.main()
