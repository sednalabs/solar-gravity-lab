"""Read-only Linux/Android topology and legal benchmark placement candidates.

Missing sysfs files remain unknown. CPU numbers, cluster sizes and cache sizes
are never inferred from a marketing name. Android shell affinity describes the
collector, not the app's foreground cpuset.
"""

from __future__ import annotations

import os
import re
from pathlib import Path


CPU_FIELDS = (
    "online", "cpu_capacity", "cpufreq/cpuinfo_max_freq",
    "topology/cluster_id", "topology/physical_package_id", "topology/core_id",
    "topology/core_siblings_list", "topology/thread_siblings_list",
)
CACHE_FIELDS = ("level", "type", "size", "coherency_line_size", "shared_cpu_list")

# Constant script, without user interpolation. Denied/missing optional sysfs
# files are omitted. The ADB serial is supplied separately through the environment.
ANDROID_TOPOLOGY_COMMAND = r'''
for path in /sys/devices/system/cpu/online /sys/devices/system/cpu/present; do
  if [ -r "$path" ]; then printf '%s\t' "$path"; cat "$path"; fi
done
awk '/^Cpus_allowed_list:/ { print "/proc/self/status:Cpus_allowed_list\t" $2 }' /proc/self/status
for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
  for field in online cpu_capacity cpufreq/cpuinfo_max_freq topology/cluster_id topology/physical_package_id topology/core_id topology/core_siblings_list topology/thread_siblings_list; do
    path="$cpu/$field"
    if [ -r "$path" ]; then printf '%s\t' "$path"; cat "$path"; fi
  done
  for cache in "$cpu"/cache/index[0-9]*; do
    for field in level type size coherency_line_size shared_cpu_list; do
      path="$cache/$field"
      if [ -r "$path" ]; then printf '%s\t' "$path"; cat "$path"; fi
    done
  done
done
'''.strip()


def parse_cpu_list(value: str | None) -> list[int] | None:
    if value is None:
        return None
    cpus: set[int] = set()
    for group in value.strip().split(","):
        match = re.fullmatch(r"(\d+)(?:-(\d+))?", group)
        if match is None:
            return None
        start = int(match[1])
        end = int(match[2]) if match[2] else start
        if start > end or end >= 4096:
            return None
        cpus.update(range(start, end + 1))
    return sorted(cpus)


def positive_int(value: str | None) -> int | None:
    try:
        number = int(value or "")
        return number if number > 0 else None
    except ValueError:
        return None


def cache_bytes(value: str | None) -> int | None:
    match = re.fullmatch(r"(\d+)([KMG]?)", (value or "").strip(), re.IGNORECASE)
    if match is None:
        return None
    return int(match[1]) * 1024 ** ("KMG".find(match[2].upper()) + 1 if match[2] else 0)


def parse_snapshot(text: str) -> dict[str, str]:
    return {path: value.strip() for line in text.splitlines()
            if "\t" in line for path, value in [line.split("\t", 1)]}


def host_snapshot(root: Path = Path("/")) -> dict[str, str]:
    values: dict[str, str] = {}

    def read(relative: str) -> None:
        try:
            values["/" + relative] = (root / relative).read_text(encoding="utf-8").strip()
        except (OSError, UnicodeError):
            pass

    prefix = "sys/devices/system/cpu"
    read(prefix + "/online")
    read(prefix + "/present")
    for cpu in (root / prefix).glob("cpu[0-9]*"):
        if re.fullmatch(r"cpu\d+", cpu.name) is None:
            continue
        for field in CPU_FIELDS:
            read(f"{prefix}/{cpu.name}/{field}")
        for cache in (cpu / "cache").glob("index[0-9]*"):
            for field in CACHE_FIELDS:
                read(f"{prefix}/{cpu.name}/cache/{cache.name}/{field}")
    if root == Path("/"):
        try:
            values["/proc/self/status:Cpus_allowed_list"] = ",".join(
                str(cpu) for cpu in sorted(os.sched_getaffinity(0)))
        except (AttributeError, OSError):
            pass
    return values


