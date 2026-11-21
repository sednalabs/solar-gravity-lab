// Exercises the production file store, not a GPU driver or an app speedup.
#include "../../main/cpp/PipelineCacheStore.h"

#include <algorithm>
#include <cassert>
#include <csignal>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/resource.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace {
using solarlab::rendering::PipelineCacheFileStatus;
using solarlab::rendering::PipelineCacheIdentity;
using solarlab::rendering::PipelineCacheStore;

struct TemporaryDirectory {
    TemporaryDirectory() {
        char pattern[] = "/tmp/solarlab-pipeline-cache-XXXXXX";
        const char* created = mkdtemp(pattern);
        assert(created != nullptr);
        path = created;
    }
    ~TemporaryDirectory() { std::filesystem::remove_all(path); }
    std::string path;
};

PipelineCacheIdentity Identity() {
    PipelineCacheIdentity identity{
        .vendorId = 0x01020304U,
        .deviceId = 0x89abcdefU,
        .driverVersion = 0x11223344U,
    };
    for (size_t i = 0; i < identity.uuid.size(); ++i) identity.uuid[i] = static_cast<uint8_t>(i + 32U);
    return identity;
}

void PutU32(std::vector<uint8_t>& bytes, size_t offset, uint32_t value) {
    for (size_t i = 0; i < 4; ++i) bytes.at(offset + i) = static_cast<uint8_t>(value >> (8U * i));
}

std::vector<uint8_t> Payload(const PipelineCacheIdentity& identity, size_t size = 512) {
    assert(size >= solarlab::rendering::kPipelineCacheHeaderBytes);
    std::vector<uint8_t> bytes(size, 0x5aU);
    PutU32(bytes, 0, 32);
    PutU32(bytes, 4, 1);
    PutU32(bytes, 8, identity.vendorId);
    PutU32(bytes, 12, identity.deviceId);
    std::copy(identity.uuid.begin(), identity.uuid.end(), bytes.begin() + 16);
    return bytes;
}

void ExpectRejected(const std::string& path, const PipelineCacheIdentity& identity, PipelineCacheFileStatus status) {
    const auto loaded = PipelineCacheStore::Load(path, identity);
    assert(loaded.status == status);
    assert(loaded.data.empty());
}

void NormalReuseAndAtomicReplacement(const std::string& path) {
    const auto identity = Identity();
    const auto first = Payload(identity);
    assert(PipelineCacheStore::Save(path, identity, first) == PipelineCacheFileStatus::Saved);
    const auto loaded = PipelineCacheStore::Load(path, identity);
    assert(loaded.status == PipelineCacheFileStatus::Loaded);
    assert(loaded.data == first);  // Exactly the Vulkan bytes; no app envelope passed to the driver.
    struct stat info{};
    assert(stat(path.c_str(), &info) == 0);
    assert((info.st_mode & 0777) == 0600);

    const int oldReader = open(path.c_str(), O_RDONLY);
    assert(oldReader >= 0);
    auto replacement = Payload(identity, 2048);
    replacement.back() = 0x81U;
    assert(PipelineCacheStore::Save(path, identity, replacement) == PipelineCacheFileStatus::Saved);
    assert(PipelineCacheStore::Load(path, identity).data == replacement);
    // A reader opened before replacement keeps the complete old file, even
    // when the new payload has a different length.
    assert(fstat(oldReader, &info) == 0);
    assert(info.st_size == static_cast<off_t>(first.size() + 32));
    std::vector<uint8_t> oldPayload(first.size());
    assert(pread(oldReader, oldPayload.data(), oldPayload.size(), 32) == static_cast<ssize_t>(oldPayload.size()));
    assert(oldPayload == first);
    assert(close(oldReader) == 0);
}

void CompatibilityChecks(const std::string& path) {
    const auto identity = Identity();
    assert(PipelineCacheStore::Save(path, identity, Payload(identity)) == PipelineCacheFileStatus::Saved);
    auto changed = identity;
    ++changed.driverVersion;
    ExpectRejected(path, changed, PipelineCacheFileStatus::DriverChanged);
    changed = identity;
    ++changed.vendorId;
    ExpectRejected(path, changed, PipelineCacheFileStatus::VendorChanged);
    changed = identity;
    ++changed.deviceId;
    ExpectRejected(path, changed, PipelineCacheFileStatus::DeviceChanged);
    changed = identity;
    changed.uuid.back() ^= 1U;
    ExpectRejected(path, changed, PipelineCacheFileStatus::UuidChanged);
}

