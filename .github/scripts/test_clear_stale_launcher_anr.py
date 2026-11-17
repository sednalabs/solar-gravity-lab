import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


LAUNCHER = "com.google.android.apps.nexuslauncher"
SCRIPT = Path(__file__).with_name("clear_stale_launcher_anr.sh")


class LauncherAnrPreflightTest(unittest.TestCase):
    def run_preflight(self, package="", emulator=True, stuck=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            adb = root / "adb"
            adb.write_text(
                f"#!{sys.executable}\n" + '''
import json, os, pathlib, sys
root = pathlib.Path(os.environ["FAKE_ADB_ROOT"])
args = sys.argv[1:]
with (root / "commands.jsonl").open("a") as out:
    out.write(json.dumps(args) + "\\n")
if args == ["shell", "getprop", "ro.kernel.qemu"]:
    print(os.environ["FAKE_EMULATOR"])
elif args == ["shell", "dumpsys", "window", "windows"]:
    package = os.environ["FAKE_ANR_PACKAGE"]
    if package and (not (root / "cleared").exists() or os.environ["FAKE_STUCK"] == "1"):
        print("Window #6 Window{abc u0 Application Not Responding: " + package + "}")
    else:
        print("Window #1 Window{def u0 Launcher}")
elif args[:5] == ["shell", "cmd", "package", "resolve-activity", "--brief"]:
    print("com.google.android.apps.nexuslauncher/.NexusLauncherActivity")
elif args == ["shell", "dumpsys", "activity", "lastanr"]:
    print("Preserved pre-existing launcher ANR details")
elif args[:3] == ["shell", "am", "force-stop"]:
    (root / "cleared").touch()
else:
    raise SystemExit("Unexpected adb command: " + repr(args))
'''
            )
            adb.chmod(0o755)
            environment = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                FAKE_ADB_ROOT=str(root), FAKE_EMULATOR="1" if emulator else "0",
                FAKE_ANR_PACKAGE=package, FAKE_STUCK="1" if stuck else "0")
            result = subprocess.run(["bash", str(SCRIPT), str(root / "evidence")],
                env=environment, capture_output=True, text=True, timeout=10)
            commands = [json.loads(line) for line in (root / "commands.jsonl").read_text().splitlines()]
            evidence = {file.name: file.read_text() for file in (root / "evidence").iterdir()}
            return result, commands, evidence

    def test_clear_stale_launcher_only_and_verify_window(self):
        result, commands, evidence = self.run_preflight(LAUNCHER)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([["shell", "am", "force-stop", LAUNCHER]],
            [command for command in commands if "force-stop" in command])
        self.assertIn("Application Not Responding", evidence["launcher-anr.before.txt"])
        self.assertNotIn("Application Not Responding", evidence["launcher-anr.after.txt"])
        self.assertIn("launcher-anr.lastanr.txt", evidence)

    def test_healthy_launcher_needs_no_mutation(self):
        result, commands, _ = self.run_preflight()
        self.assertEqual(0, result.returncode)
        self.assertFalse(any("force-stop" in command for command in commands))

    def test_product_anr_is_preserved_and_rejects_obscured_run(self):
        result, commands, _ = self.run_preflight("com.sednalabs.solarlab")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("force-stop" in command for command in commands))

    def test_physical_device_is_not_modified(self):
        result, commands, _ = self.run_preflight(LAUNCHER, emulator=False)
        self.assertEqual(0, result.returncode)
        self.assertEqual([["shell", "getprop", "ro.kernel.qemu"]], commands)

    def test_remaining_dialog_fails_preflight(self):
        result, _, _ = self.run_preflight(LAUNCHER, stuck=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
