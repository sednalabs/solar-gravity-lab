#!/usr/bin/env python3
"""Exercise the APK's actual native cases on a host. This proves no phone speed."""
import json
import subprocess
import sys


def run(binary: str, *args: str) -> dict:
    # CTest supplies its locally built $<TARGET_FILE:...>. This CLI deliberately
    # executes that caller-selected trusted binary; no report/network data can
    # choose it, and the argument vector is never passed through a shell.
    output = subprocess.check_output([binary, *args], text=True, timeout=90)
    return json.loads(output, parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def main() -> None:
    binary = sys.argv[1]
    inventory = run(binary)
    cases = inventory["cases"]
    assert len({case["id"] for case in cases}) == len(cases), "duplicate case IDs"
    counts = {"PASS": 0, "SKIP": 0}
    for case in cases:
        assert case["enabled"] is False and case["executed"] is False, case
        expected_comparison = ("accuracy_cost_experiment" if case["id"] in
                               {"gpu.visual-fp16", "gpu.passive-fp64", "opencl.passive-fp64"}
                               else "equivalent_kernel")
        assert case["comparison_kind"] == expected_comparison, case
        result = run(binary, case["id"], "3")
        assert result["case_id"] == case["id"], result
        assert result["comparison_kind"] == expected_comparison, result
        assert result["status"] in counts, result
        assert result["authoritative_world_modified"] is False, result
        if result["status"] == "PASS":
            assert result["implemented"] and result["enabled"] and result["executed"], result
            assert result["elapsed_ns"] > 0 and 1 <= result["iterations"] <= 3, result
        if case["status"] != "runnable":
            assert result["status"] == "SKIP", result
        counts[result["status"]] += 1
        print(case["id"], result["status"], result["reason"])
    unknown = run(binary, "gpu.unknown", "1")
    assert unknown["status"] == "SKIP" and not unknown["implemented"], unknown
    bounded = run(binary, "native.memcpy", "-1")
    assert bounded["status"] == "PASS" and bounded["iterations"] == 1, bounded
    print(json.dumps({"status": "PASS", "counts": counts, "phone_performance_qualified": False}))


if __name__ == "__main__":
    main()
