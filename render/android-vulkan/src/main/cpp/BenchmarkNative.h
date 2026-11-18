#pragma once

#include <string>

namespace solarlab::benchmark {
// Each invocation owns an isolated, bounded workload. Neither entry point can
// modify the renderer, the Rust world, or persistent experiments. The separate
// placement JNI call changes only its calling worker and restores on that worker.
std::string Capabilities();
std::string RunCase(const std::string& caseId, int iterations);
} // namespace solarlab::benchmark
