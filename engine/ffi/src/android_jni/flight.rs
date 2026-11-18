//! JNI ownership-only adapter for flight commands and numerical telemetry.
use super::super::flight as transport;
use super::*;
use jni::objects::JDoubleArray;
use jni::sys::jdoubleArray;

fn request(
    env: &JNIEnv,
    handle: jlong,
    id: &JByteArray,
) -> Result<(SlRuntimeHandle, String), String> {
    if handle <= 0 {
        return Err("The flight session is no longer open".into());
    }
    let length = env
        .get_array_length(id)
        .map_err(|_| "Invalid spacecraft identifier")?;
    if length <= 0 || length as usize > SL_V2_ID_CAPACITY {
        return Err("Invalid spacecraft identifier length".into());
    }
    let bytes = env
        .convert_byte_array(id)
        .map_err(|_| "Invalid spacecraft identifier")?;
    let id = String::from_utf8(bytes).map_err(|_| "Invalid spacecraft identifier encoding")?;
    Ok((SlRuntimeHandle { raw: handle as u64 }, id))
}
fn fail(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeStartFlight(
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
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeSetFlightControl(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
    controls: JDoubleArray,
    recorded: jlong,
) {
    let result = (|| {
        let (handle, id) = request(&env, handle, &ship_id)?;
        if env
            .get_array_length(&controls)
            .map_err(|_| "Invalid controls")?
            != 8
        {
            return Err("Invalid controls length".into());
        }
        let mut values = [0.0; 8];
        env.get_double_array_region(&controls, 0, &mut values)
            .map_err(|_| "Invalid controls")?;
        transport::set_control(handle, &id, &values, recorded)
    })();
    if let Err(message) = result {
        fail(&mut env, &message);
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeFlightState(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    ship_id: JByteArray,
) -> jdoubleArray {
    let result = request(&env, handle, &ship_id).and_then(|(h, id)| transport::sample(h, &id));
    match result {
        Ok(Some(values)) => {
            let array = env.new_double_array(transport::FLIGHT_SAMPLE_LEN as i32);
            match array {
                Ok(array) => {
                    if env.set_double_array_region(&array, 0, &values).is_err() {
                        fail(&mut env, "Could not return flight telemetry");
                        std::ptr::null_mut()
                    } else {
                        array.into_raw()
                    }
                }
                Err(_) => {
                    fail(&mut env, "Could not allocate flight telemetry");
                    std::ptr::null_mut()
                }
            }
        }
        Ok(None) => std::ptr::null_mut(),
        Err(message) => {
            fail(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}
