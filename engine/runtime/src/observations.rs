//! Read-only snapshots, telemetry, body queries and scene publication.
//! Authoritative mutations remain on the single WorldRuntime; no shadow world is created.
use super::*;

impl WorldRuntime {
    pub(super) fn refresh_active_solver_execution_report(&mut self) {
        let requested_backend = self.config.physics.solver_backend.clone();
        let active_cpu_features = self.hardware_profile.cpu_features.clone();
        let branch = self.active_branch_mut();
        branch.world.solver_execution = solver_execution_report_for_backend_with_source_count(
            &requested_backend,
            &active_cpu_features,
            branch.world.bodies.len(),
            branch.world.bodies.iter().filter(|body| body.source_mass_kg != 0.0).count(),
        );
    }

    /// Monotonic command ordinal for ordering snapshots within this session.
    /// Failed commands may leave gaps; branch switches do not reset it.
    #[must_use]
    pub fn snapshot_revision(&self) -> u64 {
        self.next_command_ordinal.saturating_sub(1)
    }

    /// Current command ordinal required by a new staged advance.
    #[must_use]
    pub fn next_command_ordinal(&self) -> u64 {
        self.next_command_ordinal
    }

    /// Read one body from the active branch without allocating a WorldSnapshot.
    /// Body inspection can be called frequently while the UI is active; cloning
    /// trail histories and all bodies just to read ten scalar fields is wasteful.
    /// The reference lives only as long as this immutable runtime borrow.
    #[must_use]
    pub fn body_by_id(&self, id: &str) -> Option<&BodyState> {
        self.active_branch()
            .world
            .bodies
            .iter()
            .find(|body| body.body_id.0 == id)
    }

    /// Return an ownership-safe world snapshot for host-visible status and
    /// checkpoint provenance capture.
    /// This method is a pure projection: it does not mutate runtime state.
    #[must_use]
    pub fn snapshot(&self) -> WorldSnapshot {
        self.snapshot_with_orbit_archives(Vec::new())
    }

    fn snapshot_with_orbit_archives(&self, orbit_archives: Vec<OrbitArchive>) -> WorldSnapshot {
        let active = self.active_branch();
        let checkpoint = active.last_checkpoint.clone();
        let mounted_manifest = mounted_manifest_from_state(
            &active.world.local_data_state,
            &active.world.mounted_package_ids,
        )
        .expect("runtime mounted package state must be internally consistent");

        WorldSnapshot {
            scenario_id: self.scenario_id.clone(),
            branch_id: self.active_branch_id.clone(),
            epoch_seconds: active.world.epoch_seconds,
            timeline_semantics: self.config.timeline_semantics.clone(),
            bodies: active.world.bodies.clone(),
            active_checkpoint: checkpoint,
            hardware_profile: self.hardware_profile.clone(),
            invariants: active.world.invariants,
            observer: active.world.observer.clone(),
            playback: active.world.playback.clone(),
            mounted_manifest,
            initial_conditions_provenance: active.world.initial_conditions_provenance.clone(),
            trail_history_by_body: active.world.trail_history_by_body.clone(),
            orbit_archives,
            solver_execution: active.world.solver_execution.clone(),
        }
    }

    /// Query the authoritative orbit archive surface for the active branch.
    ///
    /// This keeps trajectory, historical checkpoint orbit passes, and future
    /// prediction overlays in the Rust-owned history/runtime seam instead of
    /// leaving the client to infer them from one generic trail family.
    #[must_use]
    pub fn orbit_archives(&self, query: &OrbitArchiveQuery) -> Vec<OrbitArchive> {
        let active = self.active_branch();
        build_orbit_archives(
            active,
            query,
            &self.effective_physics_policy(),
            &self.hardware_profile.cpu_features,
        )
    }

    /// Project authoritative runtime state into a compact telemetry surface.
    #[must_use]
    pub fn telemetry_report(&self) -> RuntimeTelemetryReport {
        let snapshot =
            self.snapshot_with_orbit_archives(self.orbit_archives(&OrbitArchiveQuery::default()));
        let scene = extract_render_scene(&snapshot);
        let trail_history_counts = runtime_trail_history_counts(&snapshot.trail_history_by_body);
        let total_trail_samples = trail_history_counts
            .iter()
            .map(|entry| entry.sample_count)
            .sum();

        RuntimeTelemetryReport {
            scenario_id: snapshot.scenario_id,
            branch_id: snapshot.branch_id,
            epoch_seconds: snapshot.epoch_seconds,
            timeline_semantics: snapshot.timeline_semantics,
            active_checkpoint_id: snapshot
                .active_checkpoint
                .map(|checkpoint| checkpoint.checkpoint_id),
            total_bodies: snapshot.bodies.len(),
            total_tracers: scene.tracer_count as usize,
            trail_history_counts,
            total_trail_samples,
            invariants: snapshot.invariants,
            hardware_profile: snapshot.hardware_profile,
            playback: snapshot.playback,
            observer: snapshot.observer,
            mounted_manifest: snapshot
                .mounted_manifest
                .as_ref()
                .map(mounted_manifest_telemetry_from_state),
            scene_revision: scene.scene_revision,
            diagnostics: scene.diagnostics,
            solver_execution: snapshot.solver_execution,
        }
    }

    /// Build a renderer-agnostic scene contract from the current runtime state.
    /// This pure extraction path is intentionally read-only and used by FFI
    /// scene export.
    #[must_use]
    pub fn render_scene(&self) -> RenderScene {
        let snapshot = self
            .snapshot_with_orbit_archives(self.orbit_archives(&self.render_scene_orbit_query()));
        extract_render_scene(&snapshot)
    }

    fn render_scene_orbit_query(&self) -> OrbitArchiveQuery {
        let active = self.active_branch();
        let full_history = active.world.bodies.len() <= RENDER_SCENE_ORBIT_BODY_LIMIT;
        let mut query = OrbitArchiveQuery {
            body_ids: render_scene_orbit_body_ids(
                &active.world.bodies,
                active.world.observer.focus_body_id.as_ref(),
            ),
            include_trajectory: true,
            include_historical_orbits: full_history,
            include_prediction: full_history,
            ..OrbitArchiveQuery::default()
        };
        lab_prediction::configure_display_query(&mut query, &self.scenario_id.0, &active.world.bodies);
        query
    }
}
