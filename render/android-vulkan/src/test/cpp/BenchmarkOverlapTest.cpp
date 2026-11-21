#include "BenchmarkOverlap.h"
#include <atomic>
#include <cassert>
#include <iostream>
#include <stdexcept>

namespace {
std::atomic<int> gpuMode{0};
std::atomic<int> gpuCalls{0};
}
namespace solarlab::benchmark {
// Host seam for numerical/concurrency/error tests. Real GPU execution is a
// separate Vulkan/device qualification and is never claimed by this stub.
Sample RunGpu(const std::string& id,int iterations) {
    assert(id=="gpu.passive-fp32");
    ++gpuCalls;
    if(gpuMode.load()==2)throw std::runtime_error("synthetic-GPU-exception");
    Sample result;result.iterations=iterations;
    if(gpuMode.load()==1){result.status="SKIP";result.reason="synthetic-unsupported-GPU";return result;}
    result.enabled=true;result.executed=true;result.elapsed=1;
    result.checksum=0x12345678;result.error=1e-7;
    return result;
}
}

int main() {
    using namespace solarlab::benchmark;
    auto serial=RunOverlap("cpu-gpu-serial",2);
    auto overlap=RunOverlap("cpu-gpu-overlap",2);
    assert(serial.status=="PASS"&&overlap.status=="PASS");
    assert(serial.checksum==overlap.checksum);
    assert(serial.executed&&overlap.executed);
    assert(serial.elapsed>0&&overlap.elapsed>0);
    assert(serial.extra.find("\"scheduling_mode\":\"serial\"")!=std::string::npos);
    assert(overlap.extra.find("\"scheduling_mode\":\"concurrent\"")!=std::string::npos);
    assert(overlap.extra.find("\"oracle_verified_sweeps\":2")!=std::string::npos);
    assert(gpuCalls==2);
    gpuMode=1;
    auto skip=RunOverlap("cpu-gpu-overlap",1);
    assert(skip.status=="SKIP"&&!skip.executed);
    assert(skip.reason.find("synthetic-unsupported-GPU")!=std::string::npos);
    gpuMode=2;
    auto failure=RunOverlap("cpu-gpu-overlap",1);
    assert(failure.status=="FAIL");
    assert(failure.reason.find("synthetic-GPU-exception")!=std::string::npos);
    const int calls=gpuCalls.load();
    assert(RunOverlap("cpu-gpu-serial",0).status=="FAIL");
    assert(RunOverlap("cpu-gpu-serial",33).status=="FAIL");
    assert(RunOverlap("unknown",1).status=="SKIP");
    assert(gpuCalls==calls);
    GpuInventory inventory;
    assert(!OverlapCases(inventory)[0].supported);
    inventory.cases.push_back({"gpu.passive-fp32","","","","",true,true});
    const auto cases=OverlapCases(inventory);
    assert(cases.size()==2&&cases[0].supported&&cases[1].supported);
    assert(cases[1].baseline=="cpu-gpu-serial");
    std::cout<<serial.Json("cpu-gpu-serial")<<'\n'<<overlap.Json("cpu-gpu-overlap")<<'\n';
}
