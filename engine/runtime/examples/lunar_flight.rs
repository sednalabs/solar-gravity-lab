//! Run: cargo run --release -p solarlab-runtime --example lunar_flight
//! Optional arguments: coast max step seconds (10), finite burn seconds (300).
//! All elapsed time, cutoff and encounter observations belong to WorldRuntime.
use solarlab_domain::{BodyId, BranchId, ScenarioId, TimelineSemantics};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{
    AdvanceState, LunarMissionOutcome, RuntimeConfig, WorldCommand, WorldRuntime,
    LUNAR_FLIGHT_BODY_ID, LUNAR_INSERTION_PHASE_RADIANS, LUNAR_MISSION_DEADLINE_SECONDS,
};

fn advance(runtime: &mut WorldRuntime, seconds: f64) {
    let old = runtime.query_advance().map_or(0, |a| a.generation);
    let ordinal = runtime.next_command_ordinal();
    let generation = runtime
        .begin_advance([ordinal, ordinal + 100], old, ordinal, seconds, 0)
        .expect("valid staged advance")
        .generation;
    let mut completed = 0;
    loop {
        let (operation, _) = runtime.continue_advance(generation, completed).unwrap();
        completed = operation.completed_steps;
        if operation.state == AdvanceState::Committed {
            break;
        }
    }
}

fn main() {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    let step: f64 = arguments
        .first()
        .map_or(10.0, |v| v.parse().expect("numeric coast step"));
    let burn: f64 = arguments
        .get(1)
        .map_or(300.0, |v| v.parse().expect("numeric burn duration"));
    assert!(step.is_finite() && step > 0.0 && burn.is_finite() && (0.0..=390.0).contains(&burn));
    let mut runtime = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("lunar-flight".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: step,
            },
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            live_updates_enabled: false,
        },
        HardwareProfile::offline_reference(),
        0,
    );
    runtime
        .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
        .unwrap();
    runtime
        .apply_command(WorldCommand::PausePlayback, 0)
        .unwrap();
    runtime
        .spawn_lunar_spacecraft(LUNAR_FLIGHT_BODY_ID, 0)
        .unwrap();
    println!("Finite-burn lunar flight: canonical Earth–Moon seed, launch phase {LUNAR_INSERTION_PHASE_RADIANS} rad, coast cap {step}s.");
    if burn > 0.0 {
        runtime
            .apply_command(
                WorldCommand::StartGuidedBurn {
                    body_id: BodyId(LUNAR_FLIGHT_BODY_ID.into()),
                    full_throttle_seconds: burn,
                },
                0,
            )
            .unwrap();
    }
    // Deliberately overshoot the requested burn. The engine owns its cutoff.
    advance(&mut runtime, burn + 25.0);
    let departure = runtime
        .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
        .unwrap();
    assert!(!departure.guided_burn_active);
    assert!((departure.main_engine_full_throttle_seconds - burn).abs() < 1e-7);
    println!(
        "Native cutoff: actual main firing {:.9}s, propellant {:.3}kg, Earth altitude {:.3}km.",
        departure.main_engine_full_throttle_seconds,
        departure.remaining_propellant_kg,
        departure.earth_altitude_m / 1000.0
    );
    let started = std::time::Instant::now();
    let scene = runtime.render_scene();
    let initial_build = started.elapsed();
    let started = std::time::Instant::now();
    let cached_scene = runtime.render_scene();
    let cached_build = started.elapsed();
    assert_eq!(scene.bodies, cached_scene.bodies);
    assert_eq!(scene.trails, cached_scene.trails);
    if let Some(forecast) = runtime.lunar_coast_forecast_diagnostics() {
        println!("Engine-off display forecast: {:.3}days, {} adaptive samples, {} gravity steps; local scene build {:?}, cache hit {:?} (not device performance).",
            (forecast.end_epoch_seconds - forecast.source_epoch_seconds) / 86_400.0,
            forecast.sample_count, forecast.integration_steps, initial_build, cached_build);
    }
    let saved = runtime.export_saved_session().unwrap();
    let mut resumed =
        WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference()).unwrap();
    advance(&mut runtime, 600.0);
    advance(&mut resumed, 600.0);
    assert_eq!(runtime.snapshot().bodies, resumed.snapshot().bodies);
    assert_eq!(runtime.snapshot().spacecraft, resumed.snapshot().spacecraft);
    println!(
        "Saved/reloaded future is bit-identical ({} bytes).",
        saved.len()
    );
    runtime
        .apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 10_000.0,
            },
            0,
        )
        .unwrap();
    let mut next_report = 6.0 * 3600.0;
    loop {
        let t = runtime
            .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
            .unwrap();
        if t.elapsed_seconds >= next_report {
            println!(
                "t {:.4} days | {:?} | Moon altitude {:10.3}km | range rate {:+9.3}m/s",
                t.elapsed_seconds / 86400.0,
                t.phase,
                t.moon_altitude_m / 1000.0,
                t.moon_range_rate_mps
            );
            next_report += 6.0 * 3600.0;
        }
        if t.outcome != LunarMissionOutcome::InProgress
            || t.elapsed_seconds >= LUNAR_MISSION_DEADLINE_SECONDS
        {
            println!("Outcome {:?}: closest observed lunar altitude {:.6}km at {:.9} days; fuel {:.3}kg.",
                t.outcome, t.closest_observed_moon_altitude_m / 1000.0,
                (t.closest_observed_epoch_seconds - t.launch_epoch_seconds) / 86400.0, t.remaining_propellant_kg);
            break;
        }
        // UI sampling may be sparse; closest approach is recorded on every native
        // integration substep, independently of these report/advance intervals.
        let dt = if t.moon_altitude_m < 5_000_000.0 {
            10.0
        } else if t.moon_altitude_m < 20_000_000.0 {
            60.0
        } else {
            600.0
        };
        advance(&mut runtime, dt);
    }
    println!("Newtonian Sun/Earth/Moon gravity with integrated finite thrust and bounded wheel torque. No atmosphere, contact response, relativity or future ephemeris forcing.");
}
