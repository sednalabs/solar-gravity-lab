//! Owned numerical requests across JNI. No raw buffers or borrowed world state.
use super::*;
use solarlab_runtime::{ProbeCommitReceipt, ProbePreview};

pub(super) fn body_values(body: &BodyState) -> [f64; 10] {
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
    [
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
    ]
}

pub(super) fn body_from_values(id: String, values: &[f64]) -> Result<BodyState, String> {
    if values.len() != 10 || values.iter().any(|value| !value.is_finite()) {
        return Err("Invalid probe state payload".into());
    }
    let body_class = match values[0] {
        0.0 => BodyClass::Star,
        1.0 => BodyClass::Planet,
        2.0 => BodyClass::DwarfPlanet,
        3.0 => BodyClass::Moon,
        4.0 => BodyClass::SmallBody,
        5.0 => BodyClass::Tracer,
        6.0 => BodyClass::Spacecraft,
        7.0 => BodyClass::Custom,
        8.0 => BodyClass::Comet,
        _ => return Err("Invalid probe body class".into()),
    };
    Ok(BodyState {
        body_id: BodyId(id),
        body_class,
        mass_kg: values[1],
        source_mass_kg: values[2],
        radius_m: values[3],
        position_m: Vector3d {
            x: values[4],
            y: values[5],
            z: values[6],
        },
        velocity_mps: Vector3d {
            x: values[7],
            y: values[8],
            z: values[9],
        },
    })
}

pub(super) fn preview(
    handle: SlRuntimeHandle,
    candidate: BodyState,
    reference: BodyId,
    horizon: f64,
) -> Result<ProbePreview, String> {
    let plan = {
        let registry = registry()
            .lock()
            .map_err(|_| "Runtime lock is unavailable")?;
        let session = registry
            .get(handle)
            .ok_or("The experiment is no longer open")?;
        session
            .runtime
            .prepare_probe_preview(candidate, &reference, horizon)
            .map_err(|error| error.to_string())?
    };
    // Only the small numerical copy is propagated. The registry lock is released
    // before this work, allowing native render reads and other sessions to proceed.
    plan.run().map_err(|error| error.to_string())
}

pub(super) fn commit(
    handle: SlRuntimeHandle,
    candidate: BodyState,
    revision: u64,
    epoch: f64,
    recorded: i64,
) -> Result<ProbeCommitReceipt, String> {
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let session = registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?;
    session
        .runtime
        .commit_probe(candidate, revision, epoch, recorded)
        .map_err(|error| error.to_string())
}

pub(super) fn undo(
    handle: SlRuntimeHandle,
    checkpoint: CheckpointId,
    committed_revision: u64,
    recorded: i64,
) -> Result<(), String> {
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let session = registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?;
    session
        .runtime
        .undo_probe(&checkpoint, committed_revision, recorded)
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numerical_payload_preserves_source_mass_and_rejects_invalid_shape_or_class() {
        let values = [6.0, 1000.0, 17.0, 2.0, 1e11, 2.0, 3.0, 4.0, 5.0, 6.0];
        let body = body_from_values("probe".into(), &values).unwrap();
        assert_eq!(body_values(&body), values);
        assert!(body_from_values("probe".into(), &values[..9]).is_err());
        for class in [6.5, -1.0, 9.0, f64::NAN] {
            let mut wrong = values;
            wrong[0] = class;
            assert!(body_from_values("probe".into(), &wrong).is_err());
        }
    }

    #[test]
    fn ffi_preview_commit_and_undo_reject_stale_or_destroyed_sessions() {
        let created =
            sl_v2_session_create(super::super::tests::new_params("lab.earth-moon", "main"));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let handle = created.handle;
        let candidate = {
            let mut registry = registry().lock().unwrap();
            let runtime = &mut registry.get_mut(handle).unwrap().runtime;
            runtime
                .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
                .unwrap();
            let mut body = runtime
                .snapshot()
                .bodies
                .into_iter()
                .find(|body| body.body_id.0 == "earth")
                .unwrap();
            let radius = body.radius_m + 800_000.0;
            let speed = (6.67430e-11 * body.source_mass_kg / radius).sqrt();
            body.body_id = BodyId("probe".into());
            body.body_class = BodyClass::Spacecraft;
            body.mass_kg = 1_000.0;
            body.source_mass_kg = 0.0;
            body.radius_m = 2.0;
            body.position_m.x += radius;
            body.velocity_mps.y += speed;
            body
        };
        let before = super::super::persistence::export_save(handle).unwrap();
        let draft = preview(handle, candidate.clone(), BodyId("earth".into()), 3600.0).unwrap();
        assert_eq!(
            before,
            super::super::persistence::export_save(handle).unwrap()
        );
        assert!(commit(
            handle,
            candidate.clone(),
            draft.baseline_revision + 1,
            draft.epoch_seconds,
            2
        )
        .is_err());
        assert_eq!(
            before,
            super::super::persistence::export_save(handle).unwrap()
        );
        let receipt = commit(
            handle,
            candidate.clone(),
            draft.baseline_revision,
            draft.epoch_seconds,
            2,
        )
        .unwrap();
        undo(handle, receipt.checkpoint_id, receipt.committed_revision, 3).unwrap();
        assert_eq!(sl_v2_session_snapshot_summary(handle).summary.body_count, 3);
        sl_v2_session_destroy(handle);
        assert!(preview(handle, candidate.clone(), BodyId("earth".into()), 3600.0).is_err());
        assert!(commit(
            handle,
            candidate,
            draft.baseline_revision,
            draft.epoch_seconds,
            4
        )
        .is_err());
    }
}
