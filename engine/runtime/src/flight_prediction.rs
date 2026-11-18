//! Bounded, cached engine-off transfer forecasts for the lunar display.
//! This cache is derived presentation data, never persisted world/mission truth.
use crate::{
    BodyId, OrbitArchive, OrbitArchiveFamily, OrbitArchiveQuery, OrbitSample, WorldCommand,
    WorldRuntime,
};
use solarlab_domain::spacecraft::{finite, magnitude, sub};
use solarlab_physics::{advance_authoritative_with_features, checked_substep_count};
use std::sync::Mutex;
use std::time::{Duration, Instant};

const MAX_HORIZON_SECONDS: f64 = 3.0 * 86_400.0;
const MAX_SAMPLES: usize = 512;
const MAX_INTEGRATION_STEPS: usize = 32_768;
const MAX_REFRESH_INTERVAL_SECONDS: f64 = 900.0;
const MIN_BUILD_INTERVAL: Duration = Duration::from_millis(500);

#[derive(Debug, Default)]
pub(super) struct FlightPredictionCache(Mutex<Option<Forecast>>);
impl Clone for FlightPredictionCache {
    fn clone(&self) -> Self {
        Self::default()
    }
}
impl FlightPredictionCache {
    pub(super) fn invalidate_for(&self, command: &WorldCommand) {
        if matches!(
            command,
            WorldCommand::SeedCanonicalSolarSystem
                | WorldCommand::SpawnBody { .. }
                | WorldCommand::SpawnSpacecraft { .. }
                | WorldCommand::RemoveBody { .. }
                | WorldCommand::SetBodyKinematics { .. }
                | WorldCommand::SetBodyProperties { .. }
                | WorldCommand::SetFlightControl { .. }
                | WorldCommand::StartGuidedBurn { .. }
                | WorldCommand::CreateBranchFromCheckpoint { .. }
        ) {
            if let Ok(mut cache) = self.0.lock() {
                *cache = None;
            }
        }
    }
}

/// Work and provenance of the cached display-only coast forecast.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LunarCoastForecastDiagnostics {
    pub source_epoch_seconds: f64,
    pub end_epoch_seconds: f64,
    pub age_simulation_seconds: f64,
    pub sample_count: usize,
    pub integration_steps: usize,
}
impl WorldRuntime {
    /// Does not build a forecast or modify physical state.
    #[must_use]
    pub fn lunar_coast_forecast_diagnostics(&self) -> Option<LunarCoastForecastDiagnostics> {
        let cache = self.flight_prediction_cache.0.lock().ok()?;
        let forecast = cache.as_ref()?;
        Some(LunarCoastForecastDiagnostics {
            source_epoch_seconds: forecast.source_epoch_seconds,
            end_epoch_seconds: forecast.last_epoch_seconds,
            age_simulation_seconds: self.active_branch().world.epoch_seconds
                - forecast.source_epoch_seconds,
            sample_count: forecast.archives.first()?.samples.len(),
            integration_steps: forecast.integration_steps,
        })
    }
}

#[derive(Debug)]
struct Forecast {
    branch_id: crate::BranchId,
    ship_id: BodyId,
    source_epoch_seconds: f64,
    last_epoch_seconds: f64,
    refresh_interval_seconds: f64,
    built_at: Instant,
    integration_steps: usize,
    archives: Vec<OrbitArchive>,
}

