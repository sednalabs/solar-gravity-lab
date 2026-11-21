#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace solarlab::rendering {

// The file stays within 16 MiB including its 32-byte app-owned envelope. The
// Vulkan payload is unmodified; neither is read during steady frames.
inline constexpr size_t kMaxPipelineCacheBytes = 16U * 1024U * 1024U - 32U;
inline constexpr size_t kPipelineCacheHeaderBytes = 32U;

struct PipelineCacheIdentity {
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t driverVersion = 0;
    std::array<uint8_t, 16> uuid{};
};

enum class PipelineCacheFileStatus {
    Disabled,
    Missing,
    Loaded,
    Saved,
    InvalidFile,
    Truncated,
    TooLarge,
    DriverChanged,
    InvalidHeader,
    VendorChanged,
    DeviceChanged,
    UuidChanged,
    Corrupt,
    ReadFailed,
    WriteFailed,
    AllocationFailed,
};

const char* PipelineCacheFileStatusLabel(PipelineCacheFileStatus status);

struct PipelineCacheLoadResult {
    PipelineCacheFileStatus status = PipelineCacheFileStatus::Missing;
    std::vector<uint8_t> data;
};

// App-private cache storage, independent of Vulkan so the file contract can be
// tested on a host. A rejected file always returns an empty data vector.
class PipelineCacheStore {
public:
    static PipelineCacheLoadResult Load(const std::string& path, const PipelineCacheIdentity& identity);
    static PipelineCacheFileStatus Save(const std::string& path, const PipelineCacheIdentity& identity,
        const std::vector<uint8_t>& data);
};

}  // namespace solarlab::rendering
