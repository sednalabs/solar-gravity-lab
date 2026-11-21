//! Isolated research calls. No runtime handle is accepted, so experiments cannot
//! mutate a live world, checkpoint, or private saved session.

use jni::objects::{JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

fn response(env: &JNIEnv<'_>, text: &str) -> jstring {
    env.new_string(text)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_benchmark_NativeBridge_nativeCatalogue(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    response(&env, &solarlab_benchmarks::catalogue_json())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_sednalabs_solarlab_benchmark_NativeBridge_nativeRunCase(
    mut env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    case_id: JString<'_>,
    fixture_id: JString<'_>,
    steps: jint,
    repeats: jint,
) -> jstring {
    // Bound before conversion; negative Java integers must never become huge
    // native work requests. The engine independently validates its own limits.
    if !(1..=64).contains(&steps) || !(1..=11).contains(&repeats) {
        return response(&env, r#"{"schema_version":1,"status":"FAIL","reason":"steps must be 1..64 and repeats 1..11"}"#);
    }
    let Ok(case) = env.get_string(&case_id) else {
        return std::ptr::null_mut();
    };
    let case: String = case.into();
    let Ok(fixture) = env.get_string(&fixture_id) else {
        return std::ptr::null_mut();
    };
    let fixture: String = fixture.into();
    if case.len() > 128 || fixture.len() > 64 {
        return response(&env, r#"{"schema_version":1,"status":"FAIL","reason":"invalid identifier length"}"#);
    }
    // Do not unwind across JNI. Failed evidence is retained as failed evidence.
    let result = std::panic::catch_unwind(|| {
        solarlab_benchmarks::run_case_json(&case, &fixture, steps as u32, repeats as u32)
    })
    .unwrap_or_else(|_| {
        r#"{"schema_version":1,"status":"FAIL","reason":"native experiment panicked"}"#.to_owned()
    });
    response(&env, &result)
}
