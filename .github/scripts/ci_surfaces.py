#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import PurePosixPath


ALL_CODEQL_LANGUAGES = ("actions", "c-cpp", "java-kotlin", "python", "rust")

DOC_ONLY_NAMES = {
    "README.md",
    ".github/workflows/docs-sanity.yml",
    ".github/scripts/check_markdown_links.py",
    ".github/scripts/check_public_doc_safety.py",
}

CODEQL_POLICY_PATHS = {
    ".github/workflows/codeql.yml",
    ".github/scripts/ci_surfaces.py",
    ".github/scripts/resolve_codeql_plan.py",
    ".github/scripts/test_ci_surfaces.py",
    ".github/scripts/test_codeql_plan.py",
    ".github/codeql/codeql-config.yml",
    ".github/codeql/codeql-rust-pr.yml",
}

C_CPP_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx"}
JAVA_KOTLIN_SUFFIXES = {".java", ".kt", ".kts"}
JAVASCRIPT_SUFFIXES = {".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx"}


@dataclass(frozen=True)
class SurfaceSummary:
    surfaces: frozenset[str]
    codeql_languages: tuple[str, ...]
    is_docs_only: bool
    is_codeql_critical: bool


def normalize_path(path: str) -> str:
    normalized = str(PurePosixPath(path.strip()))
    return "" if normalized == "." else normalized


def is_doc_path(path: str) -> bool:
    return (
        path in DOC_ONLY_NAMES
        or path.startswith("docs/")
        or path.endswith(".md")
        or path.endswith(".mdx")
    )


def classify_path(raw_path: str) -> set[str]:
    path = normalize_path(raw_path)
    if not path:
        return set()

    suffix = PurePosixPath(path).suffix
    surfaces: set[str] = set()

    if is_doc_path(path):
        surfaces.add("docs")

    if path.startswith((".github/workflows/", ".github/actions/")) or path.endswith(
        ("/action.yml", "/action.yaml")
    ) or path in {"action.yml", "action.yaml"}:
        surfaces.add("actions")

    if path in CODEQL_POLICY_PATHS or path.startswith(".github/codeql/"):
        surfaces.add("codeql_policy")

    if path.startswith(".github/scripts/") or path.startswith(".github/actions/"):
        surfaces.add("ci_tooling")

    if (
        "cache" in path.lower()
        or path.startswith(".github/actions/install-sccache/")
        or path.startswith(".github/actions/rust-shared-cache/")
        or path.startswith(".github/actions/prepare-rust-android-toolchain/")
    ):
        surfaces.add("cache")

    if "release" in path.lower() or path.startswith(".github/workflows/prerelease"):
        surfaces.add("release")

    if suffix == ".py":
        surfaces.add("python")

    if suffix in C_CPP_SUFFIXES or path.endswith("CMakeLists.txt"):
        surfaces.add("c-cpp")

    if suffix in JAVA_KOTLIN_SUFFIXES:
        surfaces.add("java-kotlin")

    if suffix in JAVASCRIPT_SUFFIXES:
        surfaces.add("javascript-typescript")

    if (
        suffix == ".rs"
        or path.endswith("/Cargo.toml")
        or path.endswith("/Cargo.lock")
        or path in {"Cargo.toml", "Cargo.lock"}
    ):
        surfaces.add("rust")

    if path in {"rust-toolchain", "rust-toolchain.toml"} or path.endswith(("/rust-toolchain", "/rust-toolchain.toml")):
        surfaces.add("rust")
        surfaces.add("toolchain")

    if path.startswith(("engine/", "render/", "services/", "labs/")):
        surfaces.add("rust")

    if path.startswith(("engine/ffi/", "proto/")):
        surfaces.add("ffi_boundary")
        surfaces.add("rust")
        surfaces.add("java-kotlin")

    if path.endswith(".proto") or path.startswith("proto/"):
        surfaces.add("generated_boundary")
        surfaces.add("rust")
        surfaces.add("java-kotlin")

    if path.startswith(("clients/android/", "feature-lab/", "render-core/", "core-math/", "core-model/", "core-simulation/")):
        surfaces.add("android")
        surfaces.add("java-kotlin")
        if path.startswith(("core-math/", "core-model/", "core-simulation/")):
            surfaces.add("rust")

    if path.startswith("gradle/") or path in {
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "clients/android/build.gradle.kts",
        "clients/android/settings.gradle.kts",
    }:
        surfaces.add("gradle")
        surfaces.add("android")
        surfaces.add("java-kotlin")

    if path.startswith(".github/workflows/") or path.startswith(".github/scripts/") or path.startswith(".github/actions/"):
        surfaces.add("ci")

    return surfaces


def codeql_languages_for_surfaces(surfaces: set[str]) -> tuple[str, ...]:
    languages: set[str] = set()
    if "actions" in surfaces:
        languages.add("actions")
    if "c-cpp" in surfaces:
        languages.add("c-cpp")
    if "java-kotlin" in surfaces or "android" in surfaces or "gradle" in surfaces:
        languages.add("java-kotlin")
    if "python" in surfaces:
        languages.add("python")
    if "rust" in surfaces:
        languages.add("rust")
    return tuple(language for language in ALL_CODEQL_LANGUAGES if language in languages)


def summarize_paths(paths: list[str]) -> SurfaceSummary:
    normalized_paths = [normalize_path(path) for path in paths if normalize_path(path)]
    surfaces: set[str] = set()
    for path in normalized_paths:
        surfaces.update(classify_path(path))

    docs_only = bool(normalized_paths) and all(is_doc_path(path) for path in normalized_paths)
    critical_surfaces = {"codeql_policy", "cache", "release", "toolchain"}
    critical = bool(surfaces & critical_surfaces)
    if any(path.startswith(".github/workflows/") for path in normalized_paths):
        critical = True
    if any(path.startswith(".github/actions/") for path in normalized_paths):
        critical = True

    languages = ALL_CODEQL_LANGUAGES if critical else codeql_languages_for_surfaces(surfaces)
    return SurfaceSummary(
        surfaces=frozenset(sorted(surfaces)),
        codeql_languages=languages,
        is_docs_only=docs_only,
        is_codeql_critical=critical,
    )
