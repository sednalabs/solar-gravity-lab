//! JNI transport for native docking.
use super::super::docking as transport;
use super::flight::{export_sample, fail, request};
use super::*;
use jni::sys::{jdoubleArray, jstring};

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeStartDockingFlight(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
    recorded: jlong,
) {
    let result =
        request(&env, handle, &ship_id).and_then(|(h, id)| transport::start(h, &id, recorded));
    if let Err(message) = result {
        fail(&mut env, &message);
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeSetDockingGuidance(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
    mode: jint,
    recorded: jlong,
) {
    let result = request(&env, handle, &ship_id)
        .and_then(|(h, id)| transport::set_guidance(h, &id, mode, recorded));
    if let Err(message) = result {
        fail(&mut env, &message);
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeDockingState(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
) -> jdoubleArray {
    let result = request(&env, handle, &ship_id).and_then(|(h, id)| transport::sample(h, &id));
    export_sample(&mut env, result)
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeDockingTargetId(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
) -> jstring {
    let result = request(&env, handle, &ship_id).and_then(|(h, id)| transport::target_id(h, &id));
    match result {
        Ok(Some(id)) => match env.new_string(id) {
            Ok(value) => value.into_raw(),
            Err(_) => {
                fail(&mut env, "Could not return docking target identity");
                std::ptr::null_mut()
            }
        },
        Ok(None) => std::ptr::null_mut(),
        Err(message) => {
            fail(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeUndockSpacecraft(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
    recorded: jlong,
) {
    let result =
        request(&env, handle, &ship_id).and_then(|(h, id)| transport::undock(h, &id, recorded));
    if let Err(message) = result {
        fail(&mut env, &message);
    }
}