/// Ordinary laboratories and local orbits retain their existing display path.
/// Only an engine-off mission with an Earth-relative apogee reaching halfway to
/// the Moon (or an unbound conic) opts into the longer transfer display.
pub(super) fn display_archives(
    runtime: &WorldRuntime,
    query: &OrbitArchiveQuery,
) -> Option<Vec<OrbitArchive>> {
    if !query.include_prediction || runtime.scenario_id.0 != solarlab_data::EARTH_MOON_LAB_ID {
        return None;
    }
    let world = &runtime.active_branch().world;
    if world.bodies.len() > crate::RENDER_SCENE_ORBIT_BODY_LIMIT {
        return None;
    }
    let ship = world.spacecraft.iter().find(|s| {
        s.mission
            .as_ref()
            .is_some_and(|m| m.mission_id == crate::LUNAR_FLIGHT_MODEL)
    })?;
    if ship.has_powered_input()
        || ship.burn_program.is_some()
        || ship.main_engine_full_throttle_seconds == 0.0
    {
        return None;
    }
    let earth = runtime.body_by_id("earth")?;
    let moon = runtime.body_by_id("moon")?;
    let orbit = runtime.flight_telemetry(&ship.body_id.0)?.orbit?;
    if orbit
        .apoapsis_radius_m
        .is_some_and(|apo| apo < 0.5 * magnitude(sub(moon.position_m, earth.position_m)))
    {
        return None;
    }
    let mut history_query = query.clone();
    history_query.include_prediction = false;
    let mut archives = runtime.orbit_archives(&history_query);
    let Ok(mut cache) = runtime.flight_prediction_cache.0.lock() else {
        return Some(archives);
    };
    let reusable = cache.as_ref().is_some_and(|forecast| {
        forecast.branch_id == runtime.active_branch_id
            && forecast.ship_id == ship.body_id
            && world.epoch_seconds >= forecast.source_epoch_seconds
            && world.epoch_seconds < forecast.last_epoch_seconds
            && (world.epoch_seconds - forecast.source_epoch_seconds
                < forecast.refresh_interval_seconds
                || forecast.built_at.elapsed() < MIN_BUILD_INTERVAL)
    });
    if !reusable {
        *cache = build_forecast(runtime, &ship.body_id);
    }
    // A budget-limited/invalid forecast must not fall back to the older
    // unbounded query after this mission has opted into the bounded path.
    let Some(forecast) = cache.as_ref() else {
        return Some(archives);
    };
    for prediction in &forecast.archives {
        if !query.body_ids.is_empty() && !query.body_ids.contains(&prediction.source_body_id) {
            continue;
        }
        let Some(body) = world
            .bodies
            .iter()
            .find(|b| b.body_id == prediction.source_body_id)
        else {
            continue;
        };
        let mut archive = prediction.clone();
        archive
            .samples
            .retain(|sample| sample.epoch_seconds > world.epoch_seconds);
        if archive.samples.is_empty() {
            continue;
        }
        // Every body shares the same live anchor and cached future epochs, which
        // preserves exact epoch pairing for Earth-relative trail conversion.
        archive.samples.insert(
            0,
            OrbitSample {
                epoch_seconds: world.epoch_seconds,
                position_m: body.position_m,
            },
        );
        archive.max_samples = u32::try_from(archive.samples.len()).ok()?;
        archives.push(archive);
    }
    Some(archives)
}

