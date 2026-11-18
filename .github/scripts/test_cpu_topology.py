from __future__ import annotations

import unittest

from cpu_topology import cache_bytes, parse_cpu_list, parse_snapshot, topology_report


class CpuTopologyTests(unittest.TestCase):
    def test_discovered_two_plus_six_topology_uses_only_online_allowed_cpus(self) -> None:
        prefix = "/sys/devices/system/cpu"
        values = {
            prefix + "/online": "20-25,27",  # CPU 26 offline.
            "/proc/self/status:Cpus_allowed_list": "22-23,26-27",
        }
        for cpu in range(20, 28):
            base = f"{prefix}/cpu{cpu}"
            values.update({
                base + "/cpu_capacity": "1024" if cpu >= 26 else "768",
                base + "/cpufreq/cpuinfo_max_freq": "4300000" if cpu >= 26 else "3500000",
                base + "/cache/index2/level": "2",
                base + "/cache/index2/type": "Unified",
                base + "/cache/index2/size": "12M",
                base + "/cache/index2/coherency_line_size": "64",
                base + "/cache/index2/shared_cpu_list": "26-27" if cpu >= 26 else "20-25",
            })
        report = topology_report(values, affinity_scope="fixture")
        self.assertEqual(report["collector_eligible_cpus"], [22, 23, 27])
        self.assertEqual(len(report["caches"]), 2)
        masks = [entry["cpus"] for entry in report["benchmark_placements"]]
        self.assertIn([22, 23], masks)
        self.assertIn([27], masks)
        self.assertTrue(all(set(mask) <= {22, 23, 27} for mask in masks))

    def test_unknown_affinity_does_not_invent_legal_placement(self) -> None:
        report = topology_report({"/sys/devices/system/cpu/online": "0-7"}, affinity_scope="adb-shell")
        self.assertIsNone(report["collector_allowed_cpus"])
        self.assertEqual(report["benchmark_placements"], [])
        self.assertEqual(report["affinity_scope"], "adb-shell")

    def test_bounded_cpu_ranges_and_cache_units(self) -> None:
        self.assertEqual(parse_cpu_list("0-2,7,2"), [0, 1, 2, 7])
        for value in (None, "", "2-1", "-1", "0-99999999", "0;id"):
            self.assertIsNone(parse_cpu_list(value))
        self.assertEqual(cache_bytes("12M"), 12 * 1024 * 1024)
        self.assertEqual(cache_bytes("64K"), 64 * 1024)
        self.assertIsNone(cache_bytes("unknown"))
        self.assertEqual(parse_snapshot("/path\t42\npermission denied\n"), {"/path": "42"})


if __name__ == "__main__":
    unittest.main()
