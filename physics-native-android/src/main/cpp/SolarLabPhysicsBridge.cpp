#include <jni.h>
#include <sys/auxv.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <arm_neon.h>
#endif

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <span>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

template <typename JArrayT, typename ElementT>
class ScopedReadOnlyArray;

template <>
class ScopedReadOnlyArray<jdoubleArray, jdouble> {
public:
    ScopedReadOnlyArray(JNIEnv* env, jdoubleArray array)
        : env_(env), array_(array), length_(array != nullptr ? env->GetArrayLength(array) : 0) {
        if (array_ != nullptr && length_ > 0) {
            data_ = env_->GetDoubleArrayElements(array_, nullptr);
        }
    }

    ~ScopedReadOnlyArray() {
        if (array_ != nullptr && data_ != nullptr) {
            env_->ReleaseDoubleArrayElements(array_, data_, JNI_ABORT);
        }
    }

    std::span<const jdouble> span() const {
        return data_ != nullptr ? std::span<const jdouble>(data_, static_cast<size_t>(length_)) : std::span<const jdouble>();
    }

private:
    JNIEnv* env_;
    jdoubleArray array_;
    jsize length_;
    jdouble* data_ = nullptr;
};

template <>
class ScopedReadOnlyArray<jintArray, jint> {
public:
    ScopedReadOnlyArray(JNIEnv* env, jintArray array)
        : env_(env), array_(array), length_(array != nullptr ? env->GetArrayLength(array) : 0) {
        if (array_ != nullptr && length_ > 0) {
            data_ = env_->GetIntArrayElements(array_, nullptr);
        }
    }

    ~ScopedReadOnlyArray() {
        if (array_ != nullptr && data_ != nullptr) {
            env_->ReleaseIntArrayElements(array_, data_, JNI_ABORT);
        }
    }

    std::span<const jint> span() const {
        return data_ != nullptr ? std::span<const jint>(data_, static_cast<size_t>(length_)) : std::span<const jint>();
    }

private:
    JNIEnv* env_;
    jintArray array_;
    jsize length_;
    jint* data_ = nullptr;
};

bool HasArm64Asimd() {
#if defined(__aarch64__) && defined(HWCAP_ASIMD)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMD) != 0;
#else
    return false;
#endif
}

enum class AccelerationScheduleHint {
    Massive,
    Tracer,
};

constexpr size_t kMaxParallelWorkers = 6;
constexpr size_t kMassiveMinTargetsPerWorker = 64;
constexpr size_t kTracerMinTargetsPerWorker = 256;
constexpr uint64_t kMassiveParallelWorkThreshold = 400000ULL;
constexpr uint64_t kTracerParallelWorkThreshold = 700000ULL;

size_t HardwareWorkerBudget() {
    const auto concurrency = std::thread::hardware_concurrency();
    if (concurrency <= 1U) return 1U;
    const auto available = std::max<size_t>(1U, static_cast<size_t>(concurrency) - 1U);
    return std::min(available, kMaxParallelWorkers);
}

size_t RecommendedWorkerCount(
    const size_t sourceCount,
    const size_t targetCount,
    const AccelerationScheduleHint scheduleHint
) {
    const auto workerBudget = HardwareWorkerBudget();
    if (workerBudget <= 1U) return 1U;
    if (sourceCount == 0U || targetCount == 0U) return 1U;

    const auto minTargetsPerWorker = scheduleHint == AccelerationScheduleHint::Tracer
        ? kTracerMinTargetsPerWorker
        : kMassiveMinTargetsPerWorker;
    const auto workThreshold = scheduleHint == AccelerationScheduleHint::Tracer
        ? kTracerParallelWorkThreshold
        : kMassiveParallelWorkThreshold;

    if (targetCount < minTargetsPerWorker * 2U) return 1U;
    if (static_cast<uint64_t>(sourceCount) * static_cast<uint64_t>(targetCount) < workThreshold) return 1U;

    const auto targetsBoundWorkers = std::max<size_t>(1U, targetCount / minTargetsPerWorker);
    return std::max<size_t>(1U, std::min(workerBudget, targetsBoundWorkers));
}

