#include "SolarLabVulkanRenderer.h"

#include <android/asset_manager_jni.h>
#include <jni.h>
#include <sys/auxv.h>
#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

#include <algorithm>
#include <cstdint>
#include <span>
#include <sstream>
#include <string>
#include <vector>

namespace {
SolarLabVulkanRenderer* FromHandle(jlong handle) {
    return reinterpret_cast<SolarLabVulkanRenderer*>(handle);
}

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

    std::span<const double> asDoubleSpan() const {
        return data_ != nullptr ? std::span<const double>(data_, static_cast<size_t>(length_)) : std::span<const double>();
    }

private:
    JNIEnv* env_;
    jdoubleArray array_;
    jsize length_;
    jdouble* data_ = nullptr;
};

template <>
class ScopedReadOnlyArray<jfloatArray, jfloat> {
public:
    ScopedReadOnlyArray(JNIEnv* env, jfloatArray array)
        : env_(env), array_(array), length_(array != nullptr ? env->GetArrayLength(array) : 0) {
        if (array_ != nullptr && length_ > 0) {
            data_ = env_->GetFloatArrayElements(array_, nullptr);
        }
    }

    ~ScopedReadOnlyArray() {
        if (array_ != nullptr && data_ != nullptr) {
            env_->ReleaseFloatArrayElements(array_, data_, JNI_ABORT);
        }
    }

    std::span<const float> asFloatSpan() const {
        return data_ != nullptr ? std::span<const float>(data_, static_cast<size_t>(length_)) : std::span<const float>();
    }

private:
    JNIEnv* env_;
    jfloatArray array_;
    jsize length_;
    jfloat* data_ = nullptr;
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

    std::span<const int32_t> asIntSpan() const {
        return data_ != nullptr ? std::span<const int32_t>(reinterpret_cast<const int32_t*>(data_), static_cast<size_t>(length_)) : std::span<const int32_t>();
    }

private:
    JNIEnv* env_;
    jintArray array_;
    jsize length_;
    jint* data_ = nullptr;
};

template <>
class ScopedReadOnlyArray<jlongArray, jlong> {
public:
    ScopedReadOnlyArray(JNIEnv* env, jlongArray array)
        : env_(env), array_(array), length_(array != nullptr ? env->GetArrayLength(array) : 0) {
        if (array_ != nullptr && length_ > 0) {
            data_ = env_->GetLongArrayElements(array_, nullptr);
        }
    }

    ~ScopedReadOnlyArray() {
        if (array_ != nullptr && data_ != nullptr) {
            env_->ReleaseLongArrayElements(array_, data_, JNI_ABORT);
        }
    }

    std::span<const int64_t> asLongSpan() const {
        return data_ != nullptr ? std::span<const int64_t>(reinterpret_cast<const int64_t*>(data_), static_cast<size_t>(length_)) : std::span<const int64_t>();
    }

private:
    JNIEnv* env_;
    jlongArray array_;
    jsize length_;
    jlong* data_ = nullptr;
};

