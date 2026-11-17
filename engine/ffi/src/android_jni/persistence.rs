use super::*;
use jni::sys::jdoubleArray;

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeExportSavedSession(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jbyteArray {
    match super::super::persistence::export_save(SlRuntimeHandle { raw: handle as u64 }) {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map_or(std::ptr::null_mut(), |a| a.into_raw()),
        Err(result) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!(
                    "Could not save committed experiment (status {})",
                    status_code_as_i32(result.code)
                ),
            );
            std::ptr::null_mut()
        }
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeImportSavedSession(
    mut env: JNIEnv,
    _this: JObject,
    template_handle: jlong,
    bytes: JByteArray,
) -> jobject {
    let input = (|| {
        let length = env.get_array_length(&bytes).ok()?;
        if length <= 0 || length as usize > solarlab_runtime::MAX_SAVED_SESSION_BYTES {
            return None;
        }
        env.convert_byte_array(&bytes).ok()
    })();
    let Some(input) = input else {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "Invalid experiment file size",
        );
        return JObject::null().into_raw();
    };
    let result = super::super::persistence::import_save(
        SlRuntimeHandle {
            raw: template_handle as u64,
        },
        &input,
    );
    match create_native_create_session_result(&mut env, &result) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            if result.handle.raw != 0 {
                sl_v2_session_destroy(result.handle);
            }
            JObject::null().into_raw()
        }
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeInspectBody(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    body_id: JByteArray,
) -> jdoubleArray {
    let values = (|| {
        let length = env.get_array_length(&body_id).ok()?;
        if length <= 0 || length as usize > SL_V2_ID_CAPACITY {
            return None;
        }
        let bytes = env.convert_byte_array(&body_id).ok()?;
        let id = std::str::from_utf8(&bytes).ok()?;
        let registry = super::super::registry().lock().ok()?;
        let session = registry.get(SlRuntimeHandle { raw: handle as u64 })?;
        let body = session.runtime.body_by_id(id)?;
        use solarlab_domain::BodyClass;
        let class = match body.body_class {
            BodyClass::Star => 0.0,
            BodyClass::Planet => 1.0,
            BodyClass::DwarfPlanet => 2.0,
            BodyClass::Moon => 3.0,
            BodyClass::SmallBody => 4.0,
            BodyClass::Tracer => 5.0,
            BodyClass::Spacecraft => 6.0,
            BodyClass::Custom => 7.0,
            BodyClass::Comet => 8.0,
        };
        Some([
            class,
            body.mass_kg,
            body.source_mass_kg,
            body.radius_m,
            body.position_m.x,
            body.position_m.y,
            body.position_m.z,
            body.velocity_mps.x,
            body.velocity_mps.y,
            body.velocity_mps.z,
        ])
    })();
    let Some(values) = values else {
        return std::ptr::null_mut();
    };
    match env.new_double_array(values.len() as i32) {
        Ok(array) => {
            if env.set_double_array_region(&array, 0, &values).is_ok() {
                array.into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}
