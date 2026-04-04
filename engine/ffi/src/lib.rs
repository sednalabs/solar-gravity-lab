#![allow(unsafe_code)]

use std::collections::HashMap;
use std::str;
use std::sync::{Mutex, OnceLock};

use solarlab_domain::{BranchId, ObserverMode, ScenarioId, TimelineSemantics};
use solarlab_hardware::{CpuBackend, GpuBackend, HardwareProfile};
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{RuntimeConfig, WorldRuntime};

pub const SOLARLAB_V2_ABI_VERSION: u32 = 1;
pub const SL_V2_ID_CAPACITY: usize = 96;

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct SlRuntimeHandle {
    pub raw: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlStatusCode {
    Ok = 0,
    InvalidArgument = 1,
    NotReady = 2,
    InternalError = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlResult {
    pub code: SlStatusCode,
    pub detail_length: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlCpuBackend {
    ReferenceScalar = 0,
    SimdArm64 = 1,
    SimdX64 = 2,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlGpuBackend {
    None = 0,
    Vulkan = 1,
    Metal = 2,
    WebGpuClass = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlTimelineSemantics {
    AbsoluteEpoch = 0,
    BranchedSandbox = 1,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlObserverMode {
    Free = 0,
    FollowSelected = 1,
    FollowHost = 2,
    SystemFrame = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlRuntimeInfo {
    pub abi_version: u32,
    pub cpu_backend: SlCpuBackend,
    pub gpu_backend: SlGpuBackend,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlSessionCreateParams {
    pub scenario_id: [u8; SL_V2_ID_CAPACITY],
    pub scenario_id_len: u32,
    pub root_branch_id: [u8; SL_V2_ID_CAPACITY],
    pub root_branch_id_len: u32,
    pub created_at_unix_ms: i64,
    pub timeline_semantics: u32,
    pub live_updates_enabled: u8,
    pub cpu_backend: u32,
    pub gpu_backend: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionSnapshotSummary {
    pub scenario_id: [u8; SL_V2_ID_CAPACITY],
    pub scenario_id_len: u32,
    pub active_branch_id: [u8; SL_V2_ID_CAPACITY],
    pub active_branch_id_len: u32,
    pub epoch_seconds: f64,
    pub body_count: u32,
    pub active_checkpoint_present: u8,
    pub paused: u8,
    pub sim_seconds_per_real_second: f64,
    pub timeline_semantics: SlTimelineSemantics,
    pub observer_mode: SlObserverMode,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionCreateResult {
    pub result: SlResult,
    pub handle: SlRuntimeHandle,
    pub runtime_info: SlRuntimeInfo,
    pub snapshot_summary: SlSessionSnapshotSummary,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlRuntimeInfoResult {
    pub result: SlResult,
    pub info: SlRuntimeInfo,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionSnapshotSummaryResult {
    pub result: SlResult,
    pub summary: SlSessionSnapshotSummary,
}

#[derive(Debug)]
struct RuntimeSession {
    runtime: WorldRuntime,
}

#[derive(Debug, Default)]
struct SessionRegistry {
    next_handle: u64,
    sessions: HashMap<u64, RuntimeSession>,
}

impl SessionRegistry {
    fn insert(&mut self, session: RuntimeSession) -> SlRuntimeHandle {
        if self.next_handle == 0 {
            self.next_handle = 1;
        }

        loop {
            let candidate = self.next_handle;
            self.next_handle = self.next_handle.wrapping_add(1);
            if candidate != 0 && !self.sessions.contains_key(&candidate) {
                self.sessions.insert(candidate, session);
                return SlRuntimeHandle { raw: candidate };
            }
        }
    }

    fn remove(&mut self, handle: SlRuntimeHandle) -> bool {
        self.sessions.remove(&handle.raw).is_some()
    }

    fn get(&self, handle: SlRuntimeHandle) -> Option<&RuntimeSession> {
        self.sessions.get(&handle.raw)
    }
}

fn registry() -> &'static Mutex<SessionRegistry> {
    static REGISTRY: OnceLock<Mutex<SessionRegistry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(SessionRegistry::default()))
}

#[must_use]
pub fn abi_version() -> u32 {
    SOLARLAB_V2_ABI_VERSION
}

#[must_use]
pub fn runtime_info(cpu_backend: CpuBackend, gpu_backend: GpuBackend) -> SlRuntimeInfo {
    SlRuntimeInfo {
        abi_version: SOLARLAB_V2_ABI_VERSION,
        cpu_backend: encode_cpu_backend(&cpu_backend),
        gpu_backend: encode_gpu_backend(&gpu_backend),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn sl_v2_abi_version() -> u32 {
    abi_version()
}

#[unsafe(no_mangle)]
pub extern "C" fn sl_v2_session_create(params: SlSessionCreateParams) -> SlSessionCreateResult {
    let build_outcome = build_session(params);
    let (runtime, info, summary) = match build_outcome {
        Ok(value) => value,
        Err(result) => {
            return SlSessionCreateResult {
                result,
                handle: SlRuntimeHandle::default(),
                runtime_info: empty_runtime_info(),
                snapshot_summary: empty_snapshot_summary(),
            };
        }
    };

    let mut registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlSessionCreateResult {
                result: status(SlStatusCode::InternalError),
                handle: SlRuntimeHandle::default(),
                runtime_info: empty_runtime_info(),
                snapshot_summary: empty_snapshot_summary(),
            };
        }
    };

    let handle = registry.insert(RuntimeSession { runtime });

    SlSessionCreateResult {
        result: status(SlStatusCode::Ok),
        handle,
        runtime_info: info,
        snapshot_summary: summary,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn sl_v2_session_destroy(handle: SlRuntimeHandle) -> SlResult {
    if handle.raw == 0 {
        return status(SlStatusCode::InvalidArgument);
    }

    let mut registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => return status(SlStatusCode::InternalError),
    };

    if registry.remove(handle) {
        status(SlStatusCode::Ok)
    } else {
        status(SlStatusCode::NotReady)
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn sl_v2_session_runtime_info(handle: SlRuntimeHandle) -> SlRuntimeInfoResult {
    if handle.raw == 0 {
        return SlRuntimeInfoResult {
            result: status(SlStatusCode::InvalidArgument),
            info: empty_runtime_info(),
        };
    }

    let registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlRuntimeInfoResult {
                result: status(SlStatusCode::InternalError),
                info: empty_runtime_info(),
            };
        }
    };

    let Some(session) = registry.get(handle) else {
        return SlRuntimeInfoResult {
            result: status(SlStatusCode::NotReady),
            info: empty_runtime_info(),
        };
    };

    let snapshot = session.runtime.snapshot();

    SlRuntimeInfoResult {
        result: status(SlStatusCode::Ok),
        info: runtime_info(
            snapshot.hardware_profile.cpu_backend,
            snapshot.hardware_profile.gpu_backend,
        ),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn sl_v2_session_snapshot_summary(
    handle: SlRuntimeHandle,
) -> SlSessionSnapshotSummaryResult {
    if handle.raw == 0 {
        return SlSessionSnapshotSummaryResult {
            result: status(SlStatusCode::InvalidArgument),
            summary: empty_snapshot_summary(),
        };
    }

    let registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlSessionSnapshotSummaryResult {
                result: status(SlStatusCode::InternalError),
                summary: empty_snapshot_summary(),
            };
        }
    };

    let Some(session) = registry.get(handle) else {
        return SlSessionSnapshotSummaryResult {
            result: status(SlStatusCode::NotReady),
            summary: empty_snapshot_summary(),
        };
    };

    let summary = match snapshot_summary(&session.runtime) {
        Ok(summary) => summary,
        Err(result) => {
            return SlSessionSnapshotSummaryResult {
                result,
                summary: empty_snapshot_summary(),
            };
        }
    };

    SlSessionSnapshotSummaryResult {
        result: status(SlStatusCode::Ok),
        summary,
    }
}

fn build_session(
    params: SlSessionCreateParams,
) -> Result<(WorldRuntime, SlRuntimeInfo, SlSessionSnapshotSummary), SlResult> {
    let scenario_id = decode_identifier(&params.scenario_id, params.scenario_id_len)?;
    let root_branch_id = decode_identifier(&params.root_branch_id, params.root_branch_id_len)?;
    let timeline_semantics = decode_timeline_semantics(params.timeline_semantics)?;
    let cpu_backend = decode_cpu_backend(params.cpu_backend)?;
    let gpu_backend = decode_gpu_backend(params.gpu_backend)?;

    let runtime_config = RuntimeConfig {
        physics: PhysicsPolicy {
            solver_backend: solver_for_cpu_backend(&cpu_backend),
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: 1.0,
        },
        timeline_semantics,
        live_updates_enabled: params.live_updates_enabled != 0,
    };

    let hardware_profile = HardwareProfile {
        cpu_backend: cpu_backend.clone(),
        gpu_backend: gpu_backend.clone(),
        cpu_features: Vec::new(),
        gpu_features: Vec::new(),
        acceleration_modes: Vec::new(),
    };

    let runtime = WorldRuntime::new(
        ScenarioId(scenario_id),
        BranchId(root_branch_id),
        runtime_config,
        hardware_profile,
        params.created_at_unix_ms,
    );

    let info = runtime_info(cpu_backend, gpu_backend);
    let summary = snapshot_summary(&runtime)?;

    Ok((runtime, info, summary))
}

fn snapshot_summary(runtime: &WorldRuntime) -> Result<SlSessionSnapshotSummary, SlResult> {
    let snapshot = runtime.snapshot();

    Ok(SlSessionSnapshotSummary {
        scenario_id: encode_identifier(&snapshot.scenario_id.0)?,
        scenario_id_len: string_length_to_u32(&snapshot.scenario_id.0),
        active_branch_id: encode_identifier(&snapshot.branch_id.0)?,
        active_branch_id_len: string_length_to_u32(&snapshot.branch_id.0),
        epoch_seconds: snapshot.epoch_seconds,
        body_count: usize_to_u32(snapshot.bodies.len()),
        active_checkpoint_present: u8::from(snapshot.active_checkpoint.is_some()),
        paused: u8::from(snapshot.playback.paused),
        sim_seconds_per_real_second: snapshot.playback.sim_seconds_per_real_second,
        timeline_semantics: encode_timeline_semantics(&snapshot.timeline_semantics),
        observer_mode: encode_observer_mode(&snapshot.observer.mode),
    })
}

fn decode_identifier(bytes: &[u8; SL_V2_ID_CAPACITY], length: u32) -> Result<String, SlResult> {
    let length = usize::try_from(length).map_err(|_| status(SlStatusCode::InvalidArgument))?;
    if length == 0 || length > SL_V2_ID_CAPACITY {
        return Err(status(SlStatusCode::InvalidArgument));
    }

    let data = &bytes[..length];
    let value = str::from_utf8(data).map_err(|_| status(SlStatusCode::InvalidArgument))?;
    Ok(value.to_owned())
}

fn encode_identifier(value: &str) -> Result<[u8; SL_V2_ID_CAPACITY], SlResult> {
    if value.len() > SL_V2_ID_CAPACITY {
        return Err(status(SlStatusCode::InternalError));
    }

    let mut bytes = [0u8; SL_V2_ID_CAPACITY];
    bytes[..value.len()].copy_from_slice(value.as_bytes());
    Ok(bytes)
}

fn decode_cpu_backend(value: u32) -> Result<CpuBackend, SlResult> {
    match value {
        0 => Ok(CpuBackend::ReferenceScalar),
        1 => Ok(CpuBackend::SimdArm64),
        2 => Ok(CpuBackend::SimdX64),
        _ => Err(status(SlStatusCode::InvalidArgument)),
    }
}

fn decode_gpu_backend(value: u32) -> Result<GpuBackend, SlResult> {
    match value {
        0 => Ok(GpuBackend::None),
        1 => Ok(GpuBackend::Vulkan),
        2 => Ok(GpuBackend::Metal),
        3 => Ok(GpuBackend::WebGpuClass),
        _ => Err(status(SlStatusCode::InvalidArgument)),
    }
}

fn decode_timeline_semantics(value: u32) -> Result<TimelineSemantics, SlResult> {
    match value {
        0 => Ok(TimelineSemantics::AbsoluteEpoch),
        1 => Ok(TimelineSemantics::BranchedSandbox),
        _ => Err(status(SlStatusCode::InvalidArgument)),
    }
}

fn solver_for_cpu_backend(cpu_backend: &CpuBackend) -> SolverBackend {
    match cpu_backend {
        CpuBackend::ReferenceScalar => SolverBackend::ReferenceScalar,
        CpuBackend::SimdArm64 => SolverBackend::SimdArm64,
        CpuBackend::SimdX64 => SolverBackend::SimdX64,
    }
}

fn encode_cpu_backend(cpu_backend: &CpuBackend) -> SlCpuBackend {
    match cpu_backend {
        CpuBackend::ReferenceScalar => SlCpuBackend::ReferenceScalar,
        CpuBackend::SimdArm64 => SlCpuBackend::SimdArm64,
        CpuBackend::SimdX64 => SlCpuBackend::SimdX64,
    }
}

fn encode_gpu_backend(gpu_backend: &GpuBackend) -> SlGpuBackend {
    match gpu_backend {
        GpuBackend::None => SlGpuBackend::None,
        GpuBackend::Vulkan => SlGpuBackend::Vulkan,
        GpuBackend::Metal => SlGpuBackend::Metal,
        GpuBackend::WebGpuClass => SlGpuBackend::WebGpuClass,
    }
}

fn encode_timeline_semantics(value: &TimelineSemantics) -> SlTimelineSemantics {
    match value {
        TimelineSemantics::AbsoluteEpoch => SlTimelineSemantics::AbsoluteEpoch,
        TimelineSemantics::BranchedSandbox => SlTimelineSemantics::BranchedSandbox,
    }
}

fn encode_observer_mode(value: &ObserverMode) -> SlObserverMode {
    match value {
        ObserverMode::Free => SlObserverMode::Free,
        ObserverMode::FollowSelected => SlObserverMode::FollowSelected,
        ObserverMode::FollowHost => SlObserverMode::FollowHost,
        ObserverMode::SystemFrame => SlObserverMode::SystemFrame,
    }
}

fn string_length_to_u32(value: &str) -> u32 {
    usize_to_u32(value.len())
}

fn usize_to_u32(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}

fn status(code: SlStatusCode) -> SlResult {
    SlResult {
        code,
        detail_length: 0,
    }
}

fn empty_runtime_info() -> SlRuntimeInfo {
    SlRuntimeInfo {
        abi_version: SOLARLAB_V2_ABI_VERSION,
        cpu_backend: SlCpuBackend::ReferenceScalar,
        gpu_backend: SlGpuBackend::None,
    }
}

fn empty_snapshot_summary() -> SlSessionSnapshotSummary {
    SlSessionSnapshotSummary {
        scenario_id: [0; SL_V2_ID_CAPACITY],
        scenario_id_len: 0,
        active_branch_id: [0; SL_V2_ID_CAPACITY],
        active_branch_id_len: 0,
        epoch_seconds: 0.0,
        body_count: 0,
        active_checkpoint_present: 0,
        paused: 0,
        sim_seconds_per_real_second: 0.0,
        timeline_semantics: SlTimelineSemantics::AbsoluteEpoch,
        observer_mode: SlObserverMode::Free,
    }
}

#[cfg(target_os = "android")]
mod android_jni {
    use super::{
        sl_v2_session_create, sl_v2_session_destroy, sl_v2_session_runtime_info,
        sl_v2_session_snapshot_summary, status, SlResult, SlRuntimeHandle, SlSessionCreateParams,
        SlSessionCreateResult, SlSessionSnapshotSummaryResult, SlStatusCode, SL_V2_ID_CAPACITY,
    };
    use jni::objects::{JByteArray, JObject, JValue};
    use jni::sys::{jboolean, jbyteArray, jint, jlong, jobject};
    use jni::JNIEnv;

    const CLASS_NATIVE_RESULT: &str = "com/sednalabs/solarlab/runtime/NativeResult";
    const CLASS_NATIVE_CREATE_SESSION_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeCreateSessionResult";
    const CLASS_NATIVE_RUNTIME_INFO_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeRuntimeInfoResult";
    const CLASS_NATIVE_SNAPSHOT_SUMMARY_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeSnapshotSummaryResult";

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeCreateSession(
        mut env: JNIEnv,
        _this: JObject,
        scenario_id_utf8: jbyteArray,
        root_branch_id_utf8: jbyteArray,
        created_at_unix_ms: jlong,
        timeline_semantics: jint,
        live_updates_enabled: jboolean,
        cpu_backend: jint,
        gpu_backend: jint,
    ) -> jobject {
        let create_result = build_session_create_result(
            &env,
            scenario_id_utf8,
            root_branch_id_utf8,
            created_at_unix_ms,
            timeline_semantics,
            live_updates_enabled,
            cpu_backend,
            gpu_backend,
        );

        match create_native_create_session_result(&mut env, &create_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeDestroySession(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let native_result = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_destroy(SlRuntimeHandle { raw: handle_value })
        } else {
            status(SlStatusCode::InvalidArgument)
        };

        match create_native_result(&mut env, native_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeRuntimeInfo(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let runtime_info = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_runtime_info(SlRuntimeHandle { raw: handle_value })
        } else {
            super::SlRuntimeInfoResult {
                result: status(SlStatusCode::InvalidArgument),
                info: super::empty_runtime_info(),
            }
        };

        match create_native_runtime_info_result(&mut env, runtime_info) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeSnapshotSummary(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let snapshot_summary = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_snapshot_summary(SlRuntimeHandle { raw: handle_value })
        } else {
            SlSessionSnapshotSummaryResult {
                result: status(SlStatusCode::InvalidArgument),
                summary: super::empty_snapshot_summary(),
            }
        };

        match create_native_snapshot_summary_result(&mut env, snapshot_summary) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    fn build_session_create_result(
        env: &JNIEnv,
        scenario_id_utf8: jbyteArray,
        root_branch_id_utf8: jbyteArray,
        created_at_unix_ms: jlong,
        timeline_semantics: jint,
        live_updates_enabled: jboolean,
        cpu_backend: jint,
        gpu_backend: jint,
    ) -> SlSessionCreateResult {
        let (scenario_id, scenario_id_len) = match decode_java_id_bytes(env, scenario_id_utf8) {
            Ok(value) => value,
            Err(result) => return create_error_session(result),
        };

        let (root_branch_id, root_branch_id_len) =
            match decode_java_id_bytes(env, root_branch_id_utf8) {
                Ok(value) => value,
                Err(result) => return create_error_session(result),
            };

        let created_at_unix_ms = match created_at_unix_ms.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        let timeline_semantics = match timeline_semantics.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        let cpu_backend = match cpu_backend.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        let gpu_backend = match gpu_backend.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        sl_v2_session_create(SlSessionCreateParams {
            scenario_id,
            scenario_id_len,
            root_branch_id,
            root_branch_id_len,
            created_at_unix_ms,
            timeline_semantics,
            live_updates_enabled: u8::from(live_updates_enabled != 0),
            cpu_backend,
            gpu_backend,
        })
    }

    fn decode_java_id_bytes(
        env: &JNIEnv,
        value: jbyteArray,
    ) -> Result<([u8; SL_V2_ID_CAPACITY], u32), SlResult> {
        let array = unsafe { JByteArray::from_raw(value) };
        let bytes = env
            .convert_byte_array(&array)
            .map_err(|_| status(SlStatusCode::InvalidArgument))?;

        if bytes.is_empty() || bytes.len() > SL_V2_ID_CAPACITY {
            return Err(status(SlStatusCode::InvalidArgument));
        }

        let mut output = [0_u8; SL_V2_ID_CAPACITY];
        output[..bytes.len()].copy_from_slice(&bytes);
        let length = u32::try_from(bytes.len()).expect("validated Java ID length must fit u32");
        Ok((output, length))
    }

    fn create_error_session(result: SlResult) -> SlSessionCreateResult {
        SlSessionCreateResult {
            result,
            handle: SlRuntimeHandle::default(),
            runtime_info: super::empty_runtime_info(),
            snapshot_summary: super::empty_snapshot_summary(),
        }
    }

    fn create_native_result(env: &mut JNIEnv, result: SlResult) -> jni::errors::Result<JObject> {
        let context = env.new_string(status_label(result.code))?;
        let context_obj = JObject::from(context);
        env.new_object(
            CLASS_NATIVE_RESULT,
            "(ILjava/lang/String;)V",
            &[
                JValue::Int(status_code_as_i32(result.code)),
                JValue::Object(&context_obj),
            ],
        )
    }

    fn create_native_create_session_result(
        env: &mut JNIEnv,
        result: &SlSessionCreateResult,
    ) -> jni::errors::Result<JObject> {
        let native_result = create_native_result(env, result.result)?;
        env.new_object(
            CLASS_NATIVE_CREATE_SESSION_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;JIII)V",
            &[
                JValue::Object(&native_result),
                JValue::Long(i64::try_from(result.handle.raw).unwrap_or(i64::MAX)),
                JValue::Int(i32::try_from(result.runtime_info.abi_version).unwrap_or(i32::MAX)),
                JValue::Int(result.runtime_info.cpu_backend as i32),
                JValue::Int(result.runtime_info.gpu_backend as i32),
            ],
        )
    }

    fn create_native_runtime_info_result(
        env: &mut JNIEnv,
        result: super::SlRuntimeInfoResult,
    ) -> jni::errors::Result<JObject> {
        let native_result = create_native_result(env, result.result)?;
        env.new_object(
            CLASS_NATIVE_RUNTIME_INFO_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;III)V",
            &[
                JValue::Object(&native_result),
                JValue::Int(i32::try_from(result.info.abi_version).unwrap_or(i32::MAX)),
                JValue::Int(result.info.cpu_backend as i32),
                JValue::Int(result.info.gpu_backend as i32),
            ],
        )
    }

    fn create_native_snapshot_summary_result(
        env: &mut JNIEnv,
        result: SlSessionSnapshotSummaryResult,
    ) -> jni::errors::Result<JObject> {
        let native_result = create_native_result(env, result.result)?;
        let scenario_id = env.new_string(id_from_summary(
            &result.summary.scenario_id,
            result.summary.scenario_id_len,
        ))?;
        let active_branch_id = env.new_string(id_from_summary(
            &result.summary.active_branch_id,
            result.summary.active_branch_id_len,
        ))?;
        let scenario_id_obj = JObject::from(scenario_id);
        let active_branch_id_obj = JObject::from(active_branch_id);

        env.new_object(
            CLASS_NATIVE_SNAPSHOT_SUMMARY_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;Ljava/lang/String;Ljava/lang/String;I)V",
            &[
                JValue::Object(&native_result),
                JValue::Object(&scenario_id_obj),
                JValue::Object(&active_branch_id_obj),
                JValue::Int(i32::try_from(result.summary.body_count).unwrap_or(i32::MAX)),
            ],
        )
    }

    fn id_from_summary(bytes: &[u8; SL_V2_ID_CAPACITY], length: u32) -> String {
        super::decode_identifier(bytes, length).unwrap_or_default()
    }

    fn status_label(code: SlStatusCode) -> &'static str {
        match code {
            SlStatusCode::Ok => "ok",
            SlStatusCode::InvalidArgument => "invalid argument",
            SlStatusCode::NotReady => "not ready",
            SlStatusCode::InternalError => "internal error",
        }
    }

    fn status_code_as_i32(code: SlStatusCode) -> i32 {
        match code {
            SlStatusCode::Ok => 0,
            SlStatusCode::InvalidArgument => 1,
            SlStatusCode::NotReady => 2,
            SlStatusCode::InternalError => 3,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::id_from_summary;
        use crate::SL_V2_ID_CAPACITY;

        #[test]
        fn id_from_summary_returns_utf8_when_valid() {
            let mut bytes = [0_u8; SL_V2_ID_CAPACITY];
            bytes[..4].copy_from_slice(b"main");
            assert_eq!(id_from_summary(&bytes, 4), "main");
        }

        #[test]
        fn id_from_summary_falls_back_to_empty_when_invalid() {
            let mut bytes = [0_u8; SL_V2_ID_CAPACITY];
            bytes[0] = 0xFF;
            assert_eq!(id_from_summary(&bytes, 1), "");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{
        sl_v2_abi_version, sl_v2_session_create, sl_v2_session_destroy, sl_v2_session_runtime_info,
        sl_v2_session_snapshot_summary, SlCpuBackend, SlGpuBackend, SlSessionCreateParams,
        SlStatusCode, SlTimelineSemantics, SL_V2_ID_CAPACITY, SOLARLAB_V2_ABI_VERSION,
    };

    #[test]
    fn abi_version_matches_constant() {
        assert_eq!(sl_v2_abi_version(), SOLARLAB_V2_ABI_VERSION);
    }

    #[test]
    fn create_query_and_destroy_session() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);
        assert_ne!(create.handle.raw, 0);

        assert_eq!(
            create.runtime_info.cpu_backend,
            SlCpuBackend::ReferenceScalar
        );
        assert_eq!(create.runtime_info.gpu_backend, SlGpuBackend::None);

        let summary = sl_v2_session_snapshot_summary(create.handle);
        assert_eq!(summary.result.code, SlStatusCode::Ok);
        assert_eq!(summary.summary.body_count, 0);
        assert_eq!(summary.summary.paused, 1);
        assert_eq!(summary.summary.active_checkpoint_present, 0);
        assert_eq!(
            summary.summary.timeline_semantics,
            SlTimelineSemantics::BranchedSandbox
        );

        let runtime_info = sl_v2_session_runtime_info(create.handle);
        assert_eq!(runtime_info.result.code, SlStatusCode::Ok);
        assert_eq!(runtime_info.info.cpu_backend, SlCpuBackend::ReferenceScalar);
        assert_eq!(runtime_info.info.gpu_backend, SlGpuBackend::None);

        let destroy = sl_v2_session_destroy(create.handle);
        assert_eq!(destroy.code, SlStatusCode::Ok);

        let stale_summary = sl_v2_session_snapshot_summary(create.handle);
        assert_eq!(stale_summary.result.code, SlStatusCode::NotReady);
    }

    #[test]
    fn rejects_invalid_create_parameters() {
        let mut params = new_params("ok", "main");
        params.cpu_backend = 9;

        let create = sl_v2_session_create(params);
        assert_eq!(create.result.code, SlStatusCode::InvalidArgument);
        assert_eq!(create.handle.raw, 0);

        let mut invalid_utf8 = new_params("ok", "main");
        invalid_utf8.scenario_id[0] = 0xFF;

        let create = sl_v2_session_create(invalid_utf8);
        assert_eq!(create.result.code, SlStatusCode::InvalidArgument);
    }

    #[test]
    fn rejects_destroy_for_unknown_handle() {
        let destroy = sl_v2_session_destroy(super::SlRuntimeHandle { raw: 999_999 });
        assert_eq!(destroy.code, SlStatusCode::NotReady);
    }

    fn new_params(scenario: &str, branch: &str) -> SlSessionCreateParams {
        let mut scenario_id = [0u8; SL_V2_ID_CAPACITY];
        scenario_id[..scenario.len()].copy_from_slice(scenario.as_bytes());

        let mut root_branch_id = [0u8; SL_V2_ID_CAPACITY];
        root_branch_id[..branch.len()].copy_from_slice(branch.as_bytes());

        SlSessionCreateParams {
            scenario_id,
            scenario_id_len: u32::try_from(scenario.len()).expect("scenario id length must fit"),
            root_branch_id,
            root_branch_id_len: u32::try_from(branch.len()).expect("branch id length must fit"),
            created_at_unix_ms: 123,
            timeline_semantics: 1,
            live_updates_enabled: 0,
            cpu_backend: 0,
            gpu_backend: 0,
        }
    }
}
