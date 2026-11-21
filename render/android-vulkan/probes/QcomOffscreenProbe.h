#pragma once
#include <cstdint>
#include <string>
namespace solarlab::probes {
struct QcomProbeResult {
    std::string status="FAIL",reason,device;
    uint32_t driverVersion=0,validationErrors=0;
    bool validation=false;
    double maximumError=0;
    uint64_t submissionNs=0,totalNs=0,checksum=0;
    std::string Json(const std::string& mode)const;
};
QcomProbeResult RunQcomProbe(const std::string& mode,const std::string& shaderDirectory,bool requireValidation);
}
