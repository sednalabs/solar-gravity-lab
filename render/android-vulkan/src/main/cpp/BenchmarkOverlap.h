#pragma once
#include "BenchmarkUtil.h"

namespace solarlab::benchmark {
// Independent CPU/GPU jobs; no access to a production renderer or physics world.
std::vector<CaseInfo> OverlapCases(const GpuInventory& gpu);
Sample RunOverlap(const std::string& id,int iterations);
} // namespace solarlab::benchmark
