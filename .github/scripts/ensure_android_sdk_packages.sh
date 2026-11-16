#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "usage: ensure_android_sdk_packages.sh <sdk-package> [<sdk-package> ...]" >&2
  exit 1
fi

phase_start="$(date +%s)"

# Python's standard library bounds the whole SDK manager process group, including
# its Java child. Keep the shell entrypoint shared by the validation/release lanes.
python3 - "$@" <<'PY'
import math
import os
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone


class SetupFailure(Exception):
    def __init__(self, message, status=1):
        super().__init__(message)
        self.status = status


def log(message):
    timestamp = datetime.now(timezone.utc).strftime("%H:%M:%S UTC")
    print(f"[SDK setup {timestamp}] {message}", flush=True)


def duration(name, default, maximum, *, allow_zero=False):
    try:
        value = float(os.environ.get(name, default))
    except ValueError:
        raise SetupFailure(f"{name} must be a number")
    if not math.isfinite(value) or value < 0 or (value == 0 and not allow_zero) or value > maximum:
        raise SetupFailure(f"{name} must be {'nonnegative' if allow_zero else 'positive'} and at most {maximum}")
    return value


def stop_process_group(process, grace):
    # A launcher can exit while its Java child still holds a captured output pipe.
    # Kill the process group, not just the launcher, on either timeout boundary.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        return process.communicate(timeout=grace)[0]
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return process.communicate()[0]


def main():
    requested = list(dict.fromkeys(sys.argv[1:]))
    list_timeout = duration("SOLARLAB_SDK_LIST_TIMEOUT_SECONDS", 30, 120)
    license_timeout = duration("SOLARLAB_SDK_LICENSE_TIMEOUT_SECONDS", 60, 180)
    install_timeout = duration("SOLARLAB_SDK_INSTALL_TIMEOUT_SECONDS", 300, 600)
    retry_delay = duration("SOLARLAB_SDK_RETRY_DELAY_SECONDS", 2, 10, allow_zero=True)
    kill_grace = duration("SOLARLAB_SDK_KILL_GRACE_SECONDS", 2, 5)

    def run_manager(phase, arguments, deadline, *, capture=False, licenses=False):
        for attempt in range(1, 3):
            log(f"{phase}: attempt {attempt}/2, timeout {deadline:g}s")
            started = time.monotonic()
            try:
                process = subprocess.Popen(
                    ["sdkmanager", *arguments],
                    stdin=subprocess.PIPE if licenses else subprocess.DEVNULL,
                    stdout=subprocess.PIPE if capture or licenses else None,
                    start_new_session=True,
                )
            except OSError as error:
                raise SetupFailure(f"Cannot start sdkmanager: {error}", 127)
            try:
                # A finite buffer is ample for the SDK's license prompts and avoids
                # an unbounded `yes` pipeline or masking sdkmanager's exit status.
                output = process.communicate(
                    input=b"y\n" * 1024 if licenses else None,
                    timeout=deadline,
                )[0]
                status = process.returncode
                if status < 0:
                    status = 128 - status
                reason = f"exit status {status}"
            except subprocess.TimeoutExpired:
                output = stop_process_group(process, kill_grace)
                status = 124
                reason = f"timed out after {deadline:g}s"
            if status == 0:
                log(f"{phase}: completed in {time.monotonic() - started:.1f}s")
                return (output or b"").decode("utf-8", errors="replace")
            log(f"{phase}: {reason}")
            if output:
                print("\n".join(output.decode("utf-8", errors="replace").splitlines()[-20:]),
                      file=sys.stderr, flush=True)
            if attempt == 2:
                raise SetupFailure(f"{phase} failed after 2 attempts ({reason})", status)
            log(f"{phase}: retrying in {retry_delay:g}s")
            time.sleep(retry_delay)

    def installed_packages(phase):
        output = run_manager(phase, ["--list_installed"], list_timeout, capture=True)
        return {line.split("|", 1)[0].strip() for line in output.splitlines()}

    installed = installed_packages("Inspect installed SDK packages")
    missing = [package for package in requested if package not in installed]
    for package in requested:
        if package in installed:
            log(f"SDK package already installed: {package}")
    if not missing:
        log("All required SDK packages already installed; skipping license and install operations.")
        return

    log(f"Missing SDK packages: {' '.join(missing)}")
    run_manager("Accept required SDK licenses", ["--licenses"], license_timeout, licenses=True)
    # Stream installation progress directly so downloads/extraction remain visible.
    run_manager("Install missing SDK packages", ["--install", *missing], install_timeout)
    verified = installed_packages("Verify installed SDK packages")
    remaining = [package for package in requested if package not in verified]
    if remaining:
        raise SetupFailure(f"SDK verification failed; requested packages still missing: {' '.join(remaining)}")
    log("All required SDK packages verified installed.")


try:
    main()
except SetupFailure as error:
    log(f"ERROR: {error}")
    sys.exit(error.status)
PY

phase_end="$(date +%s)"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "sdk_setup_seconds=$((phase_end - phase_start))" >> "${GITHUB_OUTPUT}"
fi
