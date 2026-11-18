//! Bounded save transport. Bytes are owned by callers, never borrowed from a session.
use super::*;
use solarlab_runtime::{SessionPersistenceError, MAX_SAVED_SESSION_BYTES};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct SlSaveWriteResult {
    pub result: SlResult,
    /// Required buffer size, also when WorkLimit says the caller's buffer is too small.
    pub length: u32,
}

fn persistence_status(error: SessionPersistenceError) -> SlResult {
    status(match error {
        SessionPersistenceError::AdvanceInProgress => SlStatusCode::Busy,
        SessionPersistenceError::TooLarge => SlStatusCode::WorkLimit,
        _ => SlStatusCode::InvalidArgument,
    })
}

pub(super) fn export_save(handle: SlRuntimeHandle) -> Result<Vec<u8>, SlResult> {
    if handle.raw == 0 {
        return Err(status(SlStatusCode::InvalidArgument));
    }
    let registry = registry()
        .lock()
        .map_err(|_| status(SlStatusCode::InternalError))?;
    let session = registry
        .get(handle)
        .ok_or_else(|| status(SlStatusCode::NotReady))?;
    session
        .runtime
        .export_saved_session()
        .map_err(persistence_status)
}

pub(super) fn import_save(template: SlRuntimeHandle, bytes: &[u8]) -> SlSessionCreateResult {
    let result = (|| {
        if template.raw == 0 || bytes.len() > MAX_SAVED_SESSION_BYTES {
            return Err(status(SlStatusCode::InvalidArgument));
        }
        let hardware = {
            let registry = registry()
                .lock()
                .map_err(|_| status(SlStatusCode::InternalError))?;
            registry
                .get(template)
                .ok_or_else(|| status(SlStatusCode::NotReady))?
                .runtime
                .snapshot()
                .hardware_profile
        };
        // Authored definitions are materialised into the same native world as saves.
        // The returned session is detached until the host explicitly opens it.
        let header: serde_json::Value = serde_json::from_slice(bytes)
            .map_err(|_| status(SlStatusCode::InvalidArgument))?;
        let runtime = match header.get("format").and_then(serde_json::Value::as_str) {
            Some("solarlab.scenario") => WorldRuntime::import_authored_scenario(bytes, hardware, 0)
                .map_err(|_| status(SlStatusCode::InvalidArgument))?,
            Some("solarlab.spacecraft") => {
                let mut staged = registry().lock().map_err(|_| status(SlStatusCode::InternalError))?
                    .get(template).ok_or_else(|| status(SlStatusCode::NotReady))?.runtime.clone();
                staged.apply_command(WorldCommand::PausePlayback, 0).map_err(runtime_error_to_status)?;
                staged.add_authored_spacecraft(bytes, 0).map_err(|_| status(SlStatusCode::InvalidArgument))?;
                staged
            }
            Some("solarlab.experiment") => WorldRuntime::import_saved_session(bytes, hardware).map_err(persistence_status)?,
            _ => return Err(status(SlStatusCode::InvalidArgument)),
        };
        let report = runtime.active_solver_execution_report();
        let info = runtime_info(
            cpu_backend_for_solver_backend(&report.requested_backend),
            runtime.snapshot().hardware_profile.gpu_backend,
            report,
        );
        let summary = snapshot_summary(&runtime)?;
        let mut registry = registry()
            .lock()
            .map_err(|_| status(SlStatusCode::InternalError))?;
        let handle = registry.insert(RuntimeSession {
            runtime,
            runtime_info: info,
            vulkan_scene_adapter: VulkanSceneAdapter::default(),
        });
        Ok((handle, info, summary))
    })();
    match result {
        Ok((handle, info, summary)) => SlSessionCreateResult {
            result: status(SlStatusCode::Ok),
            handle,
            runtime_info: info,
            snapshot_summary: summary,
        },
        Err(result) => SlSessionCreateResult {
            result,
            handle: SlRuntimeHandle::default(),
            runtime_info: empty_runtime_info(),
            snapshot_summary: empty_snapshot_summary(),
        },
    }
}

