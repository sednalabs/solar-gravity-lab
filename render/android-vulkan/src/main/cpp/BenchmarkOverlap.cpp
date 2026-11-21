#include "BenchmarkOverlap.h"
#include <algorithm>
#include <array>
#include <exception>
#include <limits>
#include <stdexcept>
#include <thread>

namespace solarlab::benchmark {
namespace {
constexpr uint32_t kTargets=16387;
constexpr std::array<std::array<double,4>,3> kSources{{
    {0.5,-0.25,0.125,1.0}, {-0.75,0.5,-0.25,0.0625}, {0.125,0.75,0.5,0.015625}
}};
using Position=std::array<double,3>;
struct JoinOnExit {
    std::thread& worker;
    ~JoinOnExit() {if(worker.joinable())worker.join();}
    JoinOnExit(const JoinOnExit&)=delete;
    JoinOnExit& operator=(const JoinOnExit&)=delete;
    explicit JoinOnExit(std::thread& value):worker(value){}
};

Position Target(uint32_t index,int pass) {
    // Exact binary fractions. The pass-dependent shift makes every requested
    // force sweep distinct; a digest of every sweep makes all work observable.
    return {static_cast<double>(index%251U)/128.0-1.0+static_cast<double>(pass)/16384.0,
        static_cast<double>((index*17U)%253U)/128.0-1.0,
        static_cast<double>((index*31U)%257U)/128.0-1.0};
}

Sample RunCpuForce(int iterations) {
    Sample sample;sample.iterations=iterations;sample.enabled=true;
    sample.scope="sum-of-fp64-force-and-per-sweep-output-digest;excludes-allocation-and-reference";
    const auto setup=Clock::now();
    std::vector<Position> output(kTargets);
    std::vector<uint64_t> hashes(static_cast<size_t>(iterations));
    const uint64_t setupNs=Nanos(setup);
    uint64_t oracleNs=0;
    for(int pass=0;pass<iterations;++pass) {
        const auto kernel=Clock::now();
        for(uint32_t index=0;index<kTargets;++index) {
            const auto target=Target(index,pass);
            Position acceleration{};
            for(const auto& source:kSources) {
                const double x=source[0]-target[0],y=source[1]-target[1],z=source[2]-target[2];
                const double inverse=1.0/std::sqrt(x*x+y*y+z*z+0.0009765625);
                const double factor=source[3]*inverse*inverse*inverse;
                acceleration[0]+=x*factor;acceleration[1]+=y*factor;acceleration[2]+=z*factor;
            }
            output[index]=acceleration;
        }
        hashes[static_cast<size_t>(pass)]=Hash(output.data(),output.size()*sizeof(Position));
        sample.elapsed+=Nanos(kernel);
        const auto oracle=Clock::now();
        // Independently recompute every sweep in long double. Record its actual
        // precision: the ABI may make long double the same width as double.
        for(uint32_t index=0;index<kTargets;++index) {
            const auto target=Target(index,pass);
            std::array<long double,3> expected{};
            for(const auto& source:kSources) {
                const long double x=static_cast<long double>(source[0])-target[0];
                const long double y=static_cast<long double>(source[1])-target[1];
                const long double z=static_cast<long double>(source[2])-target[2];
                const long double inverse=1.0L/std::sqrt(x*x+y*y+z*z+0.0009765625L);
                const long double factor=static_cast<long double>(source[3])*inverse*inverse*inverse;
                expected[0]+=x*factor;expected[1]+=y*factor;expected[2]+=z*factor;
            }
            for(size_t axis=0;axis<3;++axis) {
                if(!std::isfinite(output[index][axis])) throw std::runtime_error("nonfinite-overlap-CPU-force");
                const auto relative=std::abs(static_cast<long double>(output[index][axis])-expected[axis])/std::max(1.0L,std::abs(expected[axis]));
                sample.error=std::max(sample.error,static_cast<double>(relative));
            }
        }
        oracleNs+=Nanos(oracle);
    }
    sample.executed=true;
    sample.checksum=Hash(hashes.data(),hashes.size()*sizeof(uint64_t));
    if(sample.error>1e-12) {sample.status="FAIL";sample.reason="overlap-cpu-fp64-oracle-mismatch";}
    std::ostringstream extra;extra<<std::setprecision(17)
        <<",\"body_count\":"<<kTargets<<",\"source_count\":3,\"cpu_setup_ns\":"<<setupNs
        <<",\"cpu_oracle_ns\":"<<oracleNs<<",\"oracle_mantissa_bits\":"<<std::numeric_limits<long double>::digits
        <<",\"oracle_verified_sweeps\":"<<iterations<<",\"max_allowed_scaled_error\":1e-12"
        <<",\"fixture\":\"normalised-three-source-grid-with-per-sweep-x-shift\",\"final_output_checksum_fnv1a64\":"
        <<Quote(std::to_string(Hash(output.data(),output.size()*sizeof(Position))));
    sample.extra=extra.str();
    return sample;
}

Sample Failed(const char* component,const std::exception_ptr& exception) {
    Sample sample;sample.status="FAIL";
    try {std::rethrow_exception(exception);} catch(const std::exception& error) {sample.reason=std::string(component)+":"+error.what();}
    catch(...) {sample.reason=std::string(component)+":unknown-exception";}
    return sample;
}
} // namespace

std::vector<CaseInfo> OverlapCases(const GpuInventory& gpu) {
    const auto found=std::find_if(gpu.cases.begin(),gpu.cases.end(),[](const auto& item){return item.id=="gpu.passive-fp32";});
    const bool supported=found!=gpu.cases.end()&&found->supported&&found->implemented;
    const std::string reason=supported?"cold isolated CPU/GPU jobs; not production frame scheduling":
        found==gpu.cases.end()?"gpu.passive-fp32-capability-unreported":
        found->reason.empty()?"gpu.passive-fp32-unsupported":found->reason;
    return {
        {"cpu-gpu-serial","Serial CPU FP64 + GPU FP32 jobs","scheduling",reason,"",supported,true},
        {"cpu-gpu-overlap","Overlapped CPU FP64 + GPU FP32 jobs","scheduling",reason,"cpu-gpu-serial",supported,true},
    };
}

Sample RunOverlap(const std::string& id,int iterations) {
    Sample result;result.iterations=iterations;
    result.scope="cold-end-to-end-until-both-jobs-complete;includes-thread-lifecycle-device-pipeline-allocation-oracles-and-teardown";
    if(id!="cpu-gpu-serial"&&id!="cpu-gpu-overlap") {
        result.status="SKIP";result.reason="unknown-cpu-gpu-scheduling-case";result.implemented=false;return result;
    }
    if(iterations<1||iterations>32) {
        result.status="FAIL";result.reason="scheduling-iterations-must-be-1-through-32";return result;
    }
    const bool overlap=id=="cpu-gpu-overlap";
    Sample cpu,gpu;
    uint64_t cpuStartNs=0,gpuStartNs=0,cpuWallNs=0,gpuWallNs=0;
    const auto begin=Clock::now();
    const auto cpuJob=[&] {
        cpuStartNs=Nanos(begin);const auto start=Clock::now();
        try {cpu=RunCpuForce(iterations);} catch(...) {cpu=Failed("cpu",std::current_exception());}
        cpuWallNs=Nanos(start);
    };
    const auto gpuJob=[&] {
        gpuStartNs=Nanos(begin);const auto start=Clock::now();
        // Exceptions must be retained, not escape a std::thread and terminate
        // the application. The caller reads these fields only after join.
        try {gpu=RunGpu("gpu.passive-fp32",iterations);} catch(...) {gpu=Failed("gpu",std::current_exception());}
        gpuWallNs=Nanos(start);
    };
    if(overlap) {
        try {
            std::thread gpuWorker(gpuJob);
            JoinOnExit joined(gpuWorker);
            // RAII also joins if constructing a failure report itself throws.
            cpuJob();
        } catch(const std::exception& error) {
            result.status="FAIL";result.reason=std::string("overlap-worker-lifecycle:")+error.what();
            result.elapsed=Nanos(begin);return result;
        }
    } else {cpuJob();gpuJob();}
    result.elapsed=Nanos(begin);
    result.enabled=cpu.enabled&&gpu.enabled;result.executed=cpu.executed&&gpu.executed;
    result.error=std::max(cpu.error,gpu.error);
    const std::array<uint64_t,2> hashes{cpu.checksum,gpu.checksum};
    result.checksum=Hash(hashes.data(),sizeof(hashes));
    if(cpu.status=="FAIL"||gpu.status=="FAIL") {
        result.status="FAIL";result.reason=cpu.status=="FAIL"?"cpu-component-failed:"+cpu.reason:"gpu-component-failed:"+gpu.reason;
    } else if(cpu.status=="SKIP"||gpu.status=="SKIP") {
        result.status="SKIP";result.reason=cpu.status=="SKIP"?"cpu-component-unavailable:"+cpu.reason:"gpu-component-unavailable:"+gpu.reason;
    } else {result.reason="both-isolated-job-oracles-passed";}
    const auto sharedStart=std::max(cpuStartNs,gpuStartNs);
    const auto sharedEnd=std::min(cpuStartNs+cpuWallNs,gpuStartNs+gpuWallNs);
    const auto concurrentNs=sharedEnd>sharedStart?sharedEnd-sharedStart:0;
    std::ostringstream extra;extra
        <<",\"scheduling_mode\":"<<Quote(overlap?"concurrent":"serial")
        <<",\"same_workload_across_schedules\":true,\"cold_setup_included\":true,\"production_scheduler_modified\":false"
        <<",\"cpu_start_offset_ns\":"<<cpuStartNs<<",\"gpu_start_offset_ns\":"<<gpuStartNs
        <<",\"cpu_job_wall_ns\":"<<cpuWallNs<<",\"gpu_job_wall_ns\":"<<gpuWallNs
        <<",\"job_interval_overlap_ns\":"<<concurrentNs
        <<",\"oracle_overlap_note\":\"job intervals include CPU verification; this is not a pure kernel-concurrency measurement\""
        <<",\"cpu_component\":"<<cpu.Json("component.cpu-fp64-force")
        <<",\"gpu_component\":"<<gpu.Json("gpu.passive-fp32");
    result.extra=extra.str();
    return result;
}
} // namespace solarlab::benchmark
