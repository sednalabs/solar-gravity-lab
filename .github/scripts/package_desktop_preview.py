#!/usr/bin/env python3
"""Package the exact Linux binary qualified by the native desktop journey."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile


def package(source_dir: Path, output_dir: Path, source: str, version: str, run_id: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("A complete qualified source SHA is required")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError("An ordinary release version is required")
    if not re.fullmatch(r"[1-9][0-9]*", run_id):
        raise ValueError("A validation run ID is required")
    names = ("solarlab-desktop", "README.md", "LICENSE", "ASSET_CREDITS.md", "source.txt", "SHA256SUMS")
    for name in names:
        path = source_dir / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Missing ordinary desktop artifact file: {name}")
    if (source_dir / "source.txt").read_text().strip() != source:
        raise ValueError("Desktop binary was qualified from a different source")
    digest_record = (source_dir / "SHA256SUMS").read_text().split()
    binary = source_dir / "solarlab-desktop"
    binary_bytes = binary.read_bytes()
    if binary_bytes[:6] != b"\x7fELF\x02\x01" or int.from_bytes(binary_bytes[18:20], "little") != 62:
        raise ValueError("Desktop preview must be a Linux x64 ELF executable")
    binary_sha = hashlib.sha256(binary_bytes).hexdigest()
    if digest_record != [binary_sha, "solarlab-desktop"]:
        raise ValueError("Desktop binary checksum differs from its qualification")
    output_dir.mkdir(parents=True, exist_ok=True)
    archive = output_dir / f"solarlab-desktop-{version}-linux-x64.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        for name in names:
            path = source_dir / name
            info = bundle.gettarinfo(str(path), arcname=name)
            info.mode = 0o755 if name == "solarlab-desktop" else 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            with path.open("rb") as data:
                bundle.addfile(info, data)
    archive_sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(archive.suffix + ".sha256").write_text(f"{archive_sha}  {archive.name}\n")
    receipt = {
        "schema_version": 1,
        "provenance_kind": "qualified-desktop-preview",
        "commit_sha": source,
        "version_name": version,
        "platform": "linux-x64",
        "validation_run_id": run_id,
        "validation_workflow": "validation-lab",
        "binary_sha256": binary_sha,
        "artifact_name": archive.name,
        "artifact_sha256": archive_sha,
        "visual_proof": "Chromium WebGL2 with software rendering",
    }
    (output_dir / f"desktop-provenance-{version}.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--validation-run", required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.input, args.output, args.source, args.version, args.validation_run)))