/// Copy one committed save document into caller-owned memory. Pass capacity=0 to
/// query the required length; that query does not pin a snapshot, so callers must
/// retry WorkLimit if their session changes before the copy.
///
/// # Safety
/// For nonzero capacity, buffer must reference that many writable bytes. The
/// caller must not mutate the session concurrently if it requires a fixed snapshot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sl_v2_session_export_save(
    handle: SlRuntimeHandle,
    buffer: *mut u8,
    capacity: u32,
) -> SlSaveWriteResult {
    let bytes = match export_save(handle) {
        Ok(bytes) => bytes,
        Err(result) => return SlSaveWriteResult { result, length: 0 },
    };
    let length = bytes.len() as u32;
    if capacity < length {
        return SlSaveWriteResult {
            result: status(SlStatusCode::WorkLimit),
            length,
        };
    }
    if buffer.is_null() {
        return SlSaveWriteResult {
            result: status(SlStatusCode::InvalidArgument),
            length,
        };
    }
    // SAFETY: caller promises writable capacity and the length was checked above.
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer, bytes.len());
    }
    SlSaveWriteResult {
        result: status(SlStatusCode::Ok),
        length,
    }
}

/// Construct a new paused session using the live template's host capabilities.
/// The template session is unchanged on success or failure and remains caller-owned.
///
/// # Safety
/// bytes must point to length readable bytes for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sl_v2_session_import_save(
    template: SlRuntimeHandle,
    bytes: *const u8,
    length: u32,
) -> SlSessionCreateResult {
    if bytes.is_null() || length == 0 || length as usize > MAX_SAVED_SESSION_BYTES {
        return SlSessionCreateResult {
            result: status(SlStatusCode::InvalidArgument),
            handle: SlRuntimeHandle::default(),
            runtime_info: empty_runtime_info(),
            snapshot_summary: empty_snapshot_summary(),
        };
    }
    // SAFETY: caller promises the bounded readable buffer.
    import_save(template, unsafe {
        std::slice::from_raw_parts(bytes, length as usize)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authored_documents_materialise_separately_and_export_as_portable_saves() {
        let created = sl_v2_session_create(super::super::tests::new_params("sol-system", "main"));
        let before = export_save(created.handle).unwrap();
        let scenario = import_save(created.handle, include_bytes!("../../data/authoring/orbital-workshop.scenario.json"));
        assert_eq!(scenario.result.code, SlStatusCode::Ok);
        assert_ne!(scenario.handle, created.handle);
        assert_eq!(export_save(created.handle).unwrap(), before);
        let original_scenario = export_save(scenario.handle).unwrap();
        let craft = import_save(scenario.handle, include_bytes!("../../data/authoring/iris.spacecraft.json"));
        assert_eq!(craft.result.code, SlStatusCode::Ok);
        assert_eq!(export_save(scenario.handle).unwrap(), original_scenario);
        let canonical = export_save(craft.handle).unwrap();
        let document: serde_json::Value = serde_json::from_slice(&canonical).unwrap();
        assert_eq!(document["format"], "solarlab.experiment");
        let restored = import_save(created.handle, &canonical);
        assert_eq!(restored.result.code, SlStatusCode::Ok);
        assert_eq!(restored.snapshot_summary.paused, 1);
        for handle in [restored.handle, craft.handle, scenario.handle, created.handle] {
            assert_eq!(sl_v2_session_destroy(handle).code, SlStatusCode::Ok);
        }
    }

    #[test]
    fn ffi_save_roundtrip_allocates_new_handle_and_bad_import_preserves_original() {
        let created = sl_v2_session_create(super::super::tests::new_params("sol-system", "main"));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let saved = export_save(created.handle).unwrap();
        let bad = import_save(created.handle, b"{malformed}");
        assert_eq!(bad.result.code, SlStatusCode::InvalidArgument);
        assert_eq!(bad.handle.raw, 0);
        assert_eq!(
            sl_v2_session_snapshot_summary(created.handle).result.code,
            SlStatusCode::Ok
        );
        let restored = import_save(created.handle, &saved);
        assert_eq!(restored.result.code, SlStatusCode::Ok);
        assert_ne!(restored.handle, created.handle);
        assert_eq!(restored.snapshot_summary.paused, 1);
        let query = unsafe { sl_v2_session_export_save(created.handle, std::ptr::null_mut(), 0) };
        assert_eq!(query.result.code, SlStatusCode::WorkLimit);
        let mut bytes = vec![0; query.length as usize];
        let copied =
            unsafe { sl_v2_session_export_save(created.handle, bytes.as_mut_ptr(), query.length) };
        assert_eq!(copied.result.code, SlStatusCode::Ok);
        assert_eq!(bytes, saved);
        sl_v2_session_destroy(restored.handle);
        assert_eq!(sl_v2_session_destroy(created.handle).code, SlStatusCode::Ok);
    }
}
