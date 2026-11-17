//! Transactional edits and read-only body inspection without cloning the world.
use super::*;

impl WorldRuntime {
    /// Borrow one body's authoritative state from the active branch.
    /// The borrow cannot outlive the runtime or overlap a mutation. Hosts must
    /// copy their small response while holding their ordinary session lock.
    #[must_use]
    pub fn inspect_body(&self, id: &str) -> Option<&BodyState> {
        self.active_branch().world.bodies.iter().find(|body| body.body_id.0 == id)
    }
}

fn finite_vector(v: Vector3d) -> bool {
    v.x.is_finite() && v.y.is_finite() && v.z.is_finite()
}

pub(super) fn validate(
    bodies: &[BodyState], id: &BodyId, mass: f64, source: f64, radius: f64,
    position: Vector3d, velocity: Vector3d,
) -> Result<PhysicsInvariants, RuntimeError> {
    if !mass.is_finite() || mass < 0.0 || !source.is_finite() || source < 0.0
        || !radius.is_finite() || radius <= 0.0
        || !finite_vector(position) || !finite_vector(velocity) {
        return Err(RuntimeError::InvalidBodyProperties);
    }
    let index = bodies.iter().position(|b| &b.body_id == id)
        .ok_or_else(|| RuntimeError::UnknownBody(id.clone()))?;
    // A finite input can still overflow energy or barycentre. Check the proposed
    // state before touching the live branch; this cost is only paid on edits.
    let mut proposed = bodies.to_vec();
    let body = &mut proposed[index];
    body.mass_kg = mass;
    body.source_mass_kg = source;
    body.radius_m = radius;
    body.position_m = position;
    body.velocity_mps = velocity;
    let inv = compute_world_invariants(&proposed);
    if !inv.total_energy_j.is_finite() || !finite_vector(inv.linear_momentum_kg_mps)
        || !finite_vector(inv.angular_momentum_kg_m2ps) || !finite_vector(inv.barycenter_m) {
        return Err(RuntimeError::InvalidBodyProperties);
    }
    Ok(inv)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn lab() -> WorldRuntime {
        let mut r = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        r.apply_command(WorldCommand::SeedCanonicalSolarSystem, 1).unwrap();
        let earth = r.snapshot().bodies.into_iter().find(|b| b.body_id.0 == "earth").unwrap();
        let mut probe = earth;
        probe.body_id = BodyId("mass-probe".into());
        probe.body_class = BodyClass::Spacecraft;
        probe.mass_kg = 1_000.0;
        probe.source_mass_kg = 0.0;
        probe.radius_m = 2.0;
        probe.position_m.x += 20_000_000.0;
        r.apply_command(WorldCommand::SpawnBody { body: probe }, 2).unwrap();
        r
    }
    fn edit(r: &WorldRuntime, mass: f64, source: f64) -> WorldCommand {
        let b = r.snapshot().bodies.into_iter().find(|b| b.body_id.0 == "mass-probe").unwrap();
        WorldCommand::SetBodyProperties {
            body_id: b.body_id, mass_kg: mass, source_mass_kg: source, radius_m: b.radius_m,
            position_m: b.position_m, velocity_mps: b.velocity_mps,
        }
    }
    #[test]
    fn body_inspection_borrows_live_state_without_mutation_or_world_copy() {
        let mut r = lab();
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal;
        let log_len = r.active_branch().command_log.len();
        let actual = r.active_branch().world.bodies.iter().find(|body| body.body_id.0 == "mass-probe").unwrap();
        for _ in 0..100 {
            let inspected = r.inspect_body("mass-probe").unwrap();
            assert!(std::ptr::eq(inspected, actual));
            assert_eq!(inspected.mass_kg, 1_000.0);
        }
        assert!(r.inspect_body("missing").is_none());
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal, ordinal);
        assert_eq!(r.active_branch().command_log.len(), log_len);
        r.apply_command(edit(&r, 2.0e22, 2.0e22), 3).unwrap();
        assert_eq!(r.inspect_body("mass-probe").unwrap().source_mass_kg, 2.0e22);
    }
    #[test]
    fn properties_edit_preserves_identity_and_roundtrips_mass_and_source() {
        let mut r = lab();
        let before = r.snapshot(); let ordinal = r.next_command_ordinal;
        r.apply_command(edit(&r, 1.0e22, 1.0e22), 3).unwrap();
        let after = r.snapshot();
        assert_eq!(after.epoch_seconds, before.epoch_seconds);
        assert_eq!(after.bodies.len(), before.bodies.len());
        assert_eq!(r.next_command_ordinal, ordinal + 1);
        let b = after.bodies.iter().find(|b| b.body_id.0 == "mass-probe").unwrap();
        assert_eq!(b.mass_kg, 1.0e22); assert_eq!(b.source_mass_kg, 1.0e22);
        assert_eq!(b.body_class, BodyClass::Spacecraft);
        let saved = r.export_saved_session().unwrap();
        let restored = WorldRuntime::import_saved_session(&saved, r.hardware_profile.clone()).unwrap();
        assert_eq!(restored.snapshot().bodies, after.bodies);
    }
    #[test]
    fn invalid_mass_edit_leaves_world_history_and_ordinal_unchanged() {
        let mut r = lab();
        for (mass, source) in [(f64::NAN,0.0),(-1.0,0.0),(1.0,f64::INFINITY),(1.0,-1.0),(f64::MAX,f64::MAX)] {
            let before = r.snapshot(); let ordinal = r.next_command_ordinal;
            assert_eq!(r.apply_command(edit(&r,mass,source),3), Err(RuntimeError::InvalidBodyProperties));
            assert_eq!(r.snapshot(),before); assert_eq!(r.next_command_ordinal,ordinal);
        }
        let mut missing = edit(&r, 5.0, 0.0);
        if let WorldCommand::SetBodyProperties { body_id, .. } = &mut missing { *body_id = BodyId("missing".into()); }
        let before = r.snapshot(); let ordinal = r.next_command_ordinal;
        assert!(matches!(r.apply_command(missing,3),Err(RuntimeError::UnknownBody(_))));
        assert_eq!(r.snapshot(),before); assert_eq!(r.next_command_ordinal,ordinal);
    }
    #[test]
    fn running_world_rejects_property_edit_without_consuming_history() {
        let mut r = lab();
        r.apply_command(WorldCommand::ResumePlayback, 3).unwrap();
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal;
        let log_len = r.active_branch().command_log.len();
        let checkpoint_len = r.active_branch().checkpoints.len();
        let command = edit(&r, 2.0e22, 2.0e22);
        assert_eq!(r.apply_command(command, 4), Err(RuntimeError::BodyEditRequiresPause));
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal, ordinal);
        assert_eq!(r.active_branch().command_log.len(), log_len);
        assert_eq!(r.active_branch().checkpoints.len(), checkpoint_len);
        r.apply_command(WorldCommand::PausePlayback, 5).unwrap();
        r.apply_command(edit(&r, 2.0e22, 2.0e22), 6).unwrap();
    }
    #[test]
    fn passive_mass_does_not_perturb_but_source_mass_changes_earth_motion() {
        let mut baseline=lab(); let mut passive=lab(); let mut active=lab();
        passive.apply_command(edit(&passive,1.0e22,0.0),3).unwrap();
        active.apply_command(edit(&active,1.0e22,1.0e22),3).unwrap();
        for r in [&mut baseline,&mut passive,&mut active] {
            r.apply_command(WorldCommand::AdvanceEpoch {delta_seconds: 60.0},4).unwrap();
        }
        let earth = |r: &WorldRuntime| r.snapshot().bodies.into_iter().find(|b| b.body_id.0 == "earth").unwrap();
        assert_eq!(earth(&baseline),earth(&passive));
        assert_ne!(earth(&baseline).velocity_mps,earth(&active).velocity_mps);
    }
}
