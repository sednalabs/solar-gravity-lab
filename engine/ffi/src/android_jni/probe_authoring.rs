use super::super::probe_authoring as transport;
use super::*;
use jni::objects::JDoubleArray;

fn read_id(env: &JNIEnv, bytes: &JByteArray) -> Result<String, String> {
    let length = env
        .get_array_length(bytes)
        .map_err(|_| "Invalid body identifier")?;
    if length <= 0 || length as usize > SL_V2_ID_CAPACITY {
        return Err("Invalid body identifier length".into());
    }
    let bytes = env
        .convert_byte_array(bytes)
        .map_err(|_| "Invalid body identifier")?;
    String::from_utf8(bytes).map_err(|_| "Invalid body identifier encoding".into())
}

fn read_body(
    env: &JNIEnv,
    id: &JByteArray,
    values: &JDoubleArray,
) -> Result<solarlab_runtime::BodyState, String> {
    let id = read_id(env, id)?;
    if env
        .get_array_length(values)
        .map_err(|_| "Invalid probe payload")?
        != 10
    {
        return Err("Invalid probe payload length".into());
    }
    let mut data = [0.0; 10];
    env.get_double_array_region(values, 0, &mut data)
        .map_err(|_| "Invalid probe payload")?;
    transport::body_from_values(id, &data)
}

fn checked_handle(handle: jlong) -> Result<SlRuntimeHandle, String> {
    if handle <= 0 {
        Err("The experiment is no longer open".into())
    } else {
        Ok(SlRuntimeHandle { raw: handle as u64 })
    }
}

fn fail(env: &mut JNIEnv, message: impl AsRef<str>) -> jobject {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
    JObject::null().into_raw()
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativePreviewProbe(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    body_id: JByteArray,
    body_values: JDoubleArray,
    reference_id: JByteArray,
    horizon: jdouble,
) -> jobject {
    let result = (|| {
        let candidate = read_body(&env, &body_id, &body_values)?;
        let reference = solarlab_domain::BodyId(read_id(&env, &reference_id)?);
        transport::preview(checked_handle(handle)?, candidate, reference, horizon)
    })();
    let result = match result {
        Ok(value) => value,
        Err(message) => return fail(&mut env, message),
    };
    let object = (|| -> jni::errors::Result<JObject> {
        let reference = env.new_double_array(10)?;
        env.set_double_array_region(
            &reference,
            0,
            &transport::body_values(&result.reference_body),
        )?;
        let values: Vec<f64> = result
            .samples
            .iter()
            .flat_map(|sample| {
                [
                    sample.elapsed_seconds,
                    sample.position_m.x,
                    sample.position_m.y,
                    sample.position_m.z,
                ]
            })
            .collect();
        let samples = env.new_double_array(values.len() as i32)?;
        env.set_double_array_region(&samples, 0, &values)?;
        let reference_object = JObject::from(reference);
        let samples_object = JObject::from(samples);
        env.new_object(
            CLASS_NATIVE_PROBE_PREVIEW_RESULT,
            "(JDDZZ[D[D)V",
            &[
                JValue::Long(result.baseline_revision as i64),
                JValue::Double(result.epoch_seconds),
                JValue::Double(result.horizon_seconds),
                JValue::Bool(u8::from(result.limited)),
                JValue::Bool(u8::from(result.intersects_reference_body)),
                JValue::Object(&reference_object),
                JValue::Object(&samples_object),
            ],
        )
    })();
    match object {
        Ok(value) => value.into_raw(),
        Err(_) => fail(&mut env, "Could not return the probe preview"),
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeCommitProbe(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    body_id: JByteArray,
    body_values: JDoubleArray,
    revision: jlong,
    epoch: jdouble,
    recorded: jlong,
) -> jobject {
    let result = (|| {
        if revision < 0 {
            return Err("Invalid preview revision".into());
        }
        let candidate = read_body(&env, &body_id, &body_values)?;
        transport::commit(
            checked_handle(handle)?,
            candidate,
            revision as u64,
            epoch,
            recorded,
        )
    })();
    let receipt = match result {
        Ok(value) => value,
        Err(message) => return fail(&mut env, message),
    };
    let object = (|| -> jni::errors::Result<JObject> {
        let checkpoint = JObject::from(env.new_string(receipt.checkpoint_id.0)?);
        env.new_object(
            CLASS_NATIVE_PROBE_COMMIT_RESULT,
            "(Ljava/lang/String;JD)V",
            &[
                JValue::Object(&checkpoint),
                JValue::Long(receipt.committed_revision as i64),
                JValue::Double(receipt.epoch_seconds),
            ],
        )
    })();
    match object {
        Ok(value) => value.into_raw(),
        Err(_) => fail(&mut env, "Could not return the probe placement receipt"),
    }
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeUndoProbe(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    checkpoint_id: JByteArray,
    committed_revision: jlong,
    recorded: jlong,
) {
    let result = (|| {
        if committed_revision < 0 {
            return Err("Invalid placement revision".into());
        }
        let checkpoint = solarlab_domain::CheckpointId(read_id(&env, &checkpoint_id)?);
        transport::undo(
            checked_handle(handle)?,
            checkpoint,
            committed_revision as u64,
            recorded,
        )
    })();
    if let Err(message) = result {
        fail(&mut env, message);
    }
}
