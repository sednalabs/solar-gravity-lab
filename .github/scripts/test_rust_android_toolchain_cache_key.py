from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("compute_rust_android_toolchain_cache_key.sh")


class RustAndroidToolchainCacheKeyTests(unittest.TestCase):
    def run_helper(self, *, release: str, commit: str, targets: str) -> dict[str, str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            fake_bin = temp / "bin"
            fake_bin.mkdir()

            self.write_executable(
                fake_bin / "rustup",
                """#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == "show active-toolchain" ]]; then
  echo "stable-x86_64-unknown-linux-gnu (default)"
  exit 0
fi
echo "unexpected rustup arguments: $*" >&2
exit 1
""",
            )
            self.write_executable(
                fake_bin / "rustc",
                """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-Vv" ]]; then
  cat <<EOF
rustc ${FAKE_RUST_RELEASE} (${FAKE_RUST_COMMIT} 2026-07-07)
binary: rustc
commit-hash: ${FAKE_RUST_COMMIT}
commit-date: 2026-07-07
host: x86_64-unknown-linux-gnu
release: ${FAKE_RUST_RELEASE}
LLVM version: 22.1.6
EOF
  exit 0
fi
echo "rustc ${FAKE_RUST_RELEASE} (${FAKE_RUST_COMMIT} 2026-07-07)"
""",
            )
            self.write_executable(
                fake_bin / "cargo",
                """#!/usr/bin/env bash
set -euo pipefail
echo "cargo 1.97.0 (placeholder 2026-07-07)"
""",
            )

            env = os.environ.copy()
            env.pop("GITHUB_OUTPUT", None)
            env.update(
                {
                    "PATH": f"{fake_bin}:{env['PATH']}",
                    "HOME": str(temp),
                    "RUSTUP_HOME": str(temp / "rustup"),
                    "RUST_ANDROID_TOOLCHAIN_CACHE_VERSION": "v2",
                    "RUST_ANDROID_TARGETS": targets,
                    "RUNNER_OS": "Linux",
                    "RUNNER_ARCH": "X64",
                    "FAKE_RUST_RELEASE": release,
                    "FAKE_RUST_COMMIT": commit,
                }
            )
            result = subprocess.run(
                ["bash", str(SCRIPT)],
                check=True,
                capture_output=True,
                text=True,
                env=env,
            )

        return {
            key: value
            for line in result.stdout.splitlines()
            if "=" in line
            for key, value in [line.split("=", 1)]
        }

    @staticmethod
    def write_executable(path: Path, contents: str) -> None:
        path.write_text(contents, encoding="utf-8")
        path.chmod(0o755)

    def test_restore_prefix_changes_with_effective_rust_compiler(self) -> None:
        old = self.run_helper(
            release="1.96.1",
            commit="31fca3adb0000000000000000000000000000000",
            targets="aarch64-linux-android x86_64-linux-android",
        )
        new = self.run_helper(
            release="1.97.0",
            commit="2d8144b780000000000000000000000000000000",
            targets="aarch64-linux-android x86_64-linux-android",
        )

        self.assertNotEqual(old["restore-key-1"], new["restore-key-1"])
        self.assertNotEqual(old["primary-key"], new["primary-key"])

    def test_same_compiler_can_restore_a_partial_target_cache(self) -> None:
        one_target = self.run_helper(
            release="1.97.0",
            commit="2d8144b780000000000000000000000000000000",
            targets="aarch64-linux-android",
        )
        two_targets = self.run_helper(
            release="1.97.0",
            commit="2d8144b780000000000000000000000000000000",
            targets="aarch64-linux-android x86_64-linux-android",
        )

        self.assertEqual(one_target["restore-key-1"], two_targets["restore-key-1"])
        self.assertNotEqual(one_target["primary-key"], two_targets["primary-key"])
        self.assertTrue(one_target["primary-key"].startswith(one_target["restore-key-1"]))


if __name__ == "__main__":
    unittest.main()