def topology_report(values: dict[str, str], *, affinity_scope: str) -> dict[str, object]:
    prefix = "/sys/devices/system/cpu"
    online = parse_cpu_list(values.get(prefix + "/online"))
    allowed = parse_cpu_list(values.get("/proc/self/status:Cpus_allowed_list"))
    known_cpus = sorted({int(match[1]) for path in values
                         if (match := re.match(re.escape(prefix) + r"/cpu(\d+)/", path))})
    # Unknown affinity is not permission to fabricate a legal placement mask.
    eligible = sorted(set(online) & set(allowed)) if online is not None and allowed is not None else []
    cpus = []
    caches: dict[tuple[object, ...], dict[str, object]] = {}
    for cpu in sorted(set(known_cpus) | set(online or [])):
        base = f"{prefix}/cpu{cpu}"
        cpus.append({
            "cpu": cpu,
            "online": cpu in online if online is not None else None,
            "allowed_for_collector": cpu in allowed if allowed is not None else None,
            "capacity": positive_int(values.get(base + "/cpu_capacity")),
            "max_frequency_khz": positive_int(values.get(base + "/cpufreq/cpuinfo_max_freq")),
            "cluster_id": values.get(base + "/topology/cluster_id"),
            "package_id": values.get(base + "/topology/physical_package_id"),
            "core_id": values.get(base + "/topology/core_id"),
            "core_siblings": parse_cpu_list(values.get(base + "/topology/core_siblings_list")),
            "thread_siblings": parse_cpu_list(values.get(base + "/topology/thread_siblings_list")),
        })
        indices = sorted({match[1] for path in values
                          if (match := re.fullmatch(re.escape(base) + r"/cache/(index\d+)/level", path))})
        for index in indices:
            path = base + "/cache/" + index
            level = positive_int(values.get(path + "/level"))
            kind = values.get(path + "/type")
            shared = parse_cpu_list(values.get(path + "/shared_cpu_list"))
            # Unknown shared_cpu_list must not accidentally merge distinct caches.
            key = (level, kind, tuple(shared) if shared is not None else (cpu, index))
            caches[key] = {
                "level": level, "type": kind, "shared_cpus": shared,
                "size_bytes": cache_bytes(values.get(path + "/size")),
                "line_bytes": positive_int(values.get(path + "/coherency_line_size")),
                "source": path,
            }
    placements: list[dict[str, object]] = []
    seen: set[tuple[int, ...]] = set()

    def placement(label: str, cpulist: list[int]) -> None:
        legal = tuple(sorted(set(cpulist) & set(eligible)))
        if legal and legal not in seen:
            seen.add(legal)
            placements.append({"label": label, "cpus": list(legal)})

    placement("all-allowed", eligible)
    for cache in caches.values():
        if cache["level"] == 2 and cache["type"] in {"Data", "Unified"}:
            placement("shared-l2", cache["shared_cpus"] or [])
    for field in ("capacity", "max_frequency_khz"):
        scored = [cpu for cpu in cpus if cpu["cpu"] in eligible and cpu[field] is not None]
        if scored:
            best = max(cpu[field] for cpu in scored)
            fastest = [cpu["cpu"] for cpu in scored if cpu[field] == best]
            placement("highest-" + field, fastest)
            placement("single-highest-" + field, fastest[:1])
            break
    if eligible:
        placement("single-allowed", eligible[:1])
    return {
        "affinity_scope": affinity_scope,
        "online_cpus": online,
        "collector_allowed_cpus": allowed,
        "collector_eligible_cpus": eligible,
        "cpus": cpus,
        "caches": list(caches.values()),
        "benchmark_placements": placements,
        "application_affinity_policy": "OS/ADPF managed; benchmark masks do not pin application threads",
        "limitations": [
            "Absent sysfs capacity, frequency or cache data remains unknown.",
            "Maximum frequency is a placement experiment, not measured per-core throughput.",
            "Recheck the target process cpuset before applying a benchmark mask.",
        ],
    }
