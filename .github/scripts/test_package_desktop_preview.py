from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from package_desktop_preview import package


class DesktopPackageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "qualified"
        self.source.mkdir()
        self.output = self.root / "release"
        self.sha = "a" * 40
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (62).to_bytes(2, "little")
        self.binary = bytes(header) + b"qualified executable fixture"
        (self.source / "solarlab-desktop").write_bytes(self.binary)
        (self.source / "README.md").write_text("Run ./solarlab-desktop\n")
        (self.source / "LICENSE").write_text("Licence fixture\n")
        (self.source / "ASSET_CREDITS.md").write_text("Asset credit fixture\n")
        (self.source / "source.txt").write_text(self.sha + "\n")
        self.record_checksum()

    def record_checksum(self) -> None:
        digest = hashlib.sha256((self.source / "solarlab-desktop").read_bytes()).hexdigest()
        (self.source / "SHA256SUMS").write_text(f"{digest}  solarlab-desktop\n")

    def build(self) -> dict:
        return package(self.source, self.output, self.sha, "0.2.0-alpha.3", "123")

    def test_archive_preserves_qualified_bytes_and_executable_permission(self) -> None:
        receipt = self.build()
        archive = self.output / receipt["artifact_name"]
        with tarfile.open(archive) as bundle:
            self.assertEqual(set(bundle.getnames()), {"solarlab-desktop", "README.md", "LICENSE", "ASSET_CREDITS.md", "source.txt", "SHA256SUMS"})
            self.assertEqual(bundle.getmember("solarlab-desktop").mode, 0o755)
            self.assertEqual(bundle.extractfile("solarlab-desktop").read(), self.binary)
            self.assertEqual(bundle.extractfile("source.txt").read().decode().strip(), self.sha)
        self.assertEqual(receipt["artifact_sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(receipt["commit_sha"], self.sha)
        self.assertEqual(receipt["validation_run_id"], "123")
        self.assertEqual(json.loads((self.output / "desktop-provenance-0.2.0-alpha.3.json").read_text()), receipt)

    def test_different_source_cannot_be_relabelled_as_qualified(self) -> None:
        (self.source / "source.txt").write_text("b" * 40)
        with self.assertRaisesRegex(ValueError, "different source"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_modified_binary_cannot_be_published_with_old_checksum(self) -> None:
        (self.source / "solarlab-desktop").write_bytes(self.binary + b"changed")
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.build()

    def test_wrong_architecture_is_rejected_even_with_matching_checksum(self) -> None:
        binary = bytearray(self.binary)
        binary[18:20] = (183).to_bytes(2, "little")
        (self.source / "solarlab-desktop").write_bytes(binary)
        self.record_checksum()
        with self.assertRaisesRegex(ValueError, "Linux x64"):
            self.build()

    def test_artifact_cannot_substitute_an_external_file(self) -> None:
        (self.root / "external-readme").write_text("external")
        (self.source / "README.md").unlink()
        (self.source / "README.md").symlink_to(self.root / "external-readme")
        with self.assertRaisesRegex(ValueError, "ordinary desktop artifact"):
            self.build()


    def test_invalid_or_oversized_version_cannot_create_release_files(self) -> None:
        for version in (
            "0.2.0/other", "0.2.0\\other", "0.2.0\n", "0.2.0-",
            "0.2.0-alpha..3", "0.2.0-alpha.03", "00.2.0", "０.2.0",
            "0.2.0-" + "a" * 128,
        ):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ValueError, "ordinary release version"):
                    package(self.source, self.output, self.sha, version, "123")
                self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
