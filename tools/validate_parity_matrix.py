#!/usr/bin/env python3

"""Validate the parity matrix schema used by validation-lab."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any


ALLOWED_STATUSES = {"port", "supersede", "ignore", "wip"}
REQUIRED_FIELDS = {"id", "family", "source_oracle", "status", "owner_item", "proof_refs", "notes"}


def _error(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)


def _load_matrix(path: pathlib.Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validate(path: pathlib.Path) -> int:
    try:
        payload = _load_matrix(path)
    except (OSError, json.JSONDecodeError) as err:
        _error(f"cannot load matrix '{path}': {err}")
        return 1

    if not isinstance(payload, dict):
        _error("matrix payload must be a JSON object")
        return 1

    capabilities = payload.get("capabilities")
    if not isinstance(capabilities, list):
        _error("field 'capabilities' is required and must be a list")
        return 1

    errors = 0
    for index, entry in enumerate(capabilities, start=1):
        if not isinstance(entry, dict):
            _error(f"entry #{index}: entry must be an object")
            errors += 1
            continue

        missing = REQUIRED_FIELDS - entry.keys()
        if missing:
            _error(f"entry #{index}: missing required fields: {', '.join(sorted(missing))}")
            errors += 1

        if not isinstance(entry.get("proof_refs"), list):
            _error(f"entry #{index} ({entry.get('id', '<unknown>')}): 'proof_refs' must be a list")
            errors += 1

        status = entry.get("status")
        if status not in ALLOWED_STATUSES:
            _error(
                f"entry #{index} ({entry.get('id', '<unknown>')}): unknown status '{status}', "
                f"allowed={', '.join(sorted(ALLOWED_STATUSES))}"
            )
            errors += 1

        for field in ("id", "family", "source_oracle", "status", "owner_item", "notes"):
            value = entry.get(field)
            if not isinstance(value, str) or not value.strip():
                _error(f"entry #{index}: '{field}' must be a non-empty string")
                errors += 1
                break

    if errors:
        return 1

    print(f"ok: parity matrix validated ({len(capabilities)} entries)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?", default="labs/parity/matrix.json")
    args = parser.parse_args()
    return validate(pathlib.Path(args.path))


if __name__ == "__main__":
    sys.exit(main())