std::string CpuBackendSummary() {
    std::ostringstream summary;
    summary << "cpu=";
#if defined(__aarch64__)
    summary << "arm64";
#else
    summary << "generic";
#endif

#if defined(__aarch64__)
    const auto hwcap = getauxval(AT_HWCAP);
#if defined(AT_HWCAP2)
    const auto hwcap2 = getauxval(AT_HWCAP2);
#else
    const auto hwcap2 = static_cast<unsigned long>(0);
#endif
    summary << " simd=";
#if defined(HWCAP_ASIMD)
    summary << ((hwcap & HWCAP_ASIMD) != 0 ? "asimd" : "scalar");
#else
    summary << "unknown";
#endif
#if defined(HWCAP_ASIMDDP)
    if ((hwcap & HWCAP_ASIMDDP) != 0) summary << "+asimddp";
#endif
#if defined(HWCAP_SVE)
    if ((hwcap & HWCAP_SVE) != 0) summary << "+sve";
#endif
#if defined(HWCAP2_SVE2)
    if ((hwcap2 & HWCAP2_SVE2) != 0) summary << "+sve2";
#endif
    summary << " active=" << (HasArm64Asimd() ? "neon" : "scalar");
#endif
    summary << " sched=adaptive-tiles";
    summary << " workers<=" << HardwareWorkerBudget();

    return summary.str();
}

std::vector<jdouble> ComputeAccelerationsScalar(
    std::span<const jint> sourceBodyIndices,
    std::span<const jdouble> sourceMassesKg,
    std::span<const jdouble> sourcePosX,
    std::span<const jdouble> sourcePosY,
    std::span<const jdouble> sourcePosZ,
    std::span<const jint> targetBodyIndices,
    std::span<const jdouble> targetPosX,
    std::span<const jdouble> targetPosY,
    std::span<const jdouble> targetPosZ,
    const double gravitationalConstant,
    const double softeningSquared,
    const bool skipSelf
) {
    const auto sourceCount = std::min({
        sourceBodyIndices.size(),
        sourceMassesKg.size(),
        sourcePosX.size(),
        sourcePosY.size(),
        sourcePosZ.size(),
    });
    const auto targetCount = std::min({
        targetBodyIndices.size(),
        targetPosX.size(),
        targetPosY.size(),
        targetPosZ.size(),
    });

    std::vector<jdouble> packed(targetCount * 3U, 0.0);
    for (size_t targetIndex = 0; targetIndex < targetCount; ++targetIndex) {
        const auto bodyIndex = targetBodyIndices[targetIndex];
        const auto bodyX = targetPosX[targetIndex];
        const auto bodyY = targetPosY[targetIndex];
        const auto bodyZ = targetPosZ[targetIndex];
        double accelerationX = 0.0;
        double accelerationY = 0.0;
        double accelerationZ = 0.0;

        for (size_t sourceIndex = 0; sourceIndex < sourceCount; ++sourceIndex) {
            if (skipSelf && sourceBodyIndices[sourceIndex] == bodyIndex) {
                continue;
            }

            const auto dx = sourcePosX[sourceIndex] - bodyX;
            const auto dy = sourcePosY[sourceIndex] - bodyY;
            const auto dz = sourcePosZ[sourceIndex] - bodyZ;
            const auto distanceSquared = (dx * dx) + (dy * dy) + (dz * dz) + softeningSquared;
            if (distanceSquared == 0.0) {
                continue;
            }

            const auto invDistance = 1.0 / std::sqrt(distanceSquared);
            const auto invDistanceCubed = invDistance * invDistance * invDistance;
            const auto scale = gravitationalConstant * sourceMassesKg[sourceIndex] * invDistanceCubed;

            accelerationX += dx * scale;
            accelerationY += dy * scale;
            accelerationZ += dz * scale;
        }

        const auto outputOffset = static_cast<std::size_t>(targetIndex) * 3U;
        packed[outputOffset] = accelerationX;
        packed[outputOffset + 1] = accelerationY;
        packed[outputOffset + 2] = accelerationZ;
    }

    return packed;
}

