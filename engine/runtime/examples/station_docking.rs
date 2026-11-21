//! cargo run -p solarlab-runtime --example station_docking --release
//! One Dock command drives the production controller, solver and saved constraint.
use solarlab_domain::{BodyId, BranchId, FlightControl, ScenarioId, TimelineSemantics, Vector3d};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{DockingGuidanceMode, RuntimeConfig, WorldCommand, WorldRuntime};
const SHIP: &str = "flight.odyssey";
fn advance(r: &mut WorldRuntime, seconds: f64) {
    let previous = r.query_advance().map_or(0, |a| a.generation);
    let ordinal = r.next_command_ordinal();
    let generation = r
        .begin_advance([ordinal, ordinal + 100], previous, ordinal, seconds, 0)
        .unwrap()
        .generation;
    let mut completed = 0;
    loop {
        let (op, _) = r.continue_advance(generation, completed).unwrap();
        completed = op.completed_steps;
        if op.state == solarlab_runtime::AdvanceState::Committed {
            break;
        }
    }
}
fn tick(r: &mut WorldRuntime) {
    advance(r, 1.0);
}
fn print(r: &WorldRuntime) {
    let t = r.docking_flight_telemetry(SHIP).unwrap();
    let o = t.observation.unwrap();
    println!("t={:6.1}s | port gap={:8.4}m | lateral={:.5}m | closing={:.5}m/s | angle={:.4}deg | fuel={:.4}kg | {:?}",
        t.epoch_seconds, o.position_port_m.z, o.position_port_m.x.hypot(o.position_port_m.y),
        o.closing_speed_mps, o.alignment_error_rad.to_degrees(), t.remaining_propellant_kg, t.status);
}
fn main() {
    let mut r = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("docking-demo".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: 30.0,
            },
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            live_updates_enabled: false,
        },
        HardwareProfile::offline_reference(),
        0,
    );
    r.apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
        .unwrap();
    r.apply_command(WorldCommand::PausePlayback, 0).unwrap();
    r.spawn_docking_flight(SHIP, 0).unwrap();
    r.set_docking_guidance(SHIP, DockingGuidanceMode::Approach, 0)
        .unwrap();
    println!("Asteria and Relay: 30m centre separation, 400km Earth orbit, native RCS approach and Rapier f64 contact.");
    print(&r);
    for second in 1..=200 {
        tick(&mut r);
        if second % 10 == 0 {
            print(&r);
        }
        if r.docking_flight_telemetry(SHIP).unwrap().attached {
            break;
        }
    }
    assert!(
        r.docking_flight_telemetry(SHIP).unwrap().attached,
        "Approach failed to capture"
    );
    print(&r);
    let saved = r.export_saved_session().unwrap();
    let mut restored =
        WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference()).unwrap();
    for _ in 0..20 {
        tick(&mut r);
        tick(&mut restored);
    }
    assert_eq!(r.snapshot().bodies, restored.snapshot().bodies);
    assert_eq!(r.snapshot().spacecraft, restored.snapshot().spacecraft);
    println!(
        "Attached save/resume matched future bodies and components exactly ({} bytes).",
        saved.len()
    );
    let before = r.snapshot();
    r.undock_spacecraft(SHIP, 0).unwrap();
    assert_eq!(
        before.bodies,
        r.snapshot().bodies,
        "Release may not change velocity or position"
    );
    let initial_range = r.docking_flight_telemetry(SHIP).unwrap().centre_range_m;
    let initial_fuel = r.spacecraft_by_id(SHIP).unwrap().propellant_mass_kg;
    r.apply_command(
        WorldCommand::SetFlightControl {
            body_id: BodyId(SHIP.into()),
            control: FlightControl {
                translation_body: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: -0.2,
                },
                ..FlightControl::default()
            },
        },
        0,
    )
    .unwrap();
    for _ in 0..10 {
        tick(&mut r);
    }
    r.apply_command(
        WorldCommand::SetFlightControl {
            body_id: BodyId(SHIP.into()),
            control: FlightControl::default(),
        },
        0,
    )
    .unwrap();
    print(&r);
    assert!(r.docking_flight_telemetry(SHIP).unwrap().centre_range_m > initial_range + 0.2);
    assert!(r.spacecraft_by_id(SHIP).unwrap().propellant_mass_kg < initial_fuel);
    assert!(!r.docking_flight_telemetry(SHIP).unwrap().attached);
    println!("Accepted: native approach, finite-fuel capture, persisted fixed attachment, impulse-free release, powered RCS retreat.");
}