void TruncationAndCorruption(const std::string& path) {
    const auto identity = Identity();
    const auto data = Payload(identity);
    assert(PipelineCacheStore::Save(path, identity, data) == PipelineCacheFileStatus::Saved);
    // Header remains intact, but the serialized payload is incomplete.
    assert(truncate(path.c_str(), 100) == 0);
    ExpectRejected(path, identity, PipelineCacheFileStatus::Truncated);
    assert(truncate(path.c_str(), 20) == 0);
    ExpectRejected(path, identity, PipelineCacheFileStatus::Truncated);

    assert(PipelineCacheStore::Save(path, identity, data) == PipelineCacheFileStatus::Saved);
    {
        std::fstream file(path, std::ios::in | std::ios::out | std::ios::binary);
        assert(file.good());
        file.seekp(-1, std::ios::end);
        file.put('\0');
        assert(file.good());
    }
    ExpectRejected(path, identity, PipelineCacheFileStatus::Corrupt);
    assert(PipelineCacheStore::Save(path, identity, data) == PipelineCacheFileStatus::Saved);
    {
        std::ofstream file(path, std::ios::app | std::ios::binary);
        file.put('x');
        assert(file.good());
    }
    ExpectRejected(path, identity, PipelineCacheFileStatus::InvalidFile);
}

void BoundedFilesAndExactVulkanHeader(const std::string& path) {
    const auto identity = Identity();
    const auto data = Payload(identity);
    assert(PipelineCacheStore::Save(path, identity, data) == PipelineCacheFileStatus::Saved);
    // Sparse oversized file: the loader must reject it before payload allocation.
    assert(truncate(path.c_str(), solarlab::rendering::kMaxPipelineCacheBytes + 33) == 0);
    ExpectRejected(path, identity, PipelineCacheFileStatus::TooLarge);
    auto excessive = Payload(identity, solarlab::rendering::kMaxPipelineCacheBytes + 1);
    assert(PipelineCacheStore::Save(path, identity, excessive) == PipelineCacheFileStatus::TooLarge);
    assert(PipelineCacheStore::Save(path, identity, data) == PipelineCacheFileStatus::Saved);

    auto malformed = data;
    PutU32(malformed, 0, 36);  // Header version one is exactly 32 bytes, not a minimum.
    assert(PipelineCacheStore::Save(path, identity, malformed) == PipelineCacheFileStatus::InvalidHeader);
    malformed = data;
    PutU32(malformed, 4, 2);
    assert(PipelineCacheStore::Save(path, identity, malformed) == PipelineCacheFileStatus::InvalidHeader);
    malformed.resize(31);
    assert(PipelineCacheStore::Save(path, identity, malformed) == PipelineCacheFileStatus::InvalidHeader);
    assert(PipelineCacheStore::Load(path, identity).data == data);
}

void WriteFailurePreservesPreviousFile(const std::string& path, const std::string& directory) {
    const auto identity = Identity();
    const auto original = Payload(identity);
    assert(PipelineCacheStore::Save(path, identity, original) == PipelineCacheFileStatus::Saved);

    // A real short write followed by EFBIG works even on a privileged CI host;
    // chmod-based failure tests do not. Restore the process limits immediately.
    struct rlimit previousLimit{};
    assert(getrlimit(RLIMIT_FSIZE, &previousLimit) == 0);
    auto limited = previousLimit;
    limited.rlim_cur = 96;
    struct sigaction ignored{}, previousAction{};
    ignored.sa_handler = SIG_IGN;
    assert(sigemptyset(&ignored.sa_mask) == 0);
    assert(sigaction(SIGXFSZ, &ignored, &previousAction) == 0);
    assert(setrlimit(RLIMIT_FSIZE, &limited) == 0);
    const auto status = PipelineCacheStore::Save(path, identity, Payload(identity, 2048));
    assert(setrlimit(RLIMIT_FSIZE, &previousLimit) == 0);
    assert(sigaction(SIGXFSZ, &previousAction, nullptr) == 0);
    assert(status == PipelineCacheFileStatus::WriteFailed);
    assert(PipelineCacheStore::Load(path, identity).data == original);
    for (const auto& entry : std::filesystem::directory_iterator(directory)) {
        assert(entry.path().string().find(".tmp.") == std::string::npos);
    }
    assert(PipelineCacheStore::Save(path + "/not-a-directory", identity, original) == PipelineCacheFileStatus::WriteFailed);
    assert(PipelineCacheStore::Save(directory, identity, original) == PipelineCacheFileStatus::WriteFailed);
    assert(PipelineCacheStore::Load(path, identity).data == original);
}
}  // namespace

int main() {
    TemporaryDirectory directory;
    const std::string path = directory.path + "/cache.bin";
    const auto identity = Identity();
    ExpectRejected("", identity, PipelineCacheFileStatus::Disabled);
    ExpectRejected(path, identity, PipelineCacheFileStatus::Missing);
    ExpectRejected(directory.path, identity, PipelineCacheFileStatus::InvalidFile);
    assert(PipelineCacheStore::Save("", identity, Payload(identity)) == PipelineCacheFileStatus::Disabled);
    NormalReuseAndAtomicReplacement(path);
    CompatibilityChecks(path);
    TruncationAndCorruption(path);
    BoundedFilesAndExactVulkanHeader(path);
    WriteFailurePreservesPreviousFile(path, directory.path);
    std::cout << "Pipeline cache store: reuse, compatibility, bounded corruption rejection and atomic write failure passed.\n";
}
