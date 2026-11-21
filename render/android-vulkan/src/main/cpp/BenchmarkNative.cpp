#include "BenchmarkNative.h"
#include "BenchmarkOpenCl.h"
#include "BenchmarkOverlap.h"
#include "BenchmarkUtil.h"
#if defined(__ANDROID__)
#include "BenchmarkNnapi.h"
#endif
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <mutex>
#include <sched.h>
#include <stdexcept>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <thread>
#include <unistd.h>
#if defined(__ANDROID__)
#include <jni.h>
#endif
#if defined(__aarch64__)
#include <arm_acle.h>
#include <arm_neon.h>
#include <arm_sve.h>
#include <asm/hwcap.h>
#endif

namespace solarlab::benchmark {
namespace {
std::mutex benchmarkMutex;
thread_local cpu_set_t originalAffinity;
thread_local bool affinityCaptured = false;
std::string ReadSmall(const std::string &path, size_t bound = 4096) {
    std::ifstream input(path);
    if (!input)
        return "";
    std::string value(bound, '\0');
    input.read(value.data(), static_cast<std::streamsize>(bound));
    value.resize(static_cast<size_t>(input.gcount()));
    while (!value.empty() && (value.back() == '\n' || value.back() == '\r'))
        value.pop_back();
    return value;
}
std::string CpuList(const cpu_set_t &mask) {
    std::string result = "[";
    bool first = true;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu)
        if (CPU_ISSET(cpu, &mask)) {
            if (!first)
                result += ",";
            first = false;
            result += std::to_string(cpu);
        }
    return result + "]";
}
long Capacity(int cpu, const char *field) {
    const std::string raw = ReadSmall("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/" + field, 128);
    try {
        return raw.empty() ? 0 : std::stol(raw);
    } catch (...) {
        return 0;
    }
}
bool Sve() {
#if defined(__aarch64__) && defined(HWCAP_SVE)
    return (getauxval(AT_HWCAP) & HWCAP_SVE) != 0;
#else
    return false;
#endif
}
bool Crc() {
#if defined(__aarch64__) && defined(HWCAP_CRC32)
    return (getauxval(AT_HWCAP) & HWCAP_CRC32) != 0;
#else
    return false;
#endif
}
bool Fcma() {
#if defined(__aarch64__) && defined(HWCAP_FCMA)
    return (getauxval(AT_HWCAP) & HWCAP_FCMA) != 0;
#else
    return false;
#endif
}
std::string CpuCapabilities() {
    cpu_set_t mask;
    CPU_ZERO(&mask);
    const int affinityResult = sched_getaffinity(0, sizeof(mask), &mask);
    std::ostringstream out;
    out << "{\"scope\":\"calling-app-thread\",\"pid\":" << getpid() << ",\"tid\":" << gettid()
        << ",\"page_size_bytes\":" << sysconf(_SC_PAGESIZE)
        << ",\"affinity_read_ok\":" << (affinityResult == 0 ? "true" : "false")
        << ",\"allowed_cpus\":" << CpuList(mask)
        << ",\"auxv_hwcap\":" << Quote(std::to_string(getauxval(AT_HWCAP)))
        << ",\"auxv_hwcap2\":" << Quote(std::to_string(getauxval(AT_HWCAP2)))
        << ",\"online_cpus\":" << Quote(ReadSmall("/sys/devices/system/cpu/online"))
        << ",\"cpuset_membership\":" << Quote(ReadSmall("/proc/thread-self/cgroup"))
        << ",\"sve\":" << (Sve() ? "true" : "false") << ",\"crc32\":" << (Crc() ? "true" : "false")
        << ",\"fcma\":" << (Fcma() ? "true" : "false");
#if defined(__aarch64__) && defined(PR_SVE_GET_VL)
    if (Sve())
        out << ",\"sve_vector_length_prctl\":" << prctl(PR_SVE_GET_VL);
#endif
#if defined(__aarch64__) && defined(PR_GET_TAGGED_ADDR_CTRL)
    out << ",\"tagged_address_control\":" << prctl(PR_GET_TAGGED_ADDR_CTRL, 0, 0, 0, 0);
#endif
    out << ",\"topology\":[";
    bool first = true;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu)
        if (CPU_ISSET(cpu, &mask)) {
            if (!first)
                out << ',';
            first = false;
            const auto root = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/";
            out << "{\"cpu\":" << cpu << ",\"capacity\":" << Quote(ReadSmall(root + "cpu_capacity", 128))
                << ",\"max_frequency_khz\":" << Quote(ReadSmall(root + "cpufreq/cpuinfo_max_freq", 128))
                << ",\"cluster_id\":" << Quote(ReadSmall(root + "topology/cluster_id", 128))
                << ",\"core_id\":" << Quote(ReadSmall(root + "topology/core_id", 128)) << ",\"caches\":[";
            bool firstCache = true;
            for (int index = 0; index < 8; ++index) {
                const auto cache = root + "cache/index" + std::to_string(index) + "/";
                const auto level = ReadSmall(cache + "level", 32);
                if (level.empty())
                    continue;
                if (!firstCache)
                    out << ',';
                firstCache = false;
                out << "{\"level\":" << Quote(level) << ",\"type\":" << Quote(ReadSmall(cache + "type", 64))
                    << ",\"size\":" << Quote(ReadSmall(cache + "size", 64))
                    << ",\"line_bytes\":" << Quote(ReadSmall(cache + "coherency_line_size", 64))
                    << ",\"shared_cpus\":" << Quote(ReadSmall(cache + "shared_cpu_list", 256)) << '}';
            }
            out << "]}";
        }
    return out.str() + "]}";
}
std::string OptionalRuntime(const char *library, const char *symbol) {
    void *handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    const bool loadable = handle != nullptr;
    const bool entrypoint = handle && dlsym(handle, symbol) != nullptr;
    if (handle)
        dlclose(handle);
    return "{\"library\":" + Quote(library) + ",\"app_namespace_loadable\":" + (loadable ? "true" : "false") +
           ",\"entrypoint_visible\":" + (entrypoint ? "true" : "false") +
           ",\"execution_qualified\":false,\"reason\":\"library-discovery-only-no-model-or-device-"
           "workload\"}";
}
#if defined(__aarch64__)
__attribute__((target("sve"), noinline)) void SveAdd(const double *a, const double *b, double *output,
                                                     size_t size) {
    for (size_t i = 0; i < size; i += svcntd()) {
        const svbool_t active = svwhilelt_b64(static_cast<uint64_t>(i), static_cast<uint64_t>(size));
        svst1_f64(active, output + i,
                  svadd_f64_x(active, svld1_f64(active, a + i), svld1_f64(active, b + i)));
    }
}
__attribute__((target("crc"), noinline)) uint32_t ArmCrc(const uint8_t *bytes, size_t size) {
    if (size != 0 && bytes == nullptr)
        throw std::invalid_argument("null-CRC-source");
    uint32_t crc = 0;
    size_t i = 0;
    // The subtraction is safe because i never exceeds size. Each copy has an
    // eight-byte source slice and a separate eight-byte local destination;
    // memcpy also avoids an unaligned uint64_t load or aliasing violation.
    for (; size - i >= sizeof(uint64_t); i += sizeof(uint64_t)) {
        uint64_t word = 0;
        std::memcpy(&word, bytes + i, sizeof(word));
        // IEEE CRC32 matches ScalarCrc's 0xedb88320 polynomial; the
        // similarly named __crc32cd intrinsic computes CRC32C instead.
        crc = __crc32d(crc, word);
    }
    for (; i < size; ++i)
        crc = __crc32b(crc, bytes[i]);
    return crc;
}
__attribute__((target("arch=armv8.3-a"), noinline)) void ComplexArm(const double *a, const double *b,
                                                                    double *output, size_t size) {
    for (size_t i = 0; i < size; i += 2) {
        const auto left = vld1q_f64(a + i), right = vld1q_f64(b + i);
        auto value = vcmlaq_f64(vdupq_n_f64(0), left, right);
        value = vcmlaq_rot90_f64(value, left, right);
        vst1q_f64(output + i, value);
    }
}
#endif
__attribute__((noinline)) uint32_t ScalarCrc(const uint8_t *bytes, size_t size) {
    uint32_t crc = 0;
    for (size_t i = 0; i < size; ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return crc;
}
__attribute__((noinline)) void ScalarAdd(const double *a, const double *b, double *output, size_t size) {
    for (size_t i = 0; i < size; ++i)
        output[i] = a[i] + b[i];
}
__attribute__((noinline)) void ComplexScalar(const double *a, const double *b, double *output, size_t size) {
    for (size_t i = 0; i < size; i += 2) {
        output[i] = a[i] * b[i] - a[i + 1] * b[i + 1];
        output[i + 1] = a[i] * b[i + 1] + a[i + 1] * b[i];
    }
}
Sample RunCpu(const std::string &id, int iterations) {
    Sample result;
    result.iterations = iterations;
    try {
        if ((id == "native.sve-fp64" && !Sve()) || (id == "native.crc32-arm" && !Crc()) ||
            (id == "native.fcma-f64" && !Fcma())) {
            result.status = "SKIP";
            result.reason = "required-instruction-set-not-exposed-in-app-auxv";
            return result;
        }
        result.enabled = true;
        if (id == "native.memcpy" || id == "native.memset" || id == "native.crc32-scalar" ||
            id == "native.crc32-arm") {
            const size_t size = id.find("crc32") != std::string::npos ? 256U * 1024U : 4U * 1024U * 1024U;
            std::vector<uint8_t> source(size), output(size);
            // This case deliberately measures libc memcpy. Validate its complete
            // contract before timing: equal nonzero capacities, live allocations,
            // and distinct owning vectors (therefore no overlap).
            if (size == 0 || source.size() != size || output.size() != size || !source.data() ||
                !output.data() || source.data() == output.data())
                throw std::runtime_error("invalid-memory-benchmark-bounds");
            for (size_t i = 0; i < size; ++i)
                source[i] = static_cast<uint8_t>((i * 131U + 17U) & 255U);
            uint32_t crc = 0;
            const auto start = Clock::now();
            for (int pass = 0; pass < iterations; ++pass) {
                if (id == "native.memcpy")
                    std::memcpy(output.data(), source.data(), size);
                else if (id == "native.memset")
                    std::memset(output.data(), 0x5a, size);
                else if (id == "native.crc32-scalar")
                    crc = ScalarCrc(source.data(), size);
#if defined(__aarch64__)
                else
                    crc = ArmCrc(source.data(), size);
#endif
                std::atomic_signal_fence(std::memory_order_seq_cst);
            }
            result.elapsed = Nanos(start);
            result.executed = true;
            if (id == "native.memcpy" && output != source)
                throw std::runtime_error("memcpy-oracle-mismatch");
            if (id == "native.memset" &&
                !std::all_of(output.begin(), output.end(), [](auto x) { return x == 0x5a; }))
                throw std::runtime_error("memset-oracle-mismatch");
            if (id.find("crc32") != std::string::npos) {
                if (crc != ScalarCrc(source.data(), size))
                    throw std::runtime_error("crc32-oracle-mismatch");
                result.checksum = Hash(&crc, sizeof(crc));
            } else
                result.checksum = Hash(output.data(), output.size());
            result.extra = ",\"bytes_per_iteration\":" + std::to_string(size) +
                           ",\"libc_instruction_selection\":\"not-inferred-from-capabilities\"";
            if (id.find("crc32") != std::string::npos)
                result.extra += ",\"crc32\":" + std::to_string(crc);
            return result;
        }
        if (id == "native.atomic-relaxed" || id == "native.atomic-seqcst") {
            const uint32_t operations = static_cast<uint32_t>(iterations) * 16384U;
            const auto order =
                id == "native.atomic-relaxed" ? std::memory_order_relaxed : std::memory_order_seq_cst;
            alignas(64) std::atomic_uint64_t counter{0};
            std::atomic_bool ready{false}, go{false};
            std::thread worker([&] {
                ready.store(true, std::memory_order_release);
                while (!go.load(std::memory_order_acquire))
                    std::this_thread::yield();
                for (uint32_t i = 0; i < operations; ++i)
                    counter.fetch_add(1, order);
            });
            while (!ready.load(std::memory_order_acquire))
                std::this_thread::yield();
            const auto start = Clock::now();
            go.store(true, std::memory_order_release);
            for (uint32_t i = 0; i < operations; ++i)
                counter.fetch_add(1, order);
            worker.join();
            result.elapsed = Nanos(start);
            result.executed = true;
            if (counter.load() != 2ULL * operations)
                throw std::runtime_error("atomic-count-mismatch");
            const uint64_t finalCount = counter.load();
            result.checksum = Hash(&finalCount, sizeof(finalCount));
            result.scope = "two-thread-contended-counter-including-worker-join";
            result.extra = ",\"operations_per_thread\":" + std::to_string(operations) +
                           ",\"instruction_selection\":\"compiler-runtime-selected\"";
            return result;
        }
        const size_t size = 256U * 1024U;
        std::vector<double> a(size), b(size), output(size);
        for (size_t i = 0; i < size; ++i) {
            a[i] = static_cast<double>(i % 1024) / 16;
            b[i] = static_cast<double>(i % 97) / 32;
        }
        double sum = 0;
        const auto start = Clock::now();
        for (int pass = 0; pass < iterations; ++pass) {
            if (id == "native.prefetch-none" || id == "native.prefetch-4" || id == "native.prefetch-8") {
                const size_t distance = id == "native.prefetch-4" ? 32 : id == "native.prefetch-8" ? 64 : 0;
                sum = 0;
                for (size_t i = 0; i < size; ++i) {
                    if (distance && i + distance < size)
                        __builtin_prefetch(a.data() + i + distance, 0, 3);
                    sum += a[i];
                }
            } else if (id == "native.vector-fp64")
                ScalarAdd(a.data(), b.data(), output.data(), size);
            else if (id == "native.complex-f64")
                ComplexScalar(a.data(), b.data(), output.data(), size);
#if defined(__aarch64__)
            else if (id == "native.sve-fp64")
                SveAdd(a.data(), b.data(), output.data(), size);
            else if (id == "native.fcma-f64")
                ComplexArm(a.data(), b.data(), output.data(), size);
#endif
            else {
                result.status = "SKIP";
                result.reason = "no-compiled-native-consumer";
                result.implemented = false;
                result.enabled = false;
                return result;
            }
            std::atomic_signal_fence(std::memory_order_seq_cst);
        }
        result.elapsed = Nanos(start);
        result.executed = true;
        if (id.rfind("native.prefetch-", 0) == 0) {
            double expected = 0;
            for (double value : a)
                expected += value;
            // Every summand is an integer multiple of 1/16 and the largest sum is
            // below 2^24. All partial sums are exactly representable in binary64;
            // exact equality here detects omitted loads without a tolerance gap.
            if (sum != expected)
                throw std::runtime_error("prefetch-scan-oracle-mismatch");
            result.checksum = Hash(&sum, sizeof(sum));
        } else {
            std::vector<double> expected(size);
            if (id == "native.complex-f64" || id == "native.fcma-f64")
                ComplexScalar(a.data(), b.data(), expected.data(), size);
            else
                ScalarAdd(a.data(), b.data(), expected.data(), size);
            // Inputs use small integer numerators over 16 and 32. Their sums and
            // complex products remain exact binary fractions, including under FMA.
            if (output != expected)
                throw std::runtime_error("vector-oracle-mismatch");
            result.checksum = Hash(output.data(), output.size() * sizeof(double));
        }
        result.extra = ",\"elements_per_iteration\":" + std::to_string(size) + ",\"solver_kernel\":false";
        return result;
    } catch (const std::exception &error) {
        // Preserve completed execution/timing if the later oracle rejects it.
        result.status = "FAIL";
        result.reason = error.what();
        return result;
    }
}
} // namespace
std::string Placement(const std::string &mode) {
    cpu_set_t before;
    CPU_ZERO(&before);
    if (sched_getaffinity(0, sizeof(before), &before) != 0)
        return "{\"status\":\"SKIP\",\"reason\":\"app-thread-affinity-unavailable\"}";
    if (!affinityCaptured) {
        originalAffinity = before;
        affinityCaptured = true;
    }
    cpu_set_t desired;
    CPU_ZERO(&desired);
    std::string metric = "system-original";
    if (mode == "system")
        desired = originalAffinity;
    else if (mode == "single" || mode == "big_cluster") {
        long highest = 0;
        const char *field = "cpu_capacity";
        const auto completeMaximum = [&](const char *property) {
            long maximum = 0;
            for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu)
                if (CPU_ISSET(cpu, &originalAffinity)) {
                    const long value = Capacity(cpu, property);
                    if (value <= 0)
                        return 0L;
                    maximum = std::max(maximum, value);
                }
            return maximum;
        };
        highest = completeMaximum(field);
        if (highest == 0) {
            field = "cpufreq/cpuinfo_max_freq";
            highest = completeMaximum(field);
        }
        metric = field;
        if (highest == 0 && mode == "big_cluster")
            return "{\"status\":\"SKIP\",\"reason\":\"complete-capacity-and-frequency-topology-unavailable\","
                   "\"current_cpus\":" +
                   CpuList(before) + "}";
        if (highest == 0)
            metric = "first-allowed-cpu-no-performance-ranking";
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu)
            if (CPU_ISSET(cpu, &originalAffinity) && (highest == 0 || Capacity(cpu, field) == highest)) {
                CPU_SET(cpu, &desired);
                if (mode == "single")
                    break;
            }
    } else
        return "{\"status\":\"FAIL\",\"reason\":\"unknown-placement-mode\"}";
    if (sched_setaffinity(0, sizeof(desired), &desired) != 0)
        return "{\"status\":\"SKIP\",\"reason\":\"sched-setaffinity-rejected\",\"errno\":" +
               std::to_string(errno) + ",\"current_cpus\":" + CpuList(before) + "}";
    cpu_set_t actual;
    CPU_ZERO(&actual);
    if (sched_getaffinity(0, sizeof(actual), &actual) != 0 || !CPU_EQUAL(&actual, &desired)) {
        const bool restored = sched_setaffinity(0, sizeof(before), &before) == 0;
        return "{\"status\":\"FAIL\",\"reason\":\"affinity-verification-failed\",\"previous_restored\":" +
               std::string(restored ? "true" : "false") + "}";
    }
    return "{\"schema_version\":1,\"status\":\"PASS\",\"mode\":" + Quote(mode) +
           ",\"tid\":" + std::to_string(gettid()) + ",\"selection_metric\":" + Quote(metric) +
           ",\"previous_cpus\":" + CpuList(before) + ",\"current_cpus\":" + CpuList(actual) +
           ",\"original_cpus\":" + CpuList(originalAffinity) +
           ",\"scope\":\"calling-thread-and-subsequently-created-workers\"}";
}
std::string Capabilities() {
    std::lock_guard<std::mutex> lock(benchmarkMutex);
    std::vector<CaseInfo> cases{
        {"native.memcpy", "libc snapshot copy", "memory", "", "", true, true},
        {"native.memset", "libc scratch clear", "memory", "", "", true, true},
        {"native.prefetch-none", "FP64 source scan without explicit prefetch", "memory", "", "", true, true},
        {"native.prefetch-4", "FP64 source scan, 256-byte prefetch", "memory", "", "native.prefetch-none",
         true, true},
        {"native.prefetch-8", "FP64 source scan, 512-byte prefetch", "memory", "", "native.prefetch-none",
         true, true},
        {"native.vector-fp64", "Compiler FP64 vector addition", "cpu", "", "", true, true},
        {"native.sve-fp64", "Predicated SVE FP64 addition", "cpu", Sve() ? "" : "SVE absent from app auxv",
         "native.vector-fp64", Sve(), true},
        {"native.complex-f64", "Compiler FP64 complex multiplication", "cpu", "", "", true, true},
        {"native.fcma-f64", "ARM FCMA FP64 complex multiplication", "cpu",
         Fcma() ? "" : "FCMA absent from app auxv", "native.complex-f64", Fcma(), true},
        {"native.crc32-scalar", "Portable checkpoint CRC32", "memory", "", "", true, true},
        {"native.crc32-arm", "ARM checkpoint CRC32", "memory", Crc() ? "" : "CRC32 absent from app auxv",
         "native.crc32-scalar", Crc(), true},
        {"native.atomic-seqcst", "Contended sequentially consistent atomics", "cpu",
         "compiler selects legal instructions", "", true, true},
        {"native.atomic-relaxed", "Contended relaxed counter atomics", "cpu",
         "counter workload only; not a publication protocol", "native.atomic-seqcst", true, true},
    };
    GpuInventory gpu;
    try {
        gpu = GpuCapabilities();
    } catch (const std::exception &error) {
        gpu.json = "{\"status\":\"FAIL\",\"reason\":" + Quote(error.what()) + "}";
    }
    cases.insert(cases.end(), gpu.cases.begin(), gpu.cases.end());
    const auto scheduling = OverlapCases(gpu);
    cases.insert(cases.end(), scheduling.begin(), scheduling.end());
    GpuInventory opencl;
    try {
        opencl = OpenClCapabilities();
    } catch (const std::exception &error) {
        opencl.json = "{\"status\":\"FAIL\",\"reason\":" + Quote(error.what()) + "}";
    }
    cases.insert(cases.end(), opencl.cases.begin(), opencl.cases.end());
    GpuInventory nnapi;
#if defined(__ANDROID__)
    try {
        nnapi = NnapiCapabilities();
    } catch (const std::exception &error) {
        nnapi.json = "{\"status\":\"FAIL\",\"reason\":" + Quote(error.what()) + "}";
    }
    cases.insert(cases.end(), nnapi.cases.begin(), nnapi.cases.end());
#else
    nnapi.json = "{\"status\":\"SKIP\",\"reason\":\"Android-NNAPI-unavailable-on-host\"}";
#endif
    std::string result = "{\"schema_version\":1,\"cpu\":" + CpuCapabilities() + ",\"vulkan\":" + gpu.json +
                         ",\"opencl\":" + opencl.json +
                         ",\"hexagon_qnn\":" + OptionalRuntime("libQnnHtp.so", "QnnInterface_getProviders") +
                         ",\"nnapi\":" + nnapi.json + ",\"cases\":[";
    for (size_t i = 0; i < cases.size(); ++i) {
        if (i)
            result += ",";
        result += cases[i].Json();
    }
    return result + "]}";
}
std::string RunCase(const std::string &id, int requested) {
    std::lock_guard<std::mutex> lock(benchmarkMutex);
    const int iterations = std::clamp(requested, 1, 32);
    try {
        if (id == "cpu-gpu-serial" || id == "cpu-gpu-overlap")
            return RunOverlap(id, iterations).Json(id);
        if (id.rfind("opencl.", 0) == 0)
            return RunOpenCl(id, iterations).Json(id);
#if defined(__ANDROID__)
        if (id.rfind("nnapi-", 0) == 0)
            return RunNnapi(id, iterations).Json(id);
#endif
        auto sample = id.rfind("gpu.", 0) == 0 ? RunGpu(id, iterations) : RunCpu(id, iterations);
        return sample.Json(id);
    } catch (const std::exception &error) {
        Sample failed;
        failed.status = "FAIL";
        failed.reason = error.what();
        failed.iterations = iterations;
        return failed.Json(id);
    }
}
} // namespace solarlab::benchmark