std::string GetCpuCapabilitySummary() {
#if defined(__aarch64__)
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 =
#ifdef AT_HWCAP2
        getauxval(AT_HWCAP2);
#else
        0UL;
#endif

    std::vector<std::string> features;
#ifdef HWCAP_ASIMD
    if ((hwcap & HWCAP_ASIMD) != 0UL) features.emplace_back("asimd");
#endif
#ifdef HWCAP_FPHP
    if ((hwcap & HWCAP_FPHP) != 0UL) features.emplace_back("fphp");
#endif
#ifdef HWCAP_ASIMDHP
    if ((hwcap & HWCAP_ASIMDHP) != 0UL) features.emplace_back("asimdhp");
#endif
#ifdef HWCAP_ASIMDDP
    if ((hwcap & HWCAP_ASIMDDP) != 0UL) features.emplace_back("asimddp");
#endif
#ifdef HWCAP_SVE
    if ((hwcap & HWCAP_SVE) != 0UL) features.emplace_back("sve");
#endif
#ifdef HWCAP2_SVE2
    if ((hwcap2 & HWCAP2_SVE2) != 0UL) features.emplace_back("sve2");
#endif

    std::ostringstream out;
    out << "cpu=arm64";
    if (!features.empty()) {
        out << " simd=";
        for (size_t index = 0; index < features.size(); ++index) {
            if (index > 0U) out << ',';
            out << features[index];
        }
    }
    return out.str();
#else
    return "cpu=non-arm64";
#endif
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeIsVulkanRuntimeAvailable(
    JNIEnv*, jclass) {
    return SolarLabVulkanRenderer::IsRuntimeAvailable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeGetCpuCapabilitySummary(
    JNIEnv* env, jclass) {
    const std::string summary = GetCpuCapabilitySummary();
    return env->NewStringUTF(summary.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeCreateRenderer(
    JNIEnv* env, jclass, jobject assetManager) {
    auto* renderer = new SolarLabVulkanRenderer();
    renderer->SetAssetManager(AAssetManager_fromJava(env, assetManager));
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeDestroyRenderer(
    JNIEnv*, jclass, jlong handle) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeOnSurfaceCreated(
    JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = FromHandle(handle);
    return renderer != nullptr && renderer->Initialize(env, surface, width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeOnSurfaceChanged(
    JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    auto* renderer = FromHandle(handle);
    return renderer != nullptr && renderer->Resize(env, surface, width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeOnSurfaceDestroyed(
    JNIEnv*, jclass, jlong handle) {
    auto* renderer = FromHandle(handle);
    if (renderer != nullptr) {
        renderer->DestroySurface();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeSubmitSceneSeed(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong sourceRevision,
    jlongArray tracerMediumHandles,
    jdoubleArray tracerMediumPositionsM,
    jdoubleArray tracerMediumVelocitiesMps,
    jfloatArray tracerMediumRadiiM,
    jintArray tracerMediumColorsArgb,
    jintArray tracerMediumKinds,
    jlongArray tracerFarHandles,
    jdoubleArray tracerFarPositionsM,
    jdoubleArray tracerFarVelocitiesMps,
    jfloatArray tracerFarRadiiM,
    jintArray tracerFarColorsArgb,
    jintArray tracerFarKinds) {
    auto* renderer = FromHandle(handle);
    if (renderer == nullptr) {
        return;
    }

    ScopedReadOnlyArray<jlongArray, jlong> tracerMediumHandlesView(env, tracerMediumHandles);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerMediumPositions(env, tracerMediumPositionsM);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerMediumVelocities(env, tracerMediumVelocitiesMps);
    ScopedReadOnlyArray<jfloatArray, jfloat> tracerMediumRadii(env, tracerMediumRadiiM);
    ScopedReadOnlyArray<jintArray, jint> tracerMediumColors(env, tracerMediumColorsArgb);
    ScopedReadOnlyArray<jintArray, jint> tracerMediumKindsView(env, tracerMediumKinds);
    ScopedReadOnlyArray<jlongArray, jlong> tracerFarHandlesView(env, tracerFarHandles);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerFarPositions(env, tracerFarPositionsM);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerFarVelocities(env, tracerFarVelocitiesMps);
    ScopedReadOnlyArray<jfloatArray, jfloat> tracerFarRadii(env, tracerFarRadiiM);
    ScopedReadOnlyArray<jintArray, jint> tracerFarColors(env, tracerFarColorsArgb);
    ScopedReadOnlyArray<jintArray, jint> tracerFarKindsView(env, tracerFarKinds);

    renderer->SubmitSceneSeed(
        tracerMediumPositions.asDoubleSpan(),
        tracerMediumHandlesView.asLongSpan(),
        tracerMediumVelocities.asDoubleSpan(),
        tracerMediumRadii.asFloatSpan(),
        tracerMediumColors.asIntSpan(),
        tracerMediumKindsView.asIntSpan(),
        tracerFarPositions.asDoubleSpan(),
        tracerFarHandlesView.asLongSpan(),
        tracerFarVelocities.asDoubleSpan(),
        tracerFarRadii.asFloatSpan(),
        tracerFarColors.asIntSpan(),
        tracerFarKindsView.asIntSpan(),
        static_cast<int64_t>(sourceRevision));
}

extern "C" JNIEXPORT void JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeSetFrameState(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong sourceRevision,
    jdouble epochSeconds,
    jdouble simulationAdvanceSeconds,
    jboolean includeTracerMutualGravity,
    jdoubleArray authoritativePositionsM,
    jdoubleArray authoritativeSourceMassesKg,
    jfloatArray authoritativeRadiiM,
    jintArray authoritativeColorsArgb,
    jintArray authoritativeKinds,
    jdoubleArray tracerNearPositionsM,
    jdoubleArray tracerNearSourceMassesKg,
    jfloatArray tracerNearRadiiM,
    jintArray tracerNearColorsArgb,
    jintArray tracerNearKinds,
    jlongArray tracerMediumHandles,
    jdoubleArray tracerMediumPositionsM,
    jdoubleArray tracerMediumSourceMassesKg,
    jlongArray tracerFarHandles,
    jdoubleArray tracerFarPositionsM,
    jdoubleArray tracerFarSourceMassesKg,
    jdoubleArray trailPositionsM,
    jintArray trailColorsArgb,
    jintArray trailVertexCounts) {
    auto* renderer = FromHandle(handle);
    if (renderer == nullptr) {
        return;
    }

    ScopedReadOnlyArray<jdoubleArray, jdouble> authoritativePositions(env, authoritativePositionsM);
    ScopedReadOnlyArray<jdoubleArray, jdouble> authoritativeSourceMasses(env, authoritativeSourceMassesKg);
    ScopedReadOnlyArray<jfloatArray, jfloat> authoritativeRadii(env, authoritativeRadiiM);
    ScopedReadOnlyArray<jintArray, jint> authoritativeColors(env, authoritativeColorsArgb);
    ScopedReadOnlyArray<jintArray, jint> authoritativeKindsView(env, authoritativeKinds);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerNearPositions(env, tracerNearPositionsM);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerNearSourceMasses(env, tracerNearSourceMassesKg);
    ScopedReadOnlyArray<jfloatArray, jfloat> tracerNearRadii(env, tracerNearRadiiM);
    ScopedReadOnlyArray<jintArray, jint> tracerNearColors(env, tracerNearColorsArgb);
    ScopedReadOnlyArray<jintArray, jint> tracerNearKindsView(env, tracerNearKinds);
    ScopedReadOnlyArray<jlongArray, jlong> tracerMediumHandlesView(env, tracerMediumHandles);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerMediumPositions(env, tracerMediumPositionsM);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerMediumSourceMasses(env, tracerMediumSourceMassesKg);
    ScopedReadOnlyArray<jlongArray, jlong> tracerFarHandlesView(env, tracerFarHandles);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerFarPositions(env, tracerFarPositionsM);
    ScopedReadOnlyArray<jdoubleArray, jdouble> tracerFarSourceMasses(env, tracerFarSourceMassesKg);
    ScopedReadOnlyArray<jdoubleArray, jdouble> trailPositions(env, trailPositionsM);
    ScopedReadOnlyArray<jintArray, jint> trailColors(env, trailColorsArgb);
    ScopedReadOnlyArray<jintArray, jint> trailVertexCountsView(env, trailVertexCounts);

    renderer->SetFrameState(
        static_cast<int64_t>(sourceRevision),
        static_cast<double>(epochSeconds),
        static_cast<double>(simulationAdvanceSeconds),
        includeTracerMutualGravity == JNI_TRUE,
        authoritativePositions.asDoubleSpan(),
        authoritativeSourceMasses.asDoubleSpan(),
        authoritativeRadii.asFloatSpan(),
        authoritativeColors.asIntSpan(),
        authoritativeKindsView.asIntSpan(),
        tracerNearPositions.asDoubleSpan(),
        tracerNearSourceMasses.asDoubleSpan(),
        tracerNearRadii.asFloatSpan(),
        tracerNearColors.asIntSpan(),
        tracerNearKindsView.asIntSpan(),
        tracerMediumHandlesView.asLongSpan(),
        tracerMediumPositions.asDoubleSpan(),
        tracerMediumSourceMasses.asDoubleSpan(),
        tracerFarHandlesView.asLongSpan(),
        tracerFarPositions.asDoubleSpan(),
        tracerFarSourceMasses.asDoubleSpan(),
        trailPositions.asDoubleSpan(),
        trailColors.asIntSpan(),
        trailVertexCountsView.asIntSpan());
}

extern "C" JNIEXPORT void JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeSetCamera(
    JNIEnv*, jclass, jlong handle, jdouble centerX, jdouble centerY, jdouble centerZ, jdouble viewRadiusM) {
    auto* renderer = FromHandle(handle);
    if (renderer != nullptr) {
        renderer->SetCamera(centerX, centerY, centerZ, viewRadiusM);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeRender(
    JNIEnv*, jclass, jlong handle) {
    auto* renderer = FromHandle(handle);
    return renderer != nullptr && renderer->Render() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeGetLastError(
    JNIEnv* env, jclass, jlong handle) {
    auto* renderer = FromHandle(handle);
    const std::string value = renderer != nullptr ? renderer->LastError() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeGetBackendLabel(
    JNIEnv* env, jclass, jlong handle) {
    auto* renderer = FromHandle(handle);
    const std::string value = renderer != nullptr ? renderer->BackendLabel() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeGetSceneSummary(
    JNIEnv* env, jclass, jlong handle) {
    auto* renderer = FromHandle(handle);
    const std::string value = renderer != nullptr ? renderer->SceneSummary() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeGetHardwareSummary(
    JNIEnv* env, jclass, jlong handle) {
    auto* renderer = FromHandle(handle);
    const std::string value = renderer != nullptr ? renderer->HardwareSummary() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}
