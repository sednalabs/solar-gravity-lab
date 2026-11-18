#include "BenchmarkOpenCl.h"
#include <algorithm>
#include <array>
#include <cstring>
#include <dlfcn.h>
#include <stdexcept>

namespace solarlab::benchmark {
namespace {
// Public OpenCL 1.2 ABI, resolved from the app-visible loader only. The Android
// namespace may intentionally hide this optional vendor API. No private path,
// root access, ICD injection, or vendor-driver assumption is involved.
using ClInt = int32_t;
using ClUint = uint32_t;
using ClUlong = uint64_t;
using Platform = void *;
using Device = void *;
using Context = void *;
using Queue = void *;
using Program = void *;
using Kernel = void *;
using Memory = void *;
using Event = void *;
constexpr ClUlong kGpu = 1ULL << 2, kReadOnly = 1ULL << 2, kWriteOnly = 1ULL << 1, kCopyHost = 1ULL << 5,
                  kProfiling = 1ULL << 1;
constexpr ClUint kDeviceName = 0x102b, kDeviceVersion = 0x102f, kDriverVersion = 0x102d, kExtensions = 0x1030,
                 kDoubleConfig = 0x1032;
constexpr ClUint kBuildLog = 0x1183, kProfileStart = 0x1282, kProfileEnd = 0x1283;
constexpr uint32_t kCount = 16387;
void Check(ClInt result, const char *operation) {
    if (result != 0)
        throw std::runtime_error(std::string(operation) + ":cl-result=" + std::to_string(result));
}
struct Api {
    void *library = nullptr;
    Device device = nullptr;
    bool fp64 = false;
    std::string reason, name, version, driver, extensions;
    ClInt (*platforms)(ClUint, Platform *, ClUint *) = nullptr;
    ClInt (*devices)(Platform, ClUlong, ClUint, Device *, ClUint *) = nullptr;
    ClInt (*deviceInfo)(Device, ClUint, size_t, void *, size_t *) = nullptr;
    Context (*createContext)(const intptr_t *, ClUint, const Device *,
                             void (*)(const char *, const void *, size_t, void *), void *, ClInt *) = nullptr;
    Queue (*createQueue)(Context, Device, ClUlong, ClInt *) = nullptr;
    Program (*createProgram)(Context, ClUint, const char **, const size_t *, ClInt *) = nullptr;
    ClInt (*buildProgram)(Program, ClUint, const Device *, const char *, void (*)(Program, void *),
                          void *) = nullptr;
    ClInt (*buildInfo)(Program, Device, ClUint, size_t, void *, size_t *) = nullptr;
    Kernel (*createKernel)(Program, const char *, ClInt *) = nullptr;
    Memory (*createBuffer)(Context, ClUlong, size_t, void *, ClInt *) = nullptr;
    ClInt (*setArgument)(Kernel, ClUint, size_t, const void *) = nullptr;
    ClInt (*enqueue)(Queue, Kernel, ClUint, const size_t *, const size_t *, const size_t *, ClUint,
                     const Event *, Event *) = nullptr;
    ClInt (*finish)(Queue) = nullptr;
    ClInt (*read)(Queue, Memory, ClUint, size_t, size_t, void *, ClUint, const Event *, Event *) = nullptr;
    ClInt (*profile)(Event, ClUint, size_t, void *, size_t *) = nullptr;
    ClInt (*releaseContext)(Context) = nullptr;
    ClInt (*releaseQueue)(Queue) = nullptr;
    ClInt (*releaseProgram)(Program) = nullptr;
    ClInt (*releaseKernel)(Kernel) = nullptr;
    ClInt (*releaseMemory)(Memory) = nullptr;
    ClInt (*releaseEvent)(Event) = nullptr;
    ~Api() {
        if (library)
            dlclose(library);
    }
    Api() = default;
    Api(const Api &) = delete;
    std::string Info(ClUint field) {
        size_t size = 0;
        if (deviceInfo(device, field, 0, nullptr, &size) != 0 || size == 0 || size > 65536)
            return "";
        std::vector<char> value(size);
        if (deviceInfo(device, field, size, value.data(), nullptr) != 0)
            return "";
        while (!value.empty() && value.back() == 0)
            value.pop_back();
        return std::string(value.begin(), value.end());
    }
    bool Initialize() {
        library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) {
            reason = "OpenCL-loader-not-accessible-in-app-namespace";
            return false;
        }
#define LOAD(field, name)                                                                                    \
    field = reinterpret_cast<decltype(field)>(dlsym(library, name));                                         \
    if (!field) {                                                                                            \
        reason = "required-OpenCL-entrypoint-absent:" name;                                                  \
        return false;                                                                                        \
    }
        LOAD(platforms, "clGetPlatformIDs")
        LOAD(devices, "clGetDeviceIDs")
        LOAD(deviceInfo, "clGetDeviceInfo") LOAD(createContext, "clCreateContext")
            LOAD(createQueue, "clCreateCommandQueue") LOAD(createProgram, "clCreateProgramWithSource")
                LOAD(buildProgram, "clBuildProgram") LOAD(buildInfo, "clGetProgramBuildInfo")
                    LOAD(createKernel, "clCreateKernel") LOAD(createBuffer, "clCreateBuffer")
                        LOAD(setArgument, "clSetKernelArg") LOAD(enqueue, "clEnqueueNDRangeKernel")
                            LOAD(finish, "clFinish") LOAD(read, "clEnqueueReadBuffer")
                                LOAD(profile, "clGetEventProfilingInfo")
                                    LOAD(releaseContext, "clReleaseContext")
                                        LOAD(releaseQueue, "clReleaseCommandQueue")
                                            LOAD(releaseProgram, "clReleaseProgram")
                                                LOAD(releaseKernel, "clReleaseKernel")
                                                    LOAD(releaseMemory, "clReleaseMemObject")
                                                        LOAD(releaseEvent, "clReleaseEvent")
#undef LOAD
                                                            ClUint count = 0;
        const auto status = platforms(0, nullptr, &count);
        if (status != 0 || count == 0) {
            reason = "no-app-visible-OpenCL-platform";
            return false;
        }
        if (count > 16) {
            reason = "OpenCL-platform-count-exceeds-probe-bound";
            return false;
        }
        std::vector<Platform> list(count);
        Check(platforms(count, list.data(), nullptr), "enumerate-platforms");
        for (auto platform : list) {
            ClUint deviceCount = 0;
            if (devices(platform, kGpu, 0, nullptr, &deviceCount) != 0 || deviceCount == 0)
                continue;
            if (deviceCount > 64)
                continue;
            std::vector<Device> gpu(deviceCount);
            Check(devices(platform, kGpu, deviceCount, gpu.data(), nullptr), "enumerate-GPU-devices");
            device = gpu.front();
            break;
        }
        if (!device) {
            reason = "no-app-visible-OpenCL-GPU";
            return false;
        }
        name = Info(kDeviceName);
        version = Info(kDeviceVersion);
        driver = Info(kDriverVersion);
        extensions = Info(kExtensions);
        ClUlong config = 0;
        fp64 = deviceInfo(device, kDoubleConfig, sizeof(config), &config, nullptr) == 0 && config != 0 &&
               extensions.find("cl_khr_fp64") != std::string::npos;
        return true;
    }
};
struct Resources {
    Api &api;
    Context context = nullptr;
    Queue queue = nullptr;
    Program program = nullptr;
    Kernel kernel = nullptr;
    Memory input = nullptr, output = nullptr;
    std::vector<Event> events;
    explicit Resources(Api &selected) : api(selected) {}
    ~Resources() {
        if (queue)
            api.finish(queue);
        for (auto event : events)
            if (event)
                api.releaseEvent(event);
        if (output)
            api.releaseMemory(output);
        if (input)
            api.releaseMemory(input);
        if (kernel)
            api.releaseKernel(kernel);
        if (program)
            api.releaseProgram(program);
        if (queue)
            api.releaseQueue(queue);
        if (context)
            api.releaseContext(context);
    }
};
constexpr char kKernel[] = R"CL(
#ifdef USE_FP64
#pragma OPENCL EXTENSION cl_khr_fp64 : enable
typedef double real;typedef double3 real3;typedef double4 real4;
#define CONVERT convert_double3
#else
typedef float real;typedef float3 real3;typedef float4 real4;
#define CONVERT convert_float3
#endif
__kernel void gravity(__global const float4* p,__global real4* out,uint count){
    uint i=get_global_id(0);if(i>=count)return;
    real3 position=CONVERT(p[i].xyz),acc=(real3)(0);
    const real3 sources[3]={(real3)(0.5,-0.25,0.125),(real3)(-0.75,0.5,-0.25),(real3)(0.125,0.75,0.5)};
    const real mu[3]={(real)1,(real)0.0625,(real)0.015625};
    for(uint s=0;s<3;++s){real3 d=sources[s]-position;real r2=dot(d,d)+(real)0.0009765625;real inv=(real)1/sqrt(r2);acc+=d*(mu[s]*inv*inv*inv);}
    out[i]=(real4)(acc,0);
}
)CL";
} // namespace
GpuInventory OpenClCapabilities() {
    Api api;
    const bool available = api.Initialize();
    GpuInventory result;
    result.json = "{\"status\":" + Quote(available ? "PASS" : "SKIP") + ",\"reason\":" + Quote(api.reason) +
                  ",\"device\":" + Quote(api.name) + ",\"version\":" + Quote(api.version) +
                  ",\"driver\":" + Quote(api.driver) + ",\"extensions\":" + Quote(api.extensions) +
                  ",\"fp64\":" + (api.fp64 ? "true" : "false") + ",\"enabled\":false,\"executed\":false}";
    result.cases = {
        {"opencl.passive-fp32", "OpenCL FP32 passive acceleration", "gpu", api.reason, "gpu.passive-fp32",
         available, true},
        {"opencl.passive-fp64", "OpenCL FP64 passive acceleration", "gpu",
         available && !api.fp64 ? "cl_khr_fp64-unavailable" : api.reason, "opencl.passive-fp32",
         available && api.fp64, true},
    };
    return result;
}
Sample RunOpenCl(const std::string &id, int iterations) {
    // The loader and selected GPU are limited to this app's public namespace.
    // Resources owns every OpenCL handle until queue completion; no renderer
    // context, authoritative physics buffer or private vendor path is reused.
    Sample result;
    result.iterations = std::clamp(iterations, 1, 32);
    result.scope = "enqueue-and-finish-excluding-runtime-compile-upload-readback-and-oracle";
    try {
        const auto setup = Clock::now();
        Api api;
        if (!api.Initialize()) {
            result.status = "SKIP";
            result.reason = api.reason;
            return result;
        }
        const bool fp64 = id == "opencl.passive-fp64";
        if (id != "opencl.passive-fp32" && !fp64) {
            result.status = "SKIP";
            result.reason = "unknown-OpenCL-case";
            result.implemented = false;
            return result;
        }
        if (fp64 && !api.fp64) {
            result.status = "SKIP";
            result.reason = "cl_khr_fp64-unavailable";
            return result;
        }
        Resources resources(api);
        ClInt error = 0;
        resources.context = api.createContext(nullptr, 1, &api.device, nullptr, nullptr, &error);
        Check(error, "create-OpenCL-context");
        resources.queue = api.createQueue(resources.context, api.device, kProfiling, &error);
        Check(error, "create-OpenCL-queue");
        constexpr size_t sourceLength = sizeof(kKernel) - 1;
        const char *kernelSource = kKernel;
        resources.program = api.createProgram(resources.context, 1, &kernelSource, &sourceLength, &error);
        Check(error, "create-OpenCL-program");
        const auto buildStart = Clock::now();
        // Compile this known source without fast-relaxed math. Compilation is
        // reported separately and excluded from the submitted kernel interval.
        const ClInt buildResult =
            api.buildProgram(resources.program, 1, &api.device,
                             fp64 ? "-cl-std=CL1.2 -DUSE_FP64=1" : "-cl-std=CL1.2", nullptr, nullptr);
        const uint64_t buildNs = Nanos(buildStart);
        if (buildResult != 0) {
            size_t length = 0;
            api.buildInfo(resources.program, api.device, kBuildLog, 0, nullptr, &length);
            length = std::min<size_t>(length, 65536);
            std::vector<char> log(length + 1, 0);
            if (length)
                api.buildInfo(resources.program, api.device, kBuildLog, length, log.data(), nullptr);
            throw std::runtime_error("OpenCL-build-failed:" + std::string(log.data()).substr(0, 4096));
        }
        resources.kernel = api.createKernel(resources.program, "gravity", &error);
        Check(error, "create-OpenCL-kernel");
        std::vector<float> positions(kCount * 4);
        for (uint32_t i = 0; i < kCount; ++i) {
            positions[i * 4] = static_cast<float>(i % 251) / 128 - 1;
            positions[i * 4 + 1] = static_cast<float>((i * 17) % 253) / 128 - 1;
            positions[i * 4 + 2] = static_cast<float>((i * 31) % 257) / 128 - 1;
        }
        const size_t outputBytes = kCount * 4 * (fp64 ? sizeof(double) : sizeof(float));
        resources.input = api.createBuffer(resources.context, kReadOnly | kCopyHost,
                                           positions.size() * sizeof(float), positions.data(), &error);
        Check(error, "create-OpenCL-input");
        resources.output = api.createBuffer(resources.context, kWriteOnly, outputBytes, nullptr, &error);
        Check(error, "create-OpenCL-output");
        Check(api.setArgument(resources.kernel, 0, sizeof(resources.input), &resources.input), "set-input");
        Check(api.setArgument(resources.kernel, 1, sizeof(resources.output), &resources.output),
              "set-output");
        Check(api.setArgument(resources.kernel, 2, sizeof(kCount), &kCount), "set-count");
        const uint64_t setupNs = Nanos(setup);
        const size_t global = (kCount + 63U) / 64U * 64U;
        resources.events.resize(static_cast<size_t>(result.iterations));
        result.enabled = true;
        const auto start = Clock::now();
        for (int i = 0; i < result.iterations; ++i)
            Check(api.enqueue(resources.queue, resources.kernel, 1, nullptr, &global, nullptr, 0, nullptr,
                              &resources.events[static_cast<size_t>(i)]),
                  "enqueue-OpenCL-kernel");
        Check(api.finish(resources.queue), "finish-OpenCL-kernels");
        result.elapsed = Nanos(start);
        result.executed = true;
        std::vector<float> output32(fp64 ? 0 : kCount * 4);
        std::vector<double> output64(fp64 ? kCount * 4 : 0);
        void *output = fp64 ? static_cast<void *>(output64.data()) : static_cast<void *>(output32.data());
        const auto readStart = Clock::now();
        Check(api.read(resources.queue, resources.output, 1, 0, outputBytes, output, 0, nullptr, nullptr),
              "read-OpenCL-output");
        const uint64_t readNs = Nanos(readStart);
        ClUlong gpuStart = 0, gpuEnd = 0;
        const bool profileValid =
            api.profile(resources.events.front(), kProfileStart, sizeof(gpuStart), &gpuStart, nullptr) == 0 &&
            api.profile(resources.events.back(), kProfileEnd, sizeof(gpuEnd), &gpuEnd, nullptr) == 0 &&
            gpuEnd >= gpuStart;
        constexpr std::array<std::array<double, 4>, 3> sources{
            {{0.5, -0.25, 0.125, 1}, {-0.75, 0.5, -0.25, 0.0625}, {0.125, 0.75, 0.5, 0.015625}}};
        for (uint32_t i = 0; i < kCount; ++i) {
            std::array<double, 4> expected{};
            for (const auto &s : sources) {
                const double x = s[0] - positions[i * 4], y = s[1] - positions[i * 4 + 1],
                             z = s[2] - positions[i * 4 + 2];
                const double inv = 1 / std::sqrt(x * x + y * y + z * z + 0.0009765625),
                             scale = s[3] * inv * inv * inv;
                expected[0] += x * scale;
                expected[1] += y * scale;
                expected[2] += z * scale;
            }
            for (size_t c = 0; c < 4; ++c) {
                const double actual = fp64 ? output64[i * 4 + c] : output32[i * 4 + c];
                if (!std::isfinite(actual))
                    throw std::runtime_error("nonfinite-OpenCL-output");
                result.error = std::max(result.error, std::abs(actual - expected[c]) /
                                                          std::max(1.0, std::abs(expected[c])));
            }
        }
        const double tolerance = fp64 ? 1e-11 : 2e-4;
        if (result.error > tolerance) {
            result.status = "FAIL";
            result.reason = "OpenCL-FP64-CPU-oracle-mismatch";
        }
        result.checksum = Hash(output, outputBytes);
        result.scope = "enqueue-and-finish-excluding-runtime-compile-upload-readback-and-oracle";
        result.extra = ",\"device\":" + Quote(api.name) + ",\"driver\":" + Quote(api.driver) +
                       ",\"body_count\":" + std::to_string(kCount) +
                       ",\"source_count\":3,\"setup_ns\":" + std::to_string(setupNs) +
                       ",\"build_ns\":" + std::to_string(buildNs) +
                       ",\"readback_ns\":" + std::to_string(readNs) +
                       ",\"gpu_timestamp_valid\":" + (profileValid ? "true" : "false") +
                       ",\"gpu_elapsed_ns\":" + (profileValid ? std::to_string(gpuEnd - gpuStart) : "null") +
                       ",\"authority\":\"isolated-forecast-only\"";
        return result;
    } catch (const std::exception &error) {
        result.status = "FAIL";
        result.reason = error.what();
        return result;
    }
}
} // namespace solarlab::benchmark