#if defined(__aarch64__)
std::vector<jdouble> ComputeAccelerationsNeon(
    std::span<const jint> sourceBodyIndices,
    std::span<const jdouble> sourceMassesKg,
    std::span<const jdouble> sourcePosX,
    std::span<const jdouble> sourcePosY,
    std::span<const jdouble> sourcePosZ,
    std::span<const jint> targetBodyIndices,
    std::span<const jdouble> targetPosX,
    std::span<const jdouble> targetPosY,
    std::span<const jdouble> targetPosZ,
    const double gravitationalConstant,
    const double softeningSquared,
    const bool skipSelf
) {
    const auto sourceCount = std::min({
        sourceBodyIndices.size(),
        sourceMassesKg.size(),
        sourcePosX.size(),
        sourcePosY.size(),
        sourcePosZ.size(),
    });
    const auto targetCount = std::min({
        targetBodyIndices.size(),
        targetPosX.size(),
        targetPosY.size(),
        targetPosZ.size(),
    });

    std::vector<jdouble> packed(targetCount * 3U, 0.0);
    const float64x2_t softening = vdupq_n_f64(softeningSquared);
    const float64x2_t gravitational = vdupq_n_f64(gravitationalConstant);

    for (size_t targetIndex = 0; targetIndex < targetCount; ++targetIndex) {
        const auto bodyIndex = targetBodyIndices[targetIndex];
        const float64x2_t targetX = vdupq_n_f64(targetPosX[targetIndex]);
        const float64x2_t targetY = vdupq_n_f64(targetPosY[targetIndex]);
        const float64x2_t targetZ = vdupq_n_f64(targetPosZ[targetIndex]);
        float64x2_t accumulatedX = vdupq_n_f64(0.0);
        float64x2_t accumulatedY = vdupq_n_f64(0.0);
        float64x2_t accumulatedZ = vdupq_n_f64(0.0);

        size_t sourceIndex = 0;
        for (; sourceIndex + 1 < sourceCount; sourceIndex += 2) {
            const float64x2_t dx = vsubq_f64(vld1q_f64(sourcePosX.data() + sourceIndex), targetX);
            const float64x2_t dy = vsubq_f64(vld1q_f64(sourcePosY.data() + sourceIndex), targetY);
            const float64x2_t dz = vsubq_f64(vld1q_f64(sourcePosZ.data() + sourceIndex), targetZ);

            float64x2_t distanceSquared = softening;
            distanceSquared = vaddq_f64(distanceSquared, vmulq_f64(dx, dx));
            distanceSquared = vaddq_f64(distanceSquared, vmulq_f64(dy, dy));
            distanceSquared = vaddq_f64(distanceSquared, vmulq_f64(dz, dz));

            double distanceSquaredValues[2];
            vst1q_f64(distanceSquaredValues, distanceSquared);
            double scaleValues[2] = {0.0, 0.0};
            for (size_t lane = 0; lane < 2; ++lane) {
                const size_t laneIndex = sourceIndex + lane;
                const double laneDistanceSquared = distanceSquaredValues[lane];
                if (laneDistanceSquared == 0.0) {
                    continue;
                }
                if (skipSelf && sourceBodyIndices[laneIndex] == bodyIndex) {
                    continue;
                }
                const double invDistance = 1.0 / std::sqrt(laneDistanceSquared);
                scaleValues[lane] = sourceMassesKg[laneIndex] * invDistance * invDistance * invDistance;
            }

            const float64x2_t scale = vmulq_f64(gravitational, vld1q_f64(scaleValues));
            accumulatedX = vaddq_f64(accumulatedX, vmulq_f64(dx, scale));
            accumulatedY = vaddq_f64(accumulatedY, vmulq_f64(dy, scale));
            accumulatedZ = vaddq_f64(accumulatedZ, vmulq_f64(dz, scale));
        }

        double accelerationX = vgetq_lane_f64(accumulatedX, 0) + vgetq_lane_f64(accumulatedX, 1);
        double accelerationY = vgetq_lane_f64(accumulatedY, 0) + vgetq_lane_f64(accumulatedY, 1);
        double accelerationZ = vgetq_lane_f64(accumulatedZ, 0) + vgetq_lane_f64(accumulatedZ, 1);

        for (; sourceIndex < sourceCount; ++sourceIndex) {
            if (skipSelf && sourceBodyIndices[sourceIndex] == bodyIndex) {
                continue;
            }
            const auto dx = sourcePosX[sourceIndex] - targetPosX[targetIndex];
            const auto dy = sourcePosY[sourceIndex] - targetPosY[targetIndex];
            const auto dz = sourcePosZ[sourceIndex] - targetPosZ[targetIndex];
            const auto distanceSquared = (dx * dx) + (dy * dy) + (dz * dz) + softeningSquared;
            if (distanceSquared == 0.0) {
                continue;
            }
            const auto invDistance = 1.0 / std::sqrt(distanceSquared);
            const auto invDistanceCubed = invDistance * invDistance * invDistance;
            const auto scale = gravitationalConstant * sourceMassesKg[sourceIndex] * invDistanceCubed;
            accelerationX += dx * scale;
            accelerationY += dy * scale;
            accelerationZ += dz * scale;
        }

        const auto outputOffset = targetIndex * 3U;
        packed[outputOffset] = accelerationX;
        packed[outputOffset + 1] = accelerationY;
        packed[outputOffset + 2] = accelerationZ;
    }

    return packed;
}
#endif

