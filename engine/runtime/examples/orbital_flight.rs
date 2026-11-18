//! Run: cargo run -p solarlab-runtime --example orbital_flight --release
//! Optional first argument: burn seconds (0..=300; default 30).
use solarlab_domain::{
    AttitudeMode, BodyId, BranchId, FlightControl, ScenarioId, TimelineSemantics,
};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{AdvanceState, RuntimeConfig, WorldCommand, WorldRuntime};
fn advance(runtime: &mut WorldRuntime, seconds: f64) {
    if seconds == 0.0 {
        return;
    }
    let old = runtime.query_advance().map_or(0, |a| a.generation);
    let ordinal = runtime.next_command_ordinal();
    let generation = runtime
        .begin_advance([ordinal, ordinal + 100], old, ordinal, seconds, 0)
        .expect("valid staged flight")
        .generation;
    let mut completed = 0;
    loop {
        let (operation, _) = runtime
            .continue_advance(generation, completed)
            .expect("flight integration");
        completed = operation.completed_steps;
        if operation.state == AdvanceState::Committed {
            break;
        }
    }
}
fn print(runtime: &WorldRuntime, label: &str) {
    let t = runtime
        .flight_telemetry("flight.odyssey")
        .expect("flight telemetry");
    let s = runtime.spacecraft_by_id("flight.odyssey").unwrap();
    let orbit = t.orbit.unwrap();
    println!("{label:>12} | t={:7.1} s | altitude={:8.2} km | speed={:8.2} m/s | fuel={:8.2} kg | delta-v={:7.2} m/s | peri/apo={:8.2}/{:8.2} km",t.epoch_seconds,t.altitude_m/1000.0,orbit.speed_mps,s.propellant_mass_kg,s.delivered_delta_v_mps,(orbit.periapsis_radius_m-t.reference_radius_m)/1000.0,(orbit.apoapsis_radius_m.unwrap_or(f64::NAN)-t.reference_radius_m)/1000.0);
}
fn main() {
    let burn = std::env::args()
        .nth(1)
        .map(|v| v.parse::<f64>().expect("numeric burn duration"))
        .unwrap_or(30.0);
    assert!(burn.is_finite() && (0.0..=300.0).contains(&burn));
    let mut world = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("flight-demo".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: 10.0,
            },
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            live_updates_enabled: false,
        },
        HardwareProfile::offline_reference(),
        0,
    );
    world
        .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
        .unwrap();
    world.apply_command(WorldCommand::PausePlayback, 0).unwrap();
    world
        .spawn_reference_spacecraft("earth", "flight.odyssey", 400_000.0, 0)
        .unwrap();
    print(&world, "insertion");
    world
        .apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId("flight.odyssey".into()),
                control: FlightControl {
                    throttle: 1.0,
                    attitude_mode: AttitudeMode::Prograde,
                    ..FlightControl::default()
                },
            },
            0,
        )
        .unwrap();
    advance(&mut world, burn);
    print(&world, "engine cut");
    world
        .apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId("flight.odyssey".into()),
                control: FlightControl {
                    attitude_mode: AttitudeMode::Hold,
                    ..FlightControl::default()
                },
            },
            0,
        )
        .unwrap();
    advance(&mut world, 30.0);
    let save = world.export_saved_session().unwrap();
    let mut reopened =
        WorldRuntime::import_saved_session(&save, HardwareProfile::offline_reference()).unwrap();
    advance(&mut world, 600.0);
    advance(&mut reopened, 600.0);
    assert_eq!(world.snapshot().bodies, reopened.snapshot().bodies);
    assert_eq!(world.snapshot().spacecraft, reopened.snapshot().spacecraft);
    print(&world, "coast 10min");
    println!(
        "Save/resume future matched exactly ({} bytes); full Earth–Moon gravity, source mass 0 kg.",
        save.len()
    );
    println!("Approximate circular insertion; Newtonian gravity, finite burns, ideal wheels; no atmosphere or collisions.");
}