#if defined(__ANDROID__)
namespace {
template <class Function> jstring JniResult(JNIEnv *env, Function body) {
    try {
        const std::string value = body();
        return env->NewStringUTF(value.c_str());
    } catch (const std::exception &error) {
        const auto value =
            "{\"status\":\"FAIL\",\"reason\":" + solarlab::benchmark::Quote(error.what()) + "}";
        return env->NewStringUTF(value.c_str());
    }
}
std::string JniString(JNIEnv *env, jstring value) {
    if (!value)
        return "";
    const char *bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes)
        return "";
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}
} // namespace
extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_benchmark_NativeHardwareBench_nativeCapabilities(JNIEnv *env, jobject) {
    return JniResult(env, [] { return solarlab::benchmark::Capabilities(); });
}
extern "C" JNIEXPORT jstring JNICALL Java_com_sednalabs_solarlab_benchmark_NativeHardwareBench_nativeRunCase(
    JNIEnv *env, jobject, jstring id, jint iterations) {
    const auto name = JniString(env, id);
    return JniResult(env, [&] { return solarlab::benchmark::RunCase(name, iterations); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_benchmark_NativeHardwareBench_nativePlacement(JNIEnv *env, jobject,
                                                                          jstring mode) {
    const auto name = JniString(env, mode);
    return JniResult(env, [&] { return solarlab::benchmark::Placement(name); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_benchmark_NativeHardwareBench_nativeThreadInfo(JNIEnv *env, jobject) {
    return JniResult(env, [] { return solarlab::benchmark::CpuCapabilities(); });
}
#endif