fn build_forecast(runtime: &WorldRuntime, ship_id: &BodyId) -> Option<Forecast> {
    let world = &runtime.active_branch().world;
    let ship_index = world.bodies.iter().position(|b| b.body_id == *ship_id)?;
    let mut projected = world.bodies.clone();
    let mut solver = crate::world_bodies_to_solver_state(&projected);
    let mut configured = runtime.config.physics.clone();
    configured.max_substep_seconds = configured.max_substep_seconds.min(60.0);
    let start = world.epoch_seconds;
    let mut epoch = start;
    let mut integration_steps = 0;
    let mut archives: Vec<_> = projected
        .iter()
        .map(|b| OrbitArchive {
            archive_id: format!("prediction:{}", b.body_id.0),
            source_body_id: b.body_id.clone(),
            family: OrbitArchiveFamily::Prediction,
            max_samples: MAX_SAMPLES as u32,
            samples: Vec::with_capacity(MAX_SAMPLES),
        })
        .collect();
    let refresh_interval_seconds =
        (crate::lab_prediction::shortest_added_body_local_period(&projected)? / 16.0)
            .clamp(30.0, MAX_REFRESH_INTERVAL_SECONDS);
    while archives[0].samples.len() < MAX_SAMPLES && epoch - start < MAX_HORIZON_SECONDS {
        // Dense samples near either primary; longer intervals only where the
        // actual predicted local motion supports them. No uniform-hour chords.
        let period = crate::lab_prediction::shortest_added_body_local_period(&projected)?;
        let interval = (period / 64.0)
            .min(3600.0)
            .min(MAX_HORIZON_SECONDS - (epoch - start));
        let next = crate::orbit_archives::next_epoch(epoch, interval)?;
        // Display-only local refinement avoids retaining departure's periapsis
        // step throughout distant coast. Actual flight keeps its stronger policy.
        let policy = crate::lab_step::legacy_for_world(&configured, &projected);
        let steps = checked_substep_count(&policy, next - epoch)?;
        if steps > MAX_INTEGRATION_STEPS - integration_steps {
            break;
        }
        let _ = advance_authoritative_with_features(
            &policy,
            &mut solver,
            next - epoch,
            &runtime.hardware_profile.cpu_features,
        );
        if solver
            .iter()
            .any(|b| !finite(b.position_m) || !finite(b.velocity_mps))
        {
            break;
        }
        crate::apply_solver_state_to_world_bodies(&mut projected, &solver);
        epoch = next;
        integration_steps += steps;
        // Do not draw a line continuing through a planet. This is a display
        // termination only; authoritative mission outcomes use actual advances.
        let ship = &projected[ship_index];
        if projected.iter().any(|b| {
            b.body_id != *ship_id
                && b.source_mass_kg > 0.0
                && magnitude(sub(ship.position_m, b.position_m)) <= ship.radius_m + b.radius_m
        }) {
            break;
        }
        for (body, archive) in projected.iter().zip(&mut archives) {
            archive.samples.push(OrbitSample {
                epoch_seconds: epoch,
                position_m: body.position_m,
            });
        }
    }
    let last_epoch_seconds = archives.first()?.samples.last()?.epoch_seconds;
    Some(Forecast {
        branch_id: runtime.active_branch_id.clone(),
        ship_id: ship_id.clone(),
        source_epoch_seconds: start,
        last_epoch_seconds,
        refresh_interval_seconds,
        built_at: Instant::now(),
        integration_steps,
        archives,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AdvanceState, LUNAR_FLIGHT_BODY_ID};
    use solarlab_domain::{AttitudeMode, FlightControl};
    fn advance(runtime: &mut WorldRuntime, seconds: f64) {
        let old = runtime.query_advance().map_or(0, |a| a.generation);
        let ordinal = runtime.next_command_ordinal();
        let generation = runtime
            .begin_advance([ordinal, ordinal + 8], old, ordinal, seconds, 0)
            .unwrap()
            .generation;
        let mut done = 0;
        loop {
            let (op, _) = runtime.continue_advance(generation, done).unwrap();
            done = op.completed_steps;
            if op.state == AdvanceState::Committed {
                break;
            }
        }
    }
    fn mission() -> WorldRuntime {
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        runtime.config.physics.max_substep_seconds = 60.0;
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        runtime
            .apply_command(WorldCommand::PausePlayback, 0)
            .unwrap();
        runtime
            .spawn_lunar_spacecraft(LUNAR_FLIGHT_BODY_ID, 0)
            .unwrap();
        runtime
            .start_lunar_departure(LUNAR_FLIGHT_BODY_ID, 0)
            .unwrap();
        advance(&mut runtime, 325.0);
        runtime
    }

    #[test]
    fn transfer_forecast_reaches_lunar_encounter_without_mutating_truth() {
        let runtime = mission();
        let before = runtime.export_saved_session().unwrap();
        let query = runtime.render_scene_orbit_query();
        let archives = display_archives(&runtime, &query).unwrap();
        let diagnostics = runtime.lunar_coast_forecast_diagnostics().unwrap();
        assert!(diagnostics.integration_steps <= MAX_INTEGRATION_STEPS);
        assert!(diagnostics.sample_count <= MAX_SAMPLES);
        assert!(
            (diagnostics.end_epoch_seconds
                - diagnostics.source_epoch_seconds
                - MAX_HORIZON_SECONDS)
                .abs()
                < 1e-6
        );
        let ship = archives
            .iter()
            .find(|a| {
                a.source_body_id.0 == LUNAR_FLIGHT_BODY_ID
                    && a.family == OrbitArchiveFamily::Prediction
            })
            .unwrap();
        let moon = archives
            .iter()
            .find(|a| a.source_body_id.0 == "moon" && a.family == OrbitArchiveFamily::Prediction)
            .unwrap();
        let radius = runtime.body_by_id("moon").unwrap().radius_m;
        let mut closest = (f64::INFINITY, 0.0);
        for (s, m) in ship.samples.iter().zip(&moon.samples) {
            assert_eq!(s.epoch_seconds, m.epoch_seconds);
            let altitude = magnitude(sub(s.position_m, m.position_m)) - radius;
            if altitude < closest.0 {
                closest = (altitude, s.epoch_seconds);
            }
        }
        eprintln!(
            "display forecast: {diagnostics:?}, closest altitude {}km at {}days",
            closest.0 / 1000.0,
            closest.1 / 86400.0
        );
        assert!((350_000.0..650_000.0).contains(&closest.0));
        assert!((2.55 * 86_400.0..2.67 * 86_400.0).contains(&closest.1));
        assert!(ship.samples[1].epoch_seconds - ship.samples[0].epoch_seconds < 100.0);
        assert_eq!(before, runtime.export_saved_session().unwrap());
    }

    #[test]
    fn cache_survives_view_changes_and_bounds_warp_refresh_but_controls_invalidate() {
        let mut runtime = mission();
        let _ = runtime.render_scene();
        let built_at = runtime
            .flight_prediction_cache
            .0
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .built_at;
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("moon".into())),
                },
                0,
            )
            .unwrap();
        let _ = runtime.render_scene();
        assert_eq!(
            runtime
                .flight_prediction_cache
                .0
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .built_at,
            built_at
        );
        advance(&mut runtime, 60.0);
        let archives = display_archives(&runtime, &runtime.render_scene_orbit_query()).unwrap();
        assert_eq!(
            runtime
                .flight_prediction_cache
                .0
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .built_at,
            built_at
        );
        for archive in archives
            .iter()
            .filter(|a| a.family == OrbitArchiveFamily::Prediction)
        {
            assert_eq!(
                archive.samples[0].epoch_seconds,
                runtime.snapshot().epoch_seconds
            );
            assert!(archive
                .samples
                .iter()
                .all(|s| s.epoch_seconds >= runtime.snapshot().epoch_seconds));
        }
        advance(&mut runtime, 6000.0);
        runtime
            .flight_prediction_cache
            .0
            .lock()
            .unwrap()
            .as_mut()
            .unwrap()
            .built_at = Instant::now();
        let old_source = runtime
            .lunar_coast_forecast_diagnostics()
            .unwrap()
            .source_epoch_seconds;
        let _ = runtime.render_scene();
        assert_eq!(
            runtime
                .lunar_coast_forecast_diagnostics()
                .unwrap()
                .source_epoch_seconds,
            old_source
        );
        runtime
            .flight_prediction_cache
            .0
            .lock()
            .unwrap()
            .as_mut()
            .unwrap()
            .built_at = Instant::now() - Duration::from_secs(1);
        let _ = runtime.render_scene();
        assert_eq!(
            runtime
                .lunar_coast_forecast_diagnostics()
                .unwrap()
                .source_epoch_seconds,
            runtime.snapshot().epoch_seconds
        );
        runtime
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId(LUNAR_FLIGHT_BODY_ID.into()),
                    control: FlightControl {
                        throttle: 0.25,
                        attitude_mode: AttitudeMode::Prograde,
                        ..FlightControl::default()
                    },
                },
                0,
            )
            .unwrap();
        assert!(runtime.lunar_coast_forecast_diagnostics().is_none());
        assert!(display_archives(&runtime, &runtime.render_scene_orbit_query()).is_none());
    }

    #[test]
    fn local_orbit_policy_stays_unchanged_and_fine_forecast_work_is_capped() {
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        runtime
            .apply_command(WorldCommand::PausePlayback, 0)
            .unwrap();
        runtime
            .spawn_lunar_spacecraft(LUNAR_FLIGHT_BODY_ID, 0)
            .unwrap();
        let query = runtime.render_scene_orbit_query();
        assert!(display_archives(&runtime, &query).is_none());
        assert_eq!(query.prediction_sample_count, 96);
        let mut runtime = mission();
        runtime.config.physics.max_substep_seconds = 0.01;
        let _ = runtime.render_scene();
        let diagnostics = runtime.lunar_coast_forecast_diagnostics().unwrap();
        assert!(diagnostics.integration_steps <= MAX_INTEGRATION_STEPS);
        assert!(diagnostics.end_epoch_seconds - diagnostics.source_epoch_seconds <= 327.68);
        assert!(runtime.clone().lunar_coast_forecast_diagnostics().is_none());
        let mut tiny_step = runtime.clone();
        tiny_step.config.physics.max_substep_seconds = 1e-5;
        let scene = tiny_step.render_scene();
        assert!(tiny_step.lunar_coast_forecast_diagnostics().is_none());
        assert!(scene
            .trails
            .iter()
            .all(|t| t.family != solarlab_scene::SceneTrailFamily::Prediction));
    }
}
