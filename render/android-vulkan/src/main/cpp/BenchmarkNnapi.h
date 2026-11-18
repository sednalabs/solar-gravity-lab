#pragma once
#include "BenchmarkUtil.h"

namespace solarlab::benchmark {
GpuInventory NnapiCapabilities();
Sample RunNnapi(const std::string& id, int iterations);
}
