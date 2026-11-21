#pragma once
#include "BenchmarkUtil.h"
namespace solarlab::benchmark {
GpuInventory OpenClCapabilities();
Sample RunOpenCl(const std::string &id, int iterations);
} // namespace solarlab::benchmark
