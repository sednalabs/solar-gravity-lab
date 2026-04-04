#include "SolarLabVulkanRenderer.h"

#include <android/asset_manager_jni.h>
#include <jni.h>
#include <sys/auxv.h>
#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {
SolarLabVulkanRenderer* FromHandle(jlong handle) {
    return reinterpret_cast<SolarLabVulkanRenderer*>(handle);
}

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

std::vector<double> CopyDoubles(JNIEnv* env, jdoubleArray array) {
    if (array == nullptr) {
        return {};
    }
    const jsize length = env->GetArrayLength(array);
    std::vector<double> out(static_cast<size_t>(length));
    jdouble* raw = env->GetDoubleArrayElements(array, nullptr);
    if (raw != nullptr) {
        std::copy(raw, raw + length, out.begin());
        env->ReleaseDoubleArrayElements(array, raw, JNI_ABORT);
    }
    return out;
}

std::vector<float> CopyFloats(JNIEnv* env, jfloatArray array) {
    if (array == nullptr) {
        return {};
    }
    const jsize length = env->GetArrayLength(array);
    std::vector<float> out(static_cast<size_t>(length));
    jfloat* raw = env->GetFloatArrayElements(array, nullptr);
    if (raw != nullptr) {
        std::copy(raw, raw + length, out.begin());
        env->ReleaseFloatArrayElements(array, raw, JNI_ABORT);
    }
    return out;
}

std::vector<int32_t> CopyInts(JNIEnv* env, jintArray array) {
    if (array == nullptr) {
        return {};
    }
    const jsize length = env->GetArrayLength(array);
    std::vector<int32_t> out(static_cast<size_t>(length));
    jint* raw = env->GetIntArrayElements(array, nullptr);
    if (raw != nullptr) {
        std::copy(raw, raw + length, out.begin());
        env->ReleaseIntArrayElements(array, raw, JNI_ABORT);
    }
    return out;
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
Java_com_graciousgazelles_solarlab_feature_lab_render_SolarLabVulkanBridge_nativeSubmitScene(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong sourceRevision,
    jdoubleArray authoritativePositionsM,
    jfloatArray authoritativeRadiiM,
    jintArray authoritativeColorsArgb,
    jintArray authoritativeKinds,
    jdoubleArray tracerNearPositionsM,
    jfloatArray tracerNearRadiiM,
    jintArray tracerNearColorsArgb,
    jintArray tracerNearKinds,
    jdoubleArray tracerMediumPositionsM,
    jfloatArray tracerMediumRadiiM,
    jintArray tracerMediumColorsArgb,
    jintArray tracerMediumKinds,
    jdoubleArray tracerFarPositionsM,
    jfloatArray tracerFarRadiiM,
    jintArray tracerFarColorsArgb,
    jintArray tracerFarKinds,
    jdoubleArray trailPositionsM,
    jintArray trailColorsArgb,
    jintArray trailVertexCounts) {
    auto* renderer = FromHandle(handle);
    if (renderer == nullptr) {
        return;
    }

    renderer->SubmitScene(
        static_cast<int64_t>(sourceRevision),
        CopyDoubles(env, authoritativePositionsM),
        CopyFloats(env, authoritativeRadiiM),
        CopyInts(env, authoritativeColorsArgb),
        CopyInts(env, authoritativeKinds),
        CopyDoubles(env, tracerNearPositionsM),
        CopyFloats(env, tracerNearRadiiM),
        CopyInts(env, tracerNearColorsArgb),
        CopyInts(env, tracerNearKinds),
        CopyDoubles(env, tracerMediumPositionsM),
        CopyFloats(env, tracerMediumRadiiM),
        CopyInts(env, tracerMediumColorsArgb),
        CopyInts(env, tracerMediumKinds),
        CopyDoubles(env, tracerFarPositionsM),
        CopyFloats(env, tracerFarRadiiM),
        CopyInts(env, tracerFarColorsArgb),
        CopyInts(env, tracerFarKinds),
        CopyDoubles(env, trailPositionsM),
        CopyInts(env, trailColorsArgb),
        CopyInts(env, trailVertexCounts));
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
