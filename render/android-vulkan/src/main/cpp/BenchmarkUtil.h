#pragma once
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace solarlab::benchmark {
using Clock = std::chrono::steady_clock;
inline uint64_t Nanos(Clock::time_point start) {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now()-start).count());
}
inline std::string Quote(const std::string& value) {
    std::ostringstream out; out << '"';
    for (unsigned char c : value) {
        if (c=='"'||c=='\\') out << '\\' << c;
        else if (c<32U) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << unsigned(c) << std::dec;
        else out << c;
    }
    return out.str()+'"';
}
inline uint64_t Hash(const void* pointer, size_t bytes) {
    const auto* data=static_cast<const uint8_t*>(pointer);
    uint64_t value=14695981039346656037ULL;
    for(size_t i=0;i<bytes;++i) value=(value^data[i])*1099511628211ULL;
    return value;
}
struct Sample {
    std::string status="PASS", reason="executed-and-cpu-oracle-matched";
    std::string scope="kernel-only-excluding-allocation-oracle-and-hash", extra;
    uint64_t elapsed=0, checksum=0;
    double error=0;
    int iterations=1;
    bool implemented=true, enabled=false, executed=false;
    std::string Json(const std::string& id) const {
        std::ostringstream out; out << std::setprecision(17)
            << "{\"schema_version\":1,\"case_id\":" << Quote(id)
            << ",\"status\":" << Quote(status) << ",\"reason\":" << Quote(reason)
            << ",\"implemented\":" << (implemented?"true":"false")
            << ",\"enabled\":" << (enabled?"true":"false") << ",\"executed\":" << (executed?"true":"false")
            << ",\"elapsed_ns\":" << elapsed << ",\"iterations\":" << iterations
            << ",\"timing_scope\":" << Quote(scope) << ",\"checksum_fnv1a64\":" << Quote(std::to_string(checksum))
            << ",\"max_scaled_error\":";
        if(std::isfinite(error)) out << error; else out << "null";
        out << ",\"authoritative_world_modified\":false" << extra << '}';
        return out.str();
    }
};
struct CaseInfo {
    std::string id,label,family,reason,baseline;
    bool supported=true,implemented=true;
    std::string Json() const {
        return "{\"id\":"+Quote(id)+",\"label\":"+Quote(label)+",\"family\":"+Quote(family)
            +",\"status\":"+Quote(!implemented?"not_implemented":supported?"runnable":"unsupported")
            +",\"supported\":"+(supported?"true":"false")+",\"implemented\":"+(implemented?"true":"false")
            +",\"enabled\":false,\"executed\":false,\"reason\":"+Quote(reason)
            +",\"baseline_case_id\":"+(baseline.empty()?"null":Quote(baseline))+"}";
    }
};
struct GpuInventory { std::string json; std::vector<CaseInfo> cases; };
GpuInventory GpuCapabilities();
Sample RunGpu(const std::string& id,int iterations);
} // namespace solarlab::benchmark
