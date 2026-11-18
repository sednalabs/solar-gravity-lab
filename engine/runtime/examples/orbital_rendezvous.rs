//! Run: cargo run -p solarlab-runtime --example orbital_rendezvous --release
//! Pass `match` to demonstrate velocity matching instead of stand-off approach.
//! A single command starts native guidance; no host-side control timer is used.
use solarlab_domain::{BranchId, ScenarioId, TimelineSemantics};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{
    RendezvousGuidanceMode, RendezvousStatus, RuntimeConfig, WorldCommand, WorldRuntime,
};

const SHIP: &str = "flight.odyssey";
fn tick(world: &mut WorldRuntime) {
    world
        .apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 1.0 }, 0)
        .expect("native guided integration");
}
fn print(world: &WorldRuntime) {
    let t = world
        .rendezvous_flight_telemetry(SHIP)
        .expect("native rendezvous telemetry");
    println!("t={:6.1} s | range={:8.3} m | relative speed={:7.4} m/s | closing={:7.3} m/s | fuel={:8.3} kg | {:?}",
        t.epoch_seconds, t.range_m, t.relative_speed_mps, t.closing_speed_mps, t.remaining_propellant_kg, t.status);
}
fn main() {
    let argument = std::env::args().nth(1);
    assert!(
        argument.as_deref().is_none_or(|s| s == "match"),
        "Optional argument: match"
    );
    let mode = if argument.is_some() {
        RendezvousGuidanceMode::MatchVelocity
    } else {
        RendezvousGuidanceMode::Approach
    };
    let duration = if mode == RendezvousGuidanceMode::Approach {
        650
    } else {
        90
    };
    let mut world = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("rendezvous-demo".into()),
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
    world
        .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
        .unwrap();
    world.apply_command(WorldCommand::PausePlayback, 0).unwrap();
    world.spawn_rendezvous_flight(SHIP, 0).unwrap();
    world.set_rendezvous_guidance(SHIP, mode, 0).unwrap();
    println!("Native persistent RCS guidance: passive Relay station and Asteria chaser, 1 km apart in approximate 400 km Earth orbits.");
    print(&world);
    let mut resumed = None;
    let mut save_size = 0;
    for second in 1..=duration {
        tick(&mut world);
        if let Some(reopened) = resumed.as_mut() {
            tick(reopened);
        }
        if second == duration / 2 {
            let saved = world.export_saved_session().unwrap();
            save_size = saved.len();
            resumed = Some(
                WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                    .unwrap(),
            );
        }
        if second % 60 == 0 || second == duration {
            print(&world);
        }
    }
    let t = world.rendezvous_flight_telemetry(SHIP).unwrap();
    assert!(!t.keep_out_entered && t.closest_observed_range_m > t.keep_out_distance_m);
    if mode == RendezvousGuidanceMode::Approach {
        assert_eq!(t.status, RendezvousStatus::Holding);
        assert!(t.stand_off_achieved && t.stand_off_error_m < 1.0);
    } else {
        assert!(t.relative_speed_mps < 0.03 && t.range_m > 900.0);
    }
    let reopened = resumed.unwrap();
    assert_eq!(world.snapshot().bodies, reopened.snapshot().bodies);
    assert_eq!(world.snapshot().spacecraft, reopened.snapshot().spacecraft);
    println!(
        "Accepted: {:.3} m separation, {:.5} m/s relative speed, {:.3} kg RCS fuel used.",
        t.range_m, t.relative_speed_mps, t.propellant_used_kg
    );
    println!("Active guidance save/resume future matched exactly ({save_size} bytes); mode, target and progress persisted.");
    println!("Full native world gravity and finite RCS burns; station-fixed guidance, no collision, contact or docking simulation.");
}
