#include <jni.h>
#include <sys/auxv.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

#include <cmath>
#include <sstream>
#include <string>
#include <vector>

namespace {

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
#endif

    return summary.str();
}

std::vector<jdouble> ComputeAccelerations(
    JNIEnv* env,
    jintArray sourceBodyIndices,
    jdoubleArray sourceMassesKg,
    jdoubleArray sourcePosX,
    jdoubleArray sourcePosY,
    jdoubleArray sourcePosZ,
    jintArray targetBodyIndices,
    jdoubleArray targetPosX,
    jdoubleArray targetPosY,
    jdoubleArray targetPosZ,
    const double gravitationalConstant,
    const double softeningSquared,
    const bool skipSelf
) {
    const auto sourceCount = static_cast<jsize>(env->GetArrayLength(sourceBodyIndices));
    const auto targetCount = static_cast<jsize>(env->GetArrayLength(targetBodyIndices));

    std::vector<jint> sourceIndices(sourceCount);
    std::vector<jdouble> sourceMasses(sourceCount);
    std::vector<jdouble> sourceX(sourceCount);
    std::vector<jdouble> sourceY(sourceCount);
    std::vector<jdouble> sourceZ(sourceCount);
    std::vector<jint> targetIndices(targetCount);
    std::vector<jdouble> targetX(targetCount);
    std::vector<jdouble> targetY(targetCount);
    std::vector<jdouble> targetZ(targetCount);
    env->GetIntArrayRegion(sourceBodyIndices, 0, sourceCount, sourceIndices.data());
    env->GetDoubleArrayRegion(sourceMassesKg, 0, sourceCount, sourceMasses.data());
    env->GetDoubleArrayRegion(sourcePosX, 0, sourceCount, sourceX.data());
    env->GetDoubleArrayRegion(sourcePosY, 0, sourceCount, sourceY.data());
    env->GetDoubleArrayRegion(sourcePosZ, 0, sourceCount, sourceZ.data());
    env->GetIntArrayRegion(targetBodyIndices, 0, targetCount, targetIndices.data());
    env->GetDoubleArrayRegion(targetPosX, 0, targetCount, targetX.data());
    env->GetDoubleArrayRegion(targetPosY, 0, targetCount, targetY.data());
    env->GetDoubleArrayRegion(targetPosZ, 0, targetCount, targetZ.data());

    std::vector<jdouble> packed(static_cast<std::size_t>(targetCount) * 3U, 0.0);
    for (jsize targetIndex = 0; targetIndex < targetCount; ++targetIndex) {
        const auto bodyIndex = targetIndices[targetIndex];
        const auto bodyX = targetX[targetIndex];
        const auto bodyY = targetY[targetIndex];
        const auto bodyZ = targetZ[targetIndex];
        double accelerationX = 0.0;
        double accelerationY = 0.0;
        double accelerationZ = 0.0;

        for (jsize sourceIndex = 0; sourceIndex < sourceCount; ++sourceIndex) {
            if (skipSelf && sourceIndices[sourceIndex] == bodyIndex) {
                continue;
            }

            const auto dx = sourceX[sourceIndex] - bodyX;
            const auto dy = sourceY[sourceIndex] - bodyY;
            const auto dz = sourceZ[sourceIndex] - bodyZ;
            const auto distanceSquared = (dx * dx) + (dy * dy) + (dz * dz) + softeningSquared;
            if (distanceSquared == 0.0) {
                continue;
            }

            const auto invDistance = 1.0 / std::sqrt(distanceSquared);
            const auto invDistanceCubed = invDistance * invDistance * invDistance;
            const auto scale = gravitationalConstant * sourceMasses[sourceIndex] * invDistanceCubed;

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
    const auto packed = ComputeAccelerations(
        env,
        sourceBodyIndices,
        sourceMassesKg,
        sourcePosX,
        sourcePosY,
        sourcePosZ,
        targetBodyIndices,
        targetPosX,
        targetPosY,
        targetPosZ,
        gravitationalConstant,
        softeningSquared,
        true
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
    const auto packed = ComputeAccelerations(
        env,
        sourceBodyIndices,
        sourceMassesKg,
        sourcePosX,
        sourcePosY,
        sourcePosZ,
        targetBodyIndices,
        targetPosX,
        targetPosY,
        targetPosZ,
        gravitationalConstant,
        softeningSquared,
        false
    );
    return ToJDoubleArray(env, packed);
}
