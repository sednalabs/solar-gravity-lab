#include "PipelineCacheStore.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <new>
#include <sys/stat.h>
#include <unistd.h>

namespace solarlab::rendering {
namespace {
constexpr std::array<uint8_t, 8> kFileMagic{'S', 'L', 'V', 'K', 'P', 'C', '0', '1'};
constexpr size_t kEnvelopeBytes = 32;
constexpr uint32_t kFileVersion = 1;
constexpr uint32_t kVulkanHeaderVersionOne = 1;

uint32_t ReadU32(const uint8_t* data) {
    return uint32_t{data[0]} | (uint32_t{data[1]} << 8U) |
        (uint32_t{data[2]} << 16U) | (uint32_t{data[3]} << 24U);
}

uint64_t ReadU64(const uint8_t* data) {
    return uint64_t{ReadU32(data)} | (uint64_t{ReadU32(data + 4)} << 32U);
}

void WriteU32(uint8_t* data, uint32_t value) {
    for (size_t i = 0; i < 4; ++i) data[i] = static_cast<uint8_t>(value >> (8U * i));
}

void WriteU64(uint8_t* data, uint64_t value) {
    WriteU32(data, static_cast<uint32_t>(value));
    WriteU32(data + 4, static_cast<uint32_t>(value >> 32U));
}

// Integrity check for accidental corruption, not an authenticity mechanism.
uint64_t Checksum(const std::vector<uint8_t>& data) {
    uint64_t value = 14695981039346656037ULL;
    for (const uint8_t byte : data) {
        value ^= byte;
        value *= 1099511628211ULL;
    }
    return value;
}

PipelineCacheFileStatus ValidateHeader(const std::vector<uint8_t>& data,
    const PipelineCacheIdentity& identity) {
    // VkPipelineCacheHeaderVersionOne is exactly 32 bytes, with little-endian
    // fields at specified offsets. Do not reinterpret_cast a native struct.
    // https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCacheHeaderVersionOne.html
    if (data.size() < kPipelineCacheHeaderBytes || ReadU32(data.data()) != kPipelineCacheHeaderBytes ||
        ReadU32(data.data() + 4) != kVulkanHeaderVersionOne) {
        return PipelineCacheFileStatus::InvalidHeader;
    }
    if (ReadU32(data.data() + 8) != identity.vendorId) return PipelineCacheFileStatus::VendorChanged;
    if (ReadU32(data.data() + 12) != identity.deviceId) return PipelineCacheFileStatus::DeviceChanged;
    if (!std::equal(identity.uuid.begin(), identity.uuid.end(), data.begin() + 16)) {
        return PipelineCacheFileStatus::UuidChanged;
    }
    return PipelineCacheFileStatus::Loaded;
}

class FileDescriptor {
public:
    explicit FileDescriptor(int fd) : fd_(fd) {}
    ~FileDescriptor() { if (fd_ >= 0) close(fd_); }
    FileDescriptor(const FileDescriptor&) = delete;
    FileDescriptor& operator=(const FileDescriptor&) = delete;
    int Get() const { return fd_; }
    bool Close() {
        const int fd = fd_;
        fd_ = -1;
        return close(fd) == 0;
    }
private:
    int fd_;
};

PipelineCacheFileStatus ReadAll(int fd, uint8_t* data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const ssize_t count = read(fd, data + offset, size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return PipelineCacheFileStatus::ReadFailed;
        if (count == 0) return PipelineCacheFileStatus::Truncated;
        offset += static_cast<size_t>(count);
    }
    return PipelineCacheFileStatus::Loaded;
}

bool WriteAll(int fd, const uint8_t* data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const ssize_t count = write(fd, data + offset, size - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        offset += static_cast<size_t>(count);
    }
    return true;
}

bool SyncFile(int fd) {
    int result;
    do { result = fsync(fd); } while (result != 0 && errno == EINTR);
    return result == 0;
}
}  // namespace

const char* PipelineCacheFileStatusLabel(PipelineCacheFileStatus status) {
    switch (status) {
        case PipelineCacheFileStatus::Disabled: return "disabled";
        case PipelineCacheFileStatus::Missing: return "missing";
        case PipelineCacheFileStatus::Loaded: return "loaded";
        case PipelineCacheFileStatus::Saved: return "saved";
        case PipelineCacheFileStatus::InvalidFile: return "invalid-file";
        case PipelineCacheFileStatus::Truncated: return "truncated";
        case PipelineCacheFileStatus::TooLarge: return "too-large";
        case PipelineCacheFileStatus::DriverChanged: return "driver-changed";
        case PipelineCacheFileStatus::InvalidHeader: return "invalid-header";
        case PipelineCacheFileStatus::VendorChanged: return "vendor-changed";
        case PipelineCacheFileStatus::DeviceChanged: return "device-changed";
        case PipelineCacheFileStatus::UuidChanged: return "uuid-changed";
        case PipelineCacheFileStatus::Corrupt: return "corrupt";
        case PipelineCacheFileStatus::ReadFailed: return "read-failed";
        case PipelineCacheFileStatus::WriteFailed: return "write-failed";
        case PipelineCacheFileStatus::AllocationFailed: return "allocation-failed";
    }
    return "unknown";
}

PipelineCacheLoadResult PipelineCacheStore::Load(const std::string& path, const PipelineCacheIdentity& identity) {
    if (path.empty()) return {PipelineCacheFileStatus::Disabled, {}};
    // Nonblocking/no-follow plus a regular-file check also keep an unexpected
    // filesystem entry from stalling renderer creation.
    FileDescriptor file(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
    if (file.Get() < 0) {
        return {errno == ENOENT ? PipelineCacheFileStatus::Missing : PipelineCacheFileStatus::ReadFailed, {}};
    }
    struct stat info{};
    if (fstat(file.Get(), &info) != 0) return {PipelineCacheFileStatus::ReadFailed, {}};
    if (!S_ISREG(info.st_mode) || info.st_size < 0) return {PipelineCacheFileStatus::InvalidFile, {}};
    const auto fileBytes = static_cast<uint64_t>(info.st_size);
    if (fileBytes > kMaxPipelineCacheBytes + kEnvelopeBytes) return {PipelineCacheFileStatus::TooLarge, {}};
    if (fileBytes < kEnvelopeBytes + kPipelineCacheHeaderBytes) return {PipelineCacheFileStatus::Truncated, {}};

    std::array<uint8_t, kEnvelopeBytes> envelope{};
    const auto envelopeRead = ReadAll(file.Get(), envelope.data(), envelope.size());
    if (envelopeRead != PipelineCacheFileStatus::Loaded) return {envelopeRead, {}};
    if (!std::equal(kFileMagic.begin(), kFileMagic.end(), envelope.begin()) ||
        ReadU32(envelope.data() + 8) != kFileVersion) {
        return {PipelineCacheFileStatus::InvalidFile, {}};
    }
    if (ReadU32(envelope.data() + 12) != identity.driverVersion) {
        return {PipelineCacheFileStatus::DriverChanged, {}};
    }
    const uint64_t payloadBytes = ReadU64(envelope.data() + 16);
    if (payloadBytes > kMaxPipelineCacheBytes) return {PipelineCacheFileStatus::TooLarge, {}};
    if (payloadBytes < kPipelineCacheHeaderBytes) return {PipelineCacheFileStatus::InvalidHeader, {}};
    if (fileBytes < payloadBytes + kEnvelopeBytes) return {PipelineCacheFileStatus::Truncated, {}};
    if (fileBytes != payloadBytes + kEnvelopeBytes) return {PipelineCacheFileStatus::InvalidFile, {}};

    PipelineCacheLoadResult result;
    try {
        result.data.resize(static_cast<size_t>(payloadBytes));
    } catch (const std::bad_alloc&) {
        return {PipelineCacheFileStatus::AllocationFailed, {}};
    }
    result.status = ReadAll(file.Get(), result.data.data(), result.data.size());
    if (result.status != PipelineCacheFileStatus::Loaded) return {result.status, {}};
    // A concurrent app writer normally uses rename. Reject an in-place size
    // change too, instead of submitting a prefix that was never serialized.
    uint8_t trailingByte;
    ssize_t trailingCount;
    do { trailingCount = read(file.Get(), &trailingByte, 1); } while (trailingCount < 0 && errno == EINTR);
    if (trailingCount != 0) return {PipelineCacheFileStatus::InvalidFile, {}};
    if (Checksum(result.data) != ReadU64(envelope.data() + 24)) return {PipelineCacheFileStatus::Corrupt, {}};
    result.status = ValidateHeader(result.data, identity);
    if (result.status != PipelineCacheFileStatus::Loaded) result.data.clear();
    return result;
}

PipelineCacheFileStatus PipelineCacheStore::Save(const std::string& path, const PipelineCacheIdentity& identity,
    const std::vector<uint8_t>& data) {
    if (path.empty()) return PipelineCacheFileStatus::Disabled;
    if (data.size() > kMaxPipelineCacheBytes) return PipelineCacheFileStatus::TooLarge;
    const auto headerStatus = ValidateHeader(data, identity);
    if (headerStatus != PipelineCacheFileStatus::Loaded) return headerStatus;

    std::array<uint8_t, kEnvelopeBytes> envelope{};
    std::copy(kFileMagic.begin(), kFileMagic.end(), envelope.begin());
    WriteU32(envelope.data() + 8, kFileVersion);
    WriteU32(envelope.data() + 12, identity.driverVersion);
    WriteU64(envelope.data() + 16, data.size());
    WriteU64(envelope.data() + 24, Checksum(data));

    std::string temporaryPath = path + ".tmp.XXXXXX";
    FileDescriptor file(mkstemp(temporaryPath.data()));  // mode 0600, same filesystem as destination
    if (file.Get() < 0) return PipelineCacheFileStatus::WriteFailed;
    const bool written = WriteAll(file.Get(), envelope.data(), envelope.size()) &&
        WriteAll(file.Get(), data.data(), data.size()) && SyncFile(file.Get());
    const bool closed = file.Close();
    if (!written || !closed || rename(temporaryPath.c_str(), path.c_str()) != 0) {
        unlink(temporaryPath.c_str());
        return PipelineCacheFileStatus::WriteFailed;
    }
    // Losing a recent rename after sudden power loss only loses a cache update.
    // No reader can observe the partially written replacement.
    return PipelineCacheFileStatus::Saved;
}

}  // namespace solarlab::rendering
