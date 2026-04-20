#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


API_VERSION = "2022-11-28"
USER_AGENT = "solar-gravity-lab-interactive-session/1.0"


class GitHubApiClient:
    def __init__(self, repository: str, token: str) -> None:
        self.repository = repository
        self.token = token

    def _request(self, url: str, *, accept: str = "application/vnd.github+json") -> urllib.request.Request:
        request = urllib.request.Request(url)
        request.add_header("Accept", accept)
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("User-Agent", USER_AGENT)
        request.add_header("X-GitHub-Api-Version", API_VERSION)
        return request

    def get_json(self, path_or_url: str) -> dict:
        url = (
            path_or_url
            if path_or_url.startswith("https://")
            else f"https://api.github.com{path_or_url}"
        )
        with urllib.request.urlopen(self._request(url)) as response:
            return json.load(response)

    def get_bytes(self, path_or_url: str) -> bytes:
        url = (
            path_or_url
            if path_or_url.startswith("https://")
            else f"https://api.github.com{path_or_url}"
        )
        with urllib.request.urlopen(
            self._request(url, accept="application/octet-stream")
        ) as response:
            return response.read()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Resolve and download an interactive Android build artifact bundle."
    )
    parser.add_argument("--repository", required=True)
    parser.add_argument("--artifact-name", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--workflow-file", default="interactive-android-build.yml")
    parser.add_argument("--workflow-run-id")
    parser.add_argument("--commit-sha")
    parser.add_argument("--github-token-env", default="GITHUB_TOKEN")
    parser.add_argument("--github-output")
    return parser.parse_args()


def require_token(env_name: str) -> str:
    token = os.environ.get(env_name, "").strip()
    if not token:
        raise SystemExit(f"Missing GitHub token in env var: {env_name}")
    return token


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_outputs(path: Path | None, payload: dict[str, str]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for key, value in payload.items():
            handle.write(f"{key}={value}\n")


def find_artifact(artifacts: list[dict], artifact_name: str) -> dict | None:
    for artifact in artifacts:
        if artifact.get("name") == artifact_name and not artifact.get("expired", False):
            return artifact
    return None


def resolve_run(client: GitHubApiClient, workflow_file: str, artifact_name: str, commit_sha: str) -> dict:
    encoded_workflow = urllib.parse.quote(workflow_file, safe="")
    runs = client.get_json(
        f"/repos/{client.repository}/actions/workflows/{encoded_workflow}/runs"
        "?event=workflow_dispatch&status=success&per_page=100"
    ).get("workflow_runs", [])
    for run in runs:
        if run.get("head_sha") != commit_sha:
            continue
        artifacts = client.get_json(
            f"/repos/{client.repository}/actions/runs/{run['id']}/artifacts?per_page=100"
        ).get("artifacts", [])
        artifact = find_artifact(artifacts, artifact_name)
        if artifact is not None:
            return {
                "run": run,
                "artifact": artifact,
            }
    raise SystemExit(
        "No successful interactive build artifact matched "
        f"commit {commit_sha} and artifact {artifact_name}"
    )


def resolve_run_artifact(client: GitHubApiClient, run_id: str, artifact_name: str) -> dict:
    run = client.get_json(f"/repos/{client.repository}/actions/runs/{run_id}")
    artifacts = client.get_json(
        f"/repos/{client.repository}/actions/runs/{run_id}/artifacts?per_page=100"
    ).get("artifacts", [])
    artifact = find_artifact(artifacts, artifact_name)
    if artifact is None:
        raise SystemExit(f"Artifact {artifact_name} not found on workflow run {run_id}")
    return {
        "run": run,
        "artifact": artifact,
    }


def extract_bundle(bundle_zip: bytes, output_dir: Path) -> None:
    shutil.rmtree(output_dir, ignore_errors=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / "artifact.zip"
    archive_path.write_bytes(bundle_zip)
    with zipfile.ZipFile(archive_path) as archive:
        archive.extractall(output_dir)
    archive_path.unlink()


def find_single_file(root: Path, filename: str) -> Path:
    candidates = list(root.rglob(filename))
    if len(candidates) != 1:
        raise SystemExit(
            f"Expected exactly one {filename} under {root}, found {len(candidates)}"
        )
    return candidates[0]


def main() -> None:
    args = parse_args()
    token = require_token(args.github_token_env)
    client = GitHubApiClient(args.repository, token)

    if args.workflow_run_id:
        resolved = resolve_run_artifact(client, args.workflow_run_id, args.artifact_name)
    else:
        if not args.commit_sha:
            raise SystemExit("Either --workflow-run-id or --commit-sha is required")
        resolved = resolve_run(client, args.workflow_file, args.artifact_name, args.commit_sha)

    artifact = resolved["artifact"]
    run = resolved["run"]
    bundle_dir = Path(args.output_dir).resolve()
    extract_bundle(client.get_bytes(artifact["archive_download_url"]), bundle_dir)

    manifest_path = find_single_file(bundle_dir, "interactive-build-manifest.json")
    manifest = json.loads(manifest_path.read_text())
    apk_path = find_single_file(bundle_dir, manifest["apk_filename"])
    apk_sha256 = sha256_file(apk_path)
    expected_sha256 = manifest.get("apk_sha256")
    if apk_sha256 != expected_sha256:
        raise SystemExit(
            f"SHA mismatch for {apk_path}: expected {expected_sha256}, got {apk_sha256}"
        )

    payload = {
        "artifact_name": artifact["name"],
        "build_run_id": str(run["id"]),
        "build_run_attempt": str(run.get("run_attempt", "")),
        "build_commit_sha": manifest.get("commit_sha", run.get("head_sha", "")),
        "bundle_dir": str(bundle_dir),
        "manifest_path": str(manifest_path),
        "apk_path": str(apk_path),
        "apk_sha256": apk_sha256,
    }
    write_outputs(Path(args.github_output) if args.github_output else None, payload)
    print(json.dumps(payload, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
