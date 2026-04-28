#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys


RELEASE_TRAILER_RE = re.compile(r"^SolarLab-Release:\s*(?P<version>[^\s]+)\s*$")
VERSION_CODE_TRAILER_RE = re.compile(r"^SolarLab-Version-Code:\s*(?P<version_code>\S+)\s*$")
CHANNEL_TRAILER_RE = re.compile(r"^SolarLab-Release-Channel:\s*(?P<channel>[A-Za-z0-9_.-]+)\s*$")
BUILD_VARIANT_TRAILER_RE = re.compile(r"^SolarLab-Build-Variant:\s*(?P<variant>[A-Za-z0-9_.-]+)\s*$")
FULL_SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
SEMVER_RE = re.compile(
    r"^(?P<major>0|[1-9][0-9]*)\."
    r"(?P<minor>0|[1-9][0-9]*)\."
    r"(?P<patch>0|[1-9][0-9]*)"
    r"(?:-(?P<prerelease>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


class RequestError(ValueError):
    pass


def parse_unique_trailer(message: str, pattern: re.Pattern[str], label: str) -> str | None:
    values: list[str] = []
    for line in message.splitlines():
        match = pattern.match(line.strip())
        if match:
            values.append(match.group(1))

    if len(values) > 1:
        raise RequestError(f"commit has more than one {label} trailer")

    return values[0] if values else None


def require_single_line(value: str, label: str) -> str:
    if "\n" in value or "\r" in value:
        raise RequestError(f"{label} must be a single-line value")
    return value


def validate_ref(value: str) -> str:
    value = require_single_line(value.strip(), "ref")
    if any(character.isspace() for character in value):
        raise RequestError("ref must not contain whitespace")
    return value


def validate_semver(version_name: str) -> re.Match[str]:
    require_single_line(version_name, "SolarLab-Release")
    match = SEMVER_RE.fullmatch(version_name)
    if not match:
        raise RequestError(
            "SolarLab-Release must be an ordinary semver version like "
            "0.1.0 or 0.1.1-alpha.1"
        )
    return match


def validate_version_code(version_code: str) -> str:
    version_code = require_single_line(version_code.strip(), "SolarLab-Version-Code")
    if version_code and not re.fullmatch(r"[1-9][0-9]*", version_code):
        raise RequestError("SolarLab-Version-Code must be a positive integer")
    return version_code


def infer_channel(version_match: re.Match[str], explicit_channel: str | None) -> str:
    inferred = "prerelease" if version_match.group("prerelease") else "stable"
    if explicit_channel is None:
        return inferred

    channel = explicit_channel.lower()
    if channel not in {"stable", "prerelease"}:
        raise RequestError("SolarLab-Release-Channel must be either 'stable' or 'prerelease'")
    if channel != inferred:
        raise RequestError(
            f"SolarLab-Release-Channel={channel} does not match version channel {inferred}"
        )
    return channel


def validate_manual_channel(value: str) -> str:
    channel = require_single_line(value.strip(), "release_channel").lower()
    if channel == "":
        return "auto"
    if channel not in {"auto", "stable", "prerelease"}:
        raise RequestError("release_channel must be 'auto', 'stable', or 'prerelease'")
    return channel


def infer_build_variant(channel: str, explicit_variant: str | None) -> str:
    if explicit_variant is None:
        return "prerelease" if channel == "prerelease" else "release"

    variant = explicit_variant.lower()
    if variant not in {"prerelease", "release"}:
        raise RequestError("SolarLab-Build-Variant must be either 'prerelease' or 'release'")
    return variant


def validate_manual_build_variant(value: str) -> str:
    variant = require_single_line(value.strip(), "build_variant").lower()
    if variant == "":
        return "prerelease"
    if variant not in {"prerelease", "release"}:
        raise RequestError("build_variant must be either 'prerelease' or 'release'")
    return variant


def validate_publish_release(value: str) -> str:
    normalized = require_single_line(value.strip(), "publish_release").lower()
    if normalized not in {"true", "false"}:
        raise RequestError("publish_release must be either 'true' or 'false'")
    return normalized


def branch_name(event_ref: str) -> str:
    return event_ref.removeprefix("refs/heads/")


def resolve_request(args: argparse.Namespace) -> dict[str, str]:
    event_name = args.event_name
    event_ref = args.event_ref
    event_sha = args.event_sha
    event_after = args.event_after
    input_ref = args.input_ref.strip()

    if event_name == "workflow_dispatch":
        checkout_ref = validate_ref(input_ref) if input_ref else validate_ref(event_sha)
        version_name = require_single_line(args.input_version_name.strip(), "version_name")
        if version_name:
            validate_semver(version_name)
        version_code = validate_version_code(args.input_version_code)
        return {
            "release_requested": "true",
            "reason": "workflow_dispatch",
            "checkout_ref": checkout_ref,
            "target_sha": checkout_ref if FULL_SHA_RE.fullmatch(checkout_ref) else validate_ref(event_sha),
            "version_name": version_name,
            "version_code": version_code,
            "release_channel": validate_manual_channel(args.input_release_channel),
            "build_variant": validate_manual_build_variant(args.input_build_variant),
            "publish_release": validate_publish_release(args.input_publish_release),
        }

    if event_name != "push":
        return {
            "release_requested": "false",
            "reason": "unsupported_event",
            "checkout_ref": "",
            "target_sha": "",
            "version_name": "",
            "version_code": "",
            "release_channel": "",
            "build_variant": "",
            "publish_release": "false",
        }

    if not event_after or re.fullmatch(r"0+", event_after):
        return {
            "release_requested": "false",
            "reason": "deleted_ref",
            "checkout_ref": "",
            "target_sha": "",
            "version_name": "",
            "version_code": "",
            "release_channel": "",
            "build_variant": "",
            "publish_release": "false",
        }

    ref_name = branch_name(event_ref)
    if ref_name.startswith("release/"):
        return {
            "release_requested": "true",
            "reason": "release_branch_push",
            "checkout_ref": ref_name,
            "target_sha": event_after,
            "version_name": "",
            "version_code": "",
            "release_channel": "auto",
            "build_variant": "prerelease",
            "publish_release": "true",
        }

    if event_ref != "refs/heads/main":
        return {
            "release_requested": "false",
            "reason": "unsupported_ref",
            "checkout_ref": "",
            "target_sha": "",
            "version_name": "",
            "version_code": "",
            "release_channel": "",
            "build_variant": "",
            "publish_release": "false",
        }

    version_name = parse_unique_trailer(args.head_message, RELEASE_TRAILER_RE, "SolarLab-Release")
    if version_name is None:
        return {
            "release_requested": "false",
            "reason": "missing_solarlab_release_trailer",
            "checkout_ref": event_after,
            "target_sha": event_after,
            "version_name": "",
            "version_code": "",
            "release_channel": "",
            "build_variant": "",
            "publish_release": "false",
        }

    version_match = validate_semver(version_name)
    version_code = parse_unique_trailer(
        args.head_message,
        VERSION_CODE_TRAILER_RE,
        "SolarLab-Version-Code",
    )
    version_code = validate_version_code(version_code or "")
    channel = infer_channel(
        version_match,
        parse_unique_trailer(args.head_message, CHANNEL_TRAILER_RE, "SolarLab-Release-Channel"),
    )
    build_variant = infer_build_variant(
        channel,
        parse_unique_trailer(args.head_message, BUILD_VARIANT_TRAILER_RE, "SolarLab-Build-Variant"),
    )

    return {
        "release_requested": "true",
        "reason": "release_trailer",
        "checkout_ref": event_after,
        "target_sha": event_after,
        "version_name": version_name,
        "version_code": version_code,
        "release_channel": channel,
        "build_variant": build_variant,
        "publish_release": "true",
    }


def write_outputs(outputs: dict[str, str]) -> None:
    lines = [f"{key}={value}" for key, value in outputs.items()]
    print("\n".join(lines))


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve prerelease-apk release routing inputs.")
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--event-ref", default="")
    parser.add_argument("--event-sha", default="")
    parser.add_argument("--event-after", default="")
    parser.add_argument("--head-message", default="")
    parser.add_argument("--input-ref", default="")
    parser.add_argument("--input-version-name", default="")
    parser.add_argument("--input-version-code", default="")
    parser.add_argument("--input-release-channel", default="")
    parser.add_argument("--input-build-variant", default="")
    parser.add_argument("--input-publish-release", default="false")
    args = parser.parse_args()

    try:
        outputs = resolve_request(args)
    except RequestError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    write_outputs(outputs)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
