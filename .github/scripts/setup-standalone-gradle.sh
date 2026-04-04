#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:-$PWD}"
wrapper_props="${repo_root}/gradle/wrapper/gradle-wrapper.properties"
default_url='https://services.gradle.org/distributions/gradle-8.13-bin.zip'

dist_url="$default_url"
if [[ -f "$wrapper_props" ]]; then
  parsed_url="$(
    sed -n 's/^[[:space:]]*distributionUrl[[:space:]]*=[[:space:]]*//p' "$wrapper_props" |
      tr -d '\r' |
      tail -n 1
  )"
  if [[ -n "$parsed_url" ]]; then
    dist_url="${parsed_url//\\:/:}"
  fi
fi

archive_name="${dist_url##*/}"
cache_root="${STANDALONE_GRADLE_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/standalone-gradle}"
case "$cache_root" in
  "~"|"~/")
    cache_root="$HOME"
    ;;
  "~/*")
    cache_root="$HOME/${cache_root#\~/}"
    ;;
esac
archive_dir="${archive_name%.zip}"
if [[ "$archive_dir" == "$archive_name" ]]; then
  archive_dir="${archive_name%.tar.gz}"
fi

install_root="${cache_root}/${archive_dir}"
archive_path="${install_root}/${archive_name}"
extract_root="${install_root}/extract"

mkdir -p "$install_root" "$extract_root"

if [[ ! -f "$archive_path" ]]; then
  echo "Downloading ${dist_url}" >&2
  curl -fsSL --retry 3 --retry-connrefused "$dist_url" -o "$archive_path"
fi

gradle_home="$(find "$extract_root" -maxdepth 1 -mindepth 1 -type d -name 'gradle-*' | sort | tail -n 1 || true)"
if [[ -z "$gradle_home" || ! -x "$gradle_home/bin/gradle" ]]; then
  rm -rf "$extract_root"
  mkdir -p "$extract_root"
  unzip -q -o "$archive_path" -d "$extract_root"
  gradle_home="$(find "$extract_root" -maxdepth 1 -mindepth 1 -type d -name 'gradle-*' | sort | tail -n 1 || true)"
fi

if [[ -z "$gradle_home" || ! -x "$gradle_home/bin/gradle" ]]; then
  echo "Failed to prepare standalone Gradle from ${dist_url}" >&2
  exit 1
fi

gradle_bin="${gradle_home}/bin/gradle"
echo "Prepared standalone Gradle at ${gradle_bin}" >&2

if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "${gradle_home}/bin" >> "${GITHUB_PATH}"
fi

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'STANDALONE_GRADLE=%s\n' "${gradle_bin}" >> "${GITHUB_ENV}"
fi

printf '%s\n' "${gradle_bin}"
