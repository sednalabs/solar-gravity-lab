//! Run: cargo run -p solarlab-runtime --example orbital_rendezvous --release
//! Pass `match` to demonstrate velocity matching instead of stand-off approach.
use solarlab_domain::{BranchId, ScenarioId, TimelineSemantics};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{
    RendezvousGuidance, RendezvousScenario, RendezvousStatus, RuntimeConfig, WorldCommand,
    WorldRuntime,
};

fn tick(world: &mut WorldRuntime, scenario: &RendezvousScenario, guidance: RendezvousGuidance) {
    world
        .apply_rendezvous_guidance(scenario, guidance, 0)
        .expect("bounded RCS guidance");
    // A 0.5 s control cadence; the production integrator uses at most 0.05 s while firing.
    world
        .apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 0.5 }, 0)
        .expect("native physics step");
}

fn print(world: &WorldRuntime, scenario: &RendezvousScenario, guidance: RendezvousGuidance) {
    let t = world
        .rendezvous_telemetry(scenario)
        .expect("live target-relative telemetry");
    let ship = world.spacecraft_by_id(&scenario.chaser_body_id.0).unwrap();
    let status = world.rendezvous_control(scenario, guidance).unwrap().1;
    println!("t={:6.1} s | range={:8.3} m | relative speed={:6.3} m/s | closing={:7.3} m/s | fuel={:8.3} kg | {status:?}",
        t.epoch_seconds, t.range_m, t.relative_speed_mps, t.closing_speed_mps, ship.propellant_mass_kg);
}

fn main() {
    let argument = std::env::args().nth(1);
    assert!(
        argument.as_deref().is_none_or(|s| s == "match"),
        "Optional argument: match"
    );
    let guidance = if argument.is_some() {
        RendezvousGuidance::velocity_match()
    } else {
        RendezvousGuidance::default()
    };
    let duration = if guidance.approach { 650 } else { 90 };
    let scenario = RendezvousScenario::default();
    let mut world = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("rendezvous-demo".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: 0.05,
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
    world.spawn_rendezvous_scenario(&scenario, 0).unwrap();
    let station = world.spacecraft_by_id(&scenario.target_body_id.0).unwrap();
    assert!(station.engines.is_empty());
    let initial_fuel = world
        .spacecraft_by_id(&scenario.chaser_body_id.0)
        .unwrap()
        .propellant_mass_kg;
    println!("Passive station and RCS chaser, 1 km apart in approximate 400 km Earth orbits.");
    print(&world, &scenario, guidance);
    let mut resumed = None;
    let mut save_size = 0;
    let mut nearest = f64::INFINITY;
    for step in 1..=duration * 2 {
        tick(&mut world, &scenario, guidance);
        if let Some(reopened) = resumed.as_mut() {
            tick(reopened, &scenario, guidance);
        }
        nearest = nearest.min(world.rendezvous_telemetry(&scenario).unwrap().range_m);
        if step == duration {
            let saved = world.export_saved_session().unwrap();
            save_size = saved.len();
            resumed = Some(
                WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                    .unwrap(),
            );
        }
        if step % 120 == 0 || step == duration * 2 {
            print(&world, &scenario, guidance);
        }
    }
    let t = world.rendezvous_telemetry(&scenario).unwrap();
    assert!(nearest > t.keep_out_distance_m, "keep-out margin crossed");
    if guidance.approach {
        assert_eq!(
            world.rendezvous_control(&scenario, guidance).unwrap().1,
            RendezvousStatus::Holding
        );
    } else {
        assert!(t.relative_speed_mps < 0.03);
        assert!(t.range_m > 900.0);
    }
    let reopened = resumed.unwrap();
    assert_eq!(world.snapshot().bodies, reopened.snapshot().bodies);
    assert_eq!(world.snapshot().spacecraft, reopened.snapshot().spacecraft);
    let fuel_used = initial_fuel
        - world
            .spacecraft_by_id(&scenario.chaser_body_id.0)
            .unwrap()
            .propellant_mass_kg;
    println!(
        "Accepted: {:.3} m stand-off, {:.5} m/s relative speed, {:.3} kg RCS fuel used.",
        t.range_m, t.relative_speed_mps, fuel_used
    );
    println!("Save/resume future matched exactly ({save_size} bytes); both vehicles have zero gravitational source mass.");
    println!("Full native world gravity and finite RCS burns; station-fixed guidance, no contact or docking simulation.");
}
