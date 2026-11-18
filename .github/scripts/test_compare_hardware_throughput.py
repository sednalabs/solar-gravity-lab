from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from compare_hardware_throughput import EXPECTED_SEGMENT_STEPS, EXPECTED_WORKLOADS, compare


class PairedThroughputTests(unittest.TestCase):
    def fixture(self, directory: Path, workloads: set[str] | None = None) -> None:
        workloads = EXPECTED_WORKLOADS if workloads is None else workloads
        (directory / "baseline-sha.txt").write_text("a" * 40)
        (directory / "candidate-sha.txt").write_text("b" * 40)
        for affinity in ("full", "one-cpu"):
            for variant in ("baseline", "candidate"):
                for repetition in (0, 1):
                    environment = {"kind": "environment", "arch": "aarch64",
                                   "available_workers": 4 if affinity == "full" else 1,
                                   "samples": 11, "steps": 64}
                    rows = [environment]
                    for workload in sorted(workloads):
                        for backend in ("ReferenceScalar", "SimdArm64"):
                            rows.append({
                                "kind": "measurement", "workload": workload, "requested": backend,
                                "bodies": 365, "sources": 15, "active_workers": 1,
                                "path": "scalar.reference" if backend == "ReferenceScalar" else "simd.arm64.neon-f64-passive-batch",
                                "median_us": 100 if variant == "baseline" else 80,
                                "p90_us": 120 if variant == "baseline" else 90,
                                "max_abs_oracle_error": 0.0, "max_relative_oracle_error": 0.0,
                            })
                    self.write_rows(directory / f"{affinity}-{variant}-{repetition}.ndjson", rows)

    @staticmethod
    def write_rows(path: Path, rows: list[dict]) -> None:
        path.write_text("".join(json.dumps(row) + "\n" for row in rows))

    def test_comparison_retains_raw_control_and_tail_ratios(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            result = compare(directory)
            self.assertEqual(len(result["comparisons"]), len(EXPECTED_WORKLOADS) * 4)
            row = result["comparisons"][0]
            self.assertEqual(row["candidate_over_baseline"], 0.8)
            self.assertEqual(row["candidate_p90_over_baseline"], 0.75)
            self.assertEqual(row["scalar_control_ratio"], 0.8)
            self.assertEqual(row["normalised_ratio"], 1.0)

    def test_explicit_dense_matrix_does_not_relax_the_default_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            dense = {"dense-192", "dense-256"}
            self.fixture(directory, workloads=dense)
            result = compare(directory, workloads=dense)
            self.assertEqual(len(result["comparisons"]), 8)
            self.assertEqual(result["expected_workloads"], sorted(dense))
            with self.assertRaisesRegex(ValueError, "incomplete"):
                compare(directory)
            path = directory / "one-cpu-candidate-1.ndjson"
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.write_rows(path, rows[:-1])
            with self.assertRaisesRegex(ValueError, "incomplete"):
                compare(directory, workloads=dense)

    def test_rejects_absolute_error_fallback_wrong_fixture_and_invalid_tail(self) -> None:
        for mutation in (
            {"max_abs_oracle_error": 1.0},
            {"path": "scalar.reference"},
            {"sources": 14},
            {"p90_us": 1.0},
            {"median_us": float("nan")},
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                self.fixture(directory)
                path = directory / "full-candidate-0.ndjson"
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                rows[2].update(mutation)  # First requested native Arm measurement.
                self.write_rows(path, rows)
                with self.assertRaises(ValueError):
                    compare(directory)

    def test_rejects_equally_incomplete_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            for path in directory.glob("*.ndjson"):
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                self.write_rows(path, rows[:-2])
            with self.assertRaisesRegex(ValueError, "incomplete"):
                compare(directory)

    def test_segment_length_report_is_explicit_and_cannot_mix_lengths(self) -> None:
        for steps in EXPECTED_SEGMENT_STEPS:
            with self.subTest(steps=steps), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                self.fixture(directory)
                for path in directory.glob("*.ndjson"):
                    rows = [json.loads(line) for line in path.read_text().splitlines()]
                    rows[0]["steps"] = steps
                    self.write_rows(path, rows)
                self.assertEqual(compare(directory)["measurement_policy"]["steps"], steps)
                for path in directory.glob("one-cpu-*.ndjson"):
                    rows = [json.loads(line) for line in path.read_text().splitlines()]
                    rows[0]["steps"] = 1 if steps != 1 else 64
                    self.write_rows(path, rows)
                with self.assertRaisesRegex(ValueError, "measurement policy"):
                    compare(directory)


if __name__ == "__main__":
    unittest.main()