std::vector<jdouble> ComputeAccelerations(
    std::span<const jint> sourceBodyIndices,
    std::span<const jdouble> sourceMassesKg,
    std::span<const jdouble> sourcePosX,
    std::span<const jdouble> sourcePosY,
    std::span<const jdouble> sourcePosZ,
    std::span<const jint> targetBodyIndices,
    std::span<const jdouble> targetPosX,
    std::span<const jdouble> targetPosY,
    std::span<const jdouble> targetPosZ,
    const double gravitationalConstant,
    const double softeningSquared,
    const bool skipSelf,
    const AccelerationScheduleHint scheduleHint
) {
    const auto sourceCount = std::min({
        sourceBodyIndices.size(),
        sourceMassesKg.size(),
        sourcePosX.size(),
        sourcePosY.size(),
        sourcePosZ.size(),
    });
    const auto targetCount = std::min({
        targetBodyIndices.size(),
        targetPosX.size(),
        targetPosY.size(),
        targetPosZ.size(),
    });
    const auto workerCount = RecommendedWorkerCount(sourceCount, targetCount, scheduleHint);
    auto computeTile = [&](const size_t start, const size_t count) {
        const auto targetIndexSlice = targetBodyIndices.subspan(start, count);
        const auto targetXSlice = targetPosX.subspan(start, count);
        const auto targetYSlice = targetPosY.subspan(start, count);
        const auto targetZSlice = targetPosZ.subspan(start, count);
#if defined(__aarch64__)
        if (HasArm64Asimd()) {
            return ComputeAccelerationsNeon(
                sourceBodyIndices,
                sourceMassesKg,
                sourcePosX,
                sourcePosY,
                sourcePosZ,
                targetIndexSlice,
                targetXSlice,
                targetYSlice,
                targetZSlice,
                gravitationalConstant,
                softeningSquared,
                skipSelf);
        }
#endif
        return ComputeAccelerationsScalar(
            sourceBodyIndices,
            sourceMassesKg,
            sourcePosX,
            sourcePosY,
            sourcePosZ,
            targetIndexSlice,
            targetXSlice,
            targetYSlice,
            targetZSlice,
            gravitationalConstant,
            softeningSquared,
            skipSelf);
    };

    if (workerCount <= 1U || targetCount <= 1U) {
        return computeTile(0U, targetCount);
    }

    std::vector<jdouble> packed(targetCount * 3U, 0.0);
    const auto tileSize = std::max<size_t>(1U, (targetCount + workerCount - 1U) / workerCount);
    std::vector<std::thread> workers;
    workers.reserve(workerCount - 1U);
    size_t tileStart = 0U;

    for (size_t workerIndex = 0U; workerIndex + 1U < workerCount && tileStart < targetCount; ++workerIndex) {
        const auto start = tileStart;
        const auto count = std::min(tileSize, targetCount - start);
        workers.emplace_back([&, start, count]() {
            auto tilePacked = computeTile(start, count);
            std::copy(tilePacked.begin(), tilePacked.end(), packed.begin() + static_cast<std::ptrdiff_t>(start * 3U));
        });
        tileStart += count;
    }

    if (tileStart < targetCount) {
        auto tilePacked = computeTile(tileStart, targetCount - tileStart);
        std::copy(tilePacked.begin(), tilePacked.end(), packed.begin() + static_cast<std::ptrdiff_t>(tileStart * 3U));
    }

    for (auto& worker : workers) {
        worker.join();
    }
    return packed;
}

