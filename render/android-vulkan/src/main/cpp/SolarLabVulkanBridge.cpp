#include "SolarLabStageController.h"

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {
SolarLabStageController* FromHandle(jlong handle) {
    return reinterpret_cast<SolarLabStageController*>(handle);
}

ANativeWindow* WindowFromHandle(jlong handle) {
    return reinterpret_cast<ANativeWindow*>(handle);
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

std::string CopyUtf8String(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::string NativeCpuCapabilitySummary() {
#if defined(__aarch64__)
    constexpr const char* architecture = "arm64";
#elif defined(__arm__)
    constexpr const char* architecture = "arm";
#elif defined(__x86_64__)
    constexpr const char* architecture = "x86_64";
#elif defined(__i386__)
    constexpr const char* architecture = "x86";
#else
    constexpr const char* architecture = "unknown";
#endif

    std::ostringstream out;
    out << "cpu=" << architecture << " bits=" << (sizeof(void*) * 8);
    return out.str();
}
}  // namespace

namespace solar_lab_jni {
jdoubleArray CameraSnapshotArray(
    JNIEnv* env,
    const SolarLabStageController::CameraSnapshot& snapshot) {
    const jdouble values[] = {
        snapshot.centerX,
        snapshot.centerY,
        snapshot.centerZ,
        snapshot.viewRadiusM,
        snapshot.yawRadians,
        snapshot.pitchRadians,
    };
    jdoubleArray result = env->NewDoubleArray(6);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, 6, values);
    }
    return result;
}
}  // namespace solar_lab_jni

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeIsVulkanRuntimeAvailable(
    JNIEnv*, jclass) {
    return SolarLabStageController::IsVulkanRuntimeAvailable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetCpuCapabilitySummary(
    JNIEnv* env, jclass) {
    const std::string value = NativeCpuCapabilitySummary();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeCreateRenderer(
    JNIEnv* env, jclass, jobject assetManager) {
    auto* controller = new SolarLabStageController();
    controller->SetAssetManager(AAssetManager_fromJava(env, assetManager));
    return reinterpret_cast<jlong>(controller);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeAcquireNativeWindow(
    JNIEnv* env, jclass, jobject surface) {
    return reinterpret_cast<jlong>(ANativeWindow_fromSurface(env, surface));
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeReleaseNativeWindow(
    JNIEnv*, jclass, jlong nativeWindowHandle) {
    auto* nativeWindow = WindowFromHandle(nativeWindowHandle);
    if (nativeWindow != nullptr) {
        ANativeWindow_release(nativeWindow);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeDestroyRenderer(
    JNIEnv*, jclass, jlong handle) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeOnSurfaceCreated(
    JNIEnv*, jclass, jlong handle, jlong nativeWindowHandle, jint width, jint height) {
    auto* controller = FromHandle(handle);
    return controller != nullptr &&
            controller->Initialize(WindowFromHandle(nativeWindowHandle), width, height)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeOnSurfaceChanged(
    JNIEnv*, jclass, jlong handle, jlong nativeWindowHandle, jint width, jint height) {
    auto* controller = FromHandle(handle);
    return controller != nullptr &&
            controller->Resize(WindowFromHandle(nativeWindowHandle), width, height)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeOnSurfaceDestroyed(
    JNIEnv*, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->DestroySurface();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSubmitScene(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong sourceRevision,
    jdouble sceneOriginX,
    jdouble sceneOriginY,
    jdouble sceneOriginZ,
    jdoubleArray authoritativePositionsM,
    jdoubleArray authoritativeSourceMassesKg,
    jfloatArray authoritativeRadiiM,
    jintArray authoritativeColorsArgb,
    jintArray authoritativeKinds,
    jdoubleArray tracerNearPositionsM,
    jfloatArray tracerNearRadiiM,
    jintArray tracerNearColorsArgb,
    jintArray tracerNearKinds,
    jdoubleArray tracerMediumPositionsM,
    jdoubleArray tracerMediumVelocitiesMps,
    jintArray tracerMediumStableIds,
    jfloatArray tracerMediumRadiiM,
    jintArray tracerMediumColorsArgb,
    jintArray tracerMediumKinds,
    jdoubleArray tracerFarPositionsM,
    jdoubleArray tracerFarVelocitiesMps,
    jintArray tracerFarStableIds,
    jfloatArray tracerFarRadiiM,
    jintArray tracerFarColorsArgb,
    jintArray tracerFarKinds,
    jdoubleArray trailPositionsM,
    jintArray trailColorsArgb,
    jintArray trailVertexCounts) {
    auto* controller = FromHandle(handle);
    if (controller == nullptr) {
        return;
    }

    controller->SubmitScene(
        static_cast<int64_t>(sourceRevision),
        static_cast<double>(sceneOriginX),
        static_cast<double>(sceneOriginY),
        static_cast<double>(sceneOriginZ),
        CopyDoubles(env, authoritativePositionsM),
        CopyDoubles(env, authoritativeSourceMassesKg),
        CopyFloats(env, authoritativeRadiiM),
        CopyInts(env, authoritativeColorsArgb),
        CopyInts(env, authoritativeKinds),
        CopyDoubles(env, tracerNearPositionsM),
        CopyFloats(env, tracerNearRadiiM),
        CopyInts(env, tracerNearColorsArgb),
        CopyInts(env, tracerNearKinds),
        CopyDoubles(env, tracerMediumPositionsM),
        CopyDoubles(env, tracerMediumVelocitiesMps),
        CopyInts(env, tracerMediumStableIds),
        CopyFloats(env, tracerMediumRadiiM),
        CopyInts(env, tracerMediumColorsArgb),
        CopyInts(env, tracerMediumKinds),
        CopyDoubles(env, tracerFarPositionsM),
        CopyDoubles(env, tracerFarVelocitiesMps),
        CopyInts(env, tracerFarStableIds),
        CopyFloats(env, tracerFarRadiiM),
        CopyInts(env, tracerFarColorsArgb),
        CopyInts(env, tracerFarKinds),
        CopyDoubles(env, trailPositionsM),
        CopyInts(env, trailColorsArgb),
        CopyInts(env, trailVertexCounts));
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetCamera(
    JNIEnv*,
    jclass,
    jlong handle,
    jdouble centerX,
    jdouble centerY,
    jdouble centerZ,
    jdouble viewRadiusM,
    jdouble yawRadians,
    jdouble pitchRadians) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->SetCamera(centerX, centerY, centerZ, viewRadiusM, yawRadians, pitchRadians);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetCameraInReference(
    JNIEnv* env, jclass, jlong handle, jdouble centerX, jdouble centerY, jdouble centerZ,
    jdouble viewRadiusM, jdouble yawRadians, jdouble pitchRadians, jstring referenceBodyId) {
    if (auto* controller = FromHandle(handle)) {
        controller->SetCamera(centerX, centerY, centerZ, viewRadiusM, yawRadians, pitchRadians,
            CopyUtf8String(env, referenceBodyId));
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetCameraState(
    JNIEnv* env,
    jclass,
    jlong handle) {
    auto* controller = FromHandle(handle);
    return controller == nullptr
        ? nullptr
        : solar_lab_jni::CameraSnapshotArray(env, controller->GetCameraSnapshot());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetFlightCamera(
    JNIEnv*, jclass, jlong handle, jint mode) {
    if (auto* controller = FromHandle(handle)) controller->SetFlightCamera(mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetFlightTargetBodyId(
    JNIEnv* env, jclass, jlong handle, jstring bodyId) {
    if (auto* controller = FromHandle(handle)) controller->SetFlightTargetBodyId(CopyUtf8String(env, bodyId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetFlightSafeArea(
    JNIEnv*, jobject, jlong handle, jdouble halfWidth, jdouble halfHeight) {
    if (auto* controller = FromHandle(handle)) controller->SetFlightSafeArea(halfWidth, halfHeight);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetLabMapView(
    JNIEnv*, jclass, jlong handle, jboolean enabled, jdouble normalX, jdouble normalY, jdouble normalZ) {
    if (auto* controller = FromHandle(handle)) {
        controller->SetLabMapView(enabled == JNI_TRUE, normalX, normalY, normalZ);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetTouchDensity(
    JNIEnv*, jclass, jlong handle, jdouble density) {
    if (auto* controller = FromHandle(handle)) controller->SetTouchDensity(density);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetLabViewportState(
    JNIEnv* env, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    if (controller == nullptr) return nullptr;
    // One controller lock captures the submitted camera and body interpolation together.
    const auto snapshot = controller->GetLabViewportSnapshot();
    const auto& camera = snapshot.camera;
    const jdouble header[] = {
        static_cast<double>(snapshot.width), static_cast<double>(snapshot.height),
        camera.centerX, camera.centerY, camera.centerZ,
        camera.viewRadiusM, camera.yawRadians, camera.pitchRadians, snapshot.metersPerPixel,
    };
    jclass objectClass = env->FindClass("java/lang/Object");
    jclass stringClass = env->FindClass("java/lang/String");
    if (objectClass == nullptr || stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    jobjectArray ids = env->NewObjectArray(static_cast<jsize>(snapshot.bodies.size()), stringClass, nullptr);
    jdoubleArray headerArray = env->NewDoubleArray(9);
    jdoubleArray bodyArray = env->NewDoubleArray(static_cast<jsize>(snapshot.bodies.size() * 8));
    if (result == nullptr || ids == nullptr || headerArray == nullptr || bodyArray == nullptr) return nullptr;
    env->SetDoubleArrayRegion(headerArray, 0, 9, header);
    std::vector<jdouble> values;
    values.reserve(snapshot.bodies.size() * 8);
    for (size_t index = 0; index < snapshot.bodies.size(); ++index) {
        const auto& body = snapshot.bodies[index];
        jstring id = env->NewStringUTF(body.bodyId.c_str());
        if (id == nullptr) return nullptr;
        env->SetObjectArrayElement(ids, static_cast<jsize>(index), id);
        env->DeleteLocalRef(id);
        values.insert(values.end(), {body.screenX, body.screenY, body.radiusPx, body.depthM,
            body.occluded ? 1.0 : 0.0, body.worldX, body.worldY, body.worldZ});
    }
    if (!values.empty()) env->SetDoubleArrayRegion(bodyArray, 0, static_cast<jsize>(values.size()), values.data());
    env->SetObjectArrayElement(result, 0, headerArray);
    env->SetObjectArrayElement(result, 1, ids);
    env->SetObjectArrayElement(result, 2, bodyArray);
    env->DeleteLocalRef(headerArray);
    env->DeleteLocalRef(ids);
    env->DeleteLocalRef(bodyArray);
    env->DeleteLocalRef(objectClass);
    env->DeleteLocalRef(stringClass);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeResolveRuntimeHomeCamera(
    JNIEnv* env,
    jclass,
    jlong handle) {
    auto* controller = FromHandle(handle);
    if (controller == nullptr) {
        return nullptr;
    }
    const auto snapshot = controller->ResolveRuntimeHomeCamera();
    return snapshot.has_value()
        ? solar_lab_jni::CameraSnapshotArray(env, snapshot.value())
        : nullptr;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeResolveRuntimeBodyFrame(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring bodyId) {
    auto* controller = FromHandle(handle);
    if (controller == nullptr) {
        return nullptr;
    }
    const auto snapshot = controller->ResolveRuntimeBodyFrame(CopyUtf8String(env, bodyId));
    return snapshot.has_value()
        ? solar_lab_jni::CameraSnapshotArray(env, snapshot.value())
        : nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeResolveRuntimeCameraTransition(
    JNIEnv* env, jclass, jlong handle, jstring bodyId) {
    auto* controller = FromHandle(handle);
    if (controller == nullptr) return nullptr;
    const auto frame = controller->ResolveRuntimeCameraTransition(CopyUtf8String(env, bodyId));
    if (!frame) return nullptr;
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    jdoubleArray start = solar_lab_jni::CameraSnapshotArray(env, frame->start);
    jdoubleArray target = solar_lab_jni::CameraSnapshotArray(env, frame->target);
    jstring reference = env->NewStringUTF(frame->referenceBodyId.c_str());
    if (result == nullptr || start == nullptr || target == nullptr || reference == nullptr) return nullptr;
    env->SetObjectArrayElement(result, 0, start);
    env->SetObjectArrayElement(result, 1, target);
    env->SetObjectArrayElement(result, 2, reference);
    env->DeleteLocalRef(start);
    env->DeleteLocalRef(target);
    env->DeleteLocalRef(reference);
    env->DeleteLocalRef(objectClass);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeBindRuntimeSession(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong runtimeSessionHandle) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->BindRuntimeSession(static_cast<uint64_t>(runtimeSessionHandle));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeUnbindRuntimeSession(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->UnbindRuntimeSession();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetRuntimeProcessingMode(
    JNIEnv*,
    jclass,
    jlong handle,
    jint processingModeCode) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->SetRuntimeProcessingMode(static_cast<int>(processingModeCode));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetRuntimeObserverMode(
    JNIEnv*,
    jclass,
    jlong handle,
    jint observerModeCode) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->SetRuntimeObserverMode(static_cast<int>(observerModeCode));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetRuntimeSelectedBodyId(
    JNIEnv* env,
    jclass,
    jlong handle,
    jstring bodyId) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->SetRuntimeSelectedBodyId(CopyUtf8String(env, bodyId));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeSetRuntimeTraceLayerMode(
    JNIEnv*,
    jclass,
    jlong handle,
    jint traceLayerModeCode) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->SetRuntimeTraceLayerMode(static_cast<int>(traceLayerModeCode));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeResetRuntimeCamera(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->ResetRuntimeCamera();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativePanRuntimeCamera(
    JNIEnv*,
    jclass,
    jlong handle,
    jfloat distanceXPx,
    jfloat distanceYPx,
    jint viewportWidthPx,
    jint viewportHeightPx) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->PanRuntimeCamera(distanceXPx, distanceYPx, viewportWidthPx, viewportHeightPx);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeZoomRuntimeCamera(
    JNIEnv*,
    jclass,
    jlong handle,
    jfloat scaleFactor,
    jfloat focusXPx,
    jfloat focusYPx,
    jint viewportWidthPx,
    jint viewportHeightPx) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->ZoomRuntimeCamera(scaleFactor, focusXPx, focusYPx, viewportWidthPx, viewportHeightPx);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativePanAndZoomRuntimeCamera(
    JNIEnv*,
    jclass,
    jlong handle,
    jfloat distanceXPx,
    jfloat distanceYPx,
    jfloat scaleFactor,
    jfloat focusXPx,
    jfloat focusYPx,
    jint viewportWidthPx,
    jint viewportHeightPx) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->PanAndZoomRuntimeCamera(
            distanceXPx,
            distanceYPx,
            scaleFactor,
            focusXPx,
            focusYPx,
            viewportWidthPx,
            viewportHeightPx);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeOrbitRuntimeCamera(
    JNIEnv*,
    jclass,
    jlong handle,
    jfloat deltaXPx,
    jfloat deltaYPx,
    jfloat focusXPx,
    jfloat focusYPx,
    jint viewportWidthPx,
    jint viewportHeightPx) {
    auto* controller = FromHandle(handle);
    if (controller != nullptr) {
        controller->OrbitRuntimeCamera(deltaXPx, deltaYPx, focusXPx, focusYPx, viewportWidthPx, viewportHeightPx);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativePickRuntimeBodyId(
    JNIEnv* env,
    jclass,
    jlong handle,
    jfloat screenXPx,
    jfloat screenYPx,
    jint viewportWidthPx,
    jint viewportHeightPx) {
    auto* controller = FromHandle(handle);
    if (controller == nullptr) {
        return nullptr;
    }
    const std::string bodyId = controller->PickRuntimeBodyId(screenXPx, screenYPx, viewportWidthPx, viewportHeightPx);
    if (bodyId.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(bodyId.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeRender(
    JNIEnv*, jclass, jlong handle, jboolean refreshRuntimeScene) {
    auto* controller = FromHandle(handle);
    return controller != nullptr && controller->Render(refreshRuntimeScene == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeHasPendingPresentation(
    JNIEnv*, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    return controller != nullptr && controller->HasPendingPresentation() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetLastError(
    JNIEnv* env, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    const std::string value = controller != nullptr ? controller->LastError() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetBackendLabel(
    JNIEnv* env, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    const std::string value = controller != nullptr ? controller->BackendLabel() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetSceneSummary(
    JNIEnv* env, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    const std::string value = controller != nullptr ? controller->SceneSummary() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeGetHardwareSummary(
    JNIEnv* env, jclass, jlong handle) {
    auto* controller = FromHandle(handle);
    const std::string value =
        controller != nullptr ? controller->HardwareSummary() : std::string("Renderer handle is null.");
    return env->NewStringUTF(value.c_str());
}
