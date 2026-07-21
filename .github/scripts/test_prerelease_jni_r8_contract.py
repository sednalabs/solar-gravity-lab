#!/usr/bin/env python3
"""Regression coverage for the Rust JNI/R8 identity boundary."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VERIFY_SCRIPT = REPOSITORY_ROOT / ".github/scripts/verify_prerelease_jni_r8_contract.py"
PROGUARD_RULES = REPOSITORY_ROOT / "clients/android/app/proguard-rules.pro"


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

    def test_mapping_parser_detects_a_renamed_native_dto(self) -> None:
        verifier = load_verifier()
        dto_classes = verifier.required_jni_dto_classes(verifier.DEFAULT_FFI_SOURCE)

        with tempfile.TemporaryDirectory() as temporary_directory:
            mapping_path = Path(temporary_directory) / "mapping.txt"
            mapping_path.write_text(
                "\n".join(
                    f"{class_name} -> {class_name}:"
                    for class_name in dto_classes
                )
                + "\n",
                encoding="utf-8",
            )
            mapped = verifier.mapped_class_names(mapping_path)

        self.assertEqual(
            {class_name: class_name for class_name in dto_classes},
            {class_name: mapped[class_name] for class_name in dto_classes},
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            mapping_path = Path(temporary_directory) / "renamed-mapping.txt"
            mapping_path.write_text(
                "\n".join(
                    f"{class_name} -> {'a.b' if class_name == dto_classes[0] else class_name}:"
                    for class_name in dto_classes
                )
                + "\n",
                encoding="utf-8",
            )
            renamed = verifier.renamed_jni_dto_classes(
                verifier.DEFAULT_FFI_SOURCE,
                mapping_path,
            )

        self.assertEqual({dto_classes[0]: "a.b"}, renamed)


if __name__ == "__main__":
    unittest.main()