jdoubleArray ToJDoubleArray(JNIEnv* env, const std::vector<jdouble>& values) {
    auto* result = env->NewDoubleArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_physics_nativeandroid_NativePhysicsBridge_nativeCpuBackendSummary(
    JNIEnv* env,
    jobject /* thiz */
) {
    const auto summary = CpuBackendSummary();
    return env->NewStringUTF(summary.c_str());
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sednalabs_solarlab_physics_nativeandroid_NativePhysicsBridge_nativeComputeMassiveAccelerationsPacked(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray sourceBodyIndices,
    jdoubleArray sourceMassesKg,
    jdoubleArray sourcePosX,
    jdoubleArray sourcePosY,
    jdoubleArray sourcePosZ,
    jintArray targetBodyIndices,
    jdoubleArray targetPosX,
    jdoubleArray targetPosY,
    jdoubleArray targetPosZ,
    jdouble gravitationalConstant,
    jdouble softeningSquared
) {
    ScopedReadOnlyArray<jintArray, jint> sourceIndices(env, sourceBodyIndices);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceMasses(env, sourceMassesKg);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceX(env, sourcePosX);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceY(env, sourcePosY);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceZ(env, sourcePosZ);
    ScopedReadOnlyArray<jintArray, jint> targetIndices(env, targetBodyIndices);
    ScopedReadOnlyArray<jdoubleArray, jdouble> targetX(env, targetPosX);
    ScopedReadOnlyArray<jdoubleArray, jdouble> targetY(env, targetPosY);
    ScopedReadOnlyArray<jdoubleArray, jdouble> targetZ(env, targetPosZ);
    const auto packed = ComputeAccelerations(
        sourceIndices.span(),
        sourceMasses.span(),
        sourceX.span(),
        sourceY.span(),
        sourceZ.span(),
        targetIndices.span(),
        targetX.span(),
        targetY.span(),
        targetZ.span(),
        gravitationalConstant,
        softeningSquared,
        true,
        AccelerationScheduleHint::Massive
    );
    return ToJDoubleArray(env, packed);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sednalabs_solarlab_physics_nativeandroid_NativePhysicsBridge_nativeComputeTracerAccelerationsPacked(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray sourceBodyIndices,
    jdoubleArray sourceMassesKg,
    jdoubleArray sourcePosX,
    jdoubleArray sourcePosY,
    jdoubleArray sourcePosZ,
    jintArray targetBodyIndices,
    jdoubleArray targetPosX,
    jdoubleArray targetPosY,
    jdoubleArray targetPosZ,
    jdouble gravitationalConstant,
    jdouble softeningSquared
) {
    ScopedReadOnlyArray<jintArray, jint> sourceIndices(env, sourceBodyIndices);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceMasses(env, sourceMassesKg);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceX(env, sourcePosX);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceY(env, sourcePosY);
    ScopedReadOnlyArray<jdoubleArray, jdouble> sourceZ(env, sourcePosZ);
    ScopedReadOnlyArray<jintArray, jint> targetIndices(env, targetBodyIndices);
    ScopedReadOnlyArray<jdoubleArray, jdouble> targetX(env, targetPosX);
    ScopedReadOnlyArray<jdoubleArray, jdouble> targetY(env, targetPosY);
    ScopedReadOnlyArray<jdoubleArray, jdouble> targetZ(env, targetPosZ);
    const auto packed = ComputeAccelerations(
        sourceIndices.span(),
        sourceMasses.span(),
        sourceX.span(),
        sourceY.span(),
        sourceZ.span(),
        targetIndices.span(),
        targetX.span(),
        targetY.span(),
        targetZ.span(),
        gravitationalConstant,
        softeningSquared,
        true,
        AccelerationScheduleHint::Tracer
    );
    return ToJDoubleArray(env, packed);
}
