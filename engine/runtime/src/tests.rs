//! Existing authoritative runtime regression suite, kept source-identical during modularisation.
    use std::collections::{BTreeMap, BTreeSet, HashSet};

    use solarlab_data::{
        CompatibilityTarget, Digest, PackageCompatibility, PackageKind, PackageLocator, SemVer,
        StoredPackage, UpdateManifest,
    };
    use solarlab_domain::{
        BodyClass, BodyId, BranchId, CelestialMaterialFamily, CheckpointId, ObserverMode,
        ScenarioId, TimelineSemantics, Vector3d,
    };
    use solarlab_hardware::HardwareProfile;
    use solarlab_history::{HistoryEvent, OrbitArchiveFamily, OrbitArchiveQuery};
    use solarlab_physics::{
        CollisionModel, IntegratorKind, PhysicsInvariants, PhysicsPolicy, SolverBackend,
    };
    use solarlab_scene::{
        SceneBodyKind, SceneDetailBand, SceneItemFamily, SceneTrailFamily,
    };

    use super::{
        compute_world_invariants, extract_render_scene, record_trail_samples_from_bodies,
        vec_magnitude, ApplyUpdateManifestCommand, BodyState, MountPackageCommand, RuntimeConfig,
        RuntimeError, RuntimeEvent, RuntimeTrailHistoryCount, WorldCommand, WorldRuntime,
        WorldSnapshot,
    };

    #[test]
    fn applies_body_commands_and_publishes_snapshot() {
        let mut runtime = new_runtime();

        let body = BodyState {
            body_id: BodyId("earth".into()),
            body_class: BodyClass::Planet,
            mass_kg: 5.972e24,
            source_mass_kg: 5.972e24,
            radius_m: 6_371_000.0,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        };

        let events = runtime
            .apply_command(WorldCommand::SpawnBody { body: body.clone() }, 10)
            .expect("spawn command should succeed");

        assert!(matches!(
            events[0],
            RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(_))
        ));
        assert!(matches!(events[1], RuntimeEvent::SnapshotPublished(_)));
        assert_eq!(runtime.snapshot().bodies, vec![body.clone()]);
        // Inspection must expose authoritative state without reconstructing
        // a full snapshot; it also must not accidentally find stale branches.
        assert_eq!(runtime.body_by_id("earth"), Some(&body));
        assert!(runtime.body_by_id("missing").is_none());

        runtime
            .apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: BodyId("earth".into()),
                    position_m: Vector3d {
                        x: 10.0,
                        y: 0.0,
                        z: 0.0,
                    },
                    velocity_mps: Vector3d {
                        x: 0.0,
                        y: 30_000.0,
                        z: 0.0,
                    },
                },
                11,
            )
            .expect("kinematic update should succeed");

        assert_eq!(runtime.snapshot().bodies[0].position_m.x, 10.0);
        assert_eq!(runtime.body_by_id("earth").map(|body| body.position_m.x), Some(10.0));

        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                12,
            )
            .expect("observer mode update should succeed");

        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("earth".into())),
                },
                13,
            )
            .expect("observer focus should succeed");

        runtime
            .apply_command(
                WorldCommand::RemoveBody {
                    body_id: BodyId("earth".into()),
                },
                14,
            )
            .expect("remove body should succeed");

        assert!(runtime.snapshot().bodies.is_empty());
        assert!(runtime.body_by_id("earth").is_none());
        assert_eq!(runtime.snapshot().observer.focus_body_id, None);
    }

    #[test]
    fn seed_canonical_solar_system_populates_authoritative_startup_world() {
        let mut runtime = new_runtime();
        assert!(runtime.snapshot().bodies.is_empty());

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("seed command should succeed");

        let snapshot = runtime.snapshot();
        assert_eq!(snapshot.bodies.len(), 365);
        assert!(snapshot.bodies.iter().any(|body| body.body_id.0 == "sun"));
        assert!(snapshot.bodies.iter().any(|body| body.body_id.0 == "moon"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "halley"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "belt-239"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "oort-95"));
        assert_eq!(snapshot.trail_history_by_body.len(), 365);
    }

    #[test]
    fn seed_scenario_pack_populates_focus_and_playback_defaults() {
        let mut runtime = new_runtime_with_scenario("showcase.jupiter-system");

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("scenario pack seed command should succeed");

        let snapshot = runtime.snapshot();
        assert_eq!(
            snapshot.scenario_id,
            ScenarioId("showcase.jupiter-system".into())
        );
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "jupiter"));
        assert!(snapshot.bodies.iter().any(|body| body.body_id.0 == "io"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "callisto"));
        assert_eq!(
            snapshot.observer.focus_body_id,
            Some(BodyId("jupiter".into()))
        );
        assert_eq!(snapshot.observer.mode, ObserverMode::FollowSelected);
        assert!(!snapshot.playback.paused);
        assert_eq!(snapshot.playback.sim_seconds_per_real_second, 14_400.0);
    }

    #[test]
    fn seed_close_scenario_can_start_paused_for_visual_inspection() {
        let mut runtime = new_runtime_with_scenario("showcase.earth-moon");

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("scenario pack seed command should succeed");

        let snapshot = runtime.snapshot();
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "lunar-marker-00"));
        assert_eq!(snapshot.observer.focus_body_id, Some(BodyId("moon".into())));
        assert_eq!(snapshot.observer.mode, ObserverMode::FollowHost);
        assert!(snapshot.playback.paused);
    }

    #[test]
    fn seed_unknown_scenario_pack_is_rejected() {
        let mut runtime = new_runtime_with_scenario("missing-pack");

        let err = runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect_err("unknown scenario pack should not silently seed default");

        assert_eq!(
            err,
            RuntimeError::UnknownScenario(ScenarioId("missing-pack".into()))
        );
        assert!(runtime.snapshot().bodies.is_empty());
    }

    #[test]
    fn seed_canonical_solar_system_is_idempotent_for_non_empty_world() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("first seed command should succeed");
        let first_count = runtime.snapshot().bodies.len();

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 2)
            .expect("second seed command should succeed");
        let second_count = runtime.snapshot().bodies.len();

        assert_eq!(first_count, 365);
        assert_eq!(second_count, 365);
    }

    #[test]
    fn render_scene_limits_large_catalog_orbit_archives_to_scene_budget() {
        let mut runtime = new_runtime();
        let moon = BodyId("moon".into());

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("seed command should succeed");
        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                2,
            )
            .expect("setting observer mode should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon.clone()),
                },
                3,
            )
            .expect("focusing moon should succeed");

        let physics_state_before_render = runtime.snapshot().bodies;
        let scene = runtime.render_scene();
        let trail_body_ids = scene
            .trails
            .iter()
            .map(|trail| trail.source_body_id.clone())
            .collect::<HashSet<_>>();

        assert!(trail_body_ids.len() <= super::RENDER_SCENE_ORBIT_BODY_LIMIT);
        assert!(trail_body_ids.contains(&moon));
        assert_eq!(
            scene
                .bodies
                .iter()
                .find(|body| body.body_id.0 == "halley")
                .map(|body| body.kind),
            Some(SceneBodyKind::Comet),
        );
        assert_eq!(
            scene
                .bodies
                .iter()
                .find(|body| body.body_id.0 == "vesta")
                .map(|body| body.kind),
            Some(SceneBodyKind::Asteroid),
        );
        let saturn = scene
            .bodies
            .iter()
            .find(|body| body.body_id.0 == "saturn")
            .expect("Saturn should be present");
        assert_eq!(saturn.appearance.facts.material, CelestialMaterialFamily::GasGiant);
        assert_eq!(
            saturn
                .appearance
                .facts
                .ring_system
                .expect("Saturn should carry ring facts")
                .outer_radius_m,
            140_220_000.0
        );
        let halley = scene
            .bodies
            .iter()
            .find(|body| body.body_id.0 == "halley")
            .expect("Halley should be present");
        assert_eq!(
            halley.appearance.facts.material,
            CelestialMaterialFamily::CometNucleus
        );
        assert!(halley.appearance.comet_visual.is_some());
        assert_eq!(physics_state_before_render, runtime.snapshot().bodies);
        assert!(scene
            .trails
            .iter()
            .any(|trail| trail.source_body_id == moon
                && trail.family == SceneTrailFamily::Trajectory));
        assert!(scene
            .trails
            .iter()
            .all(|trail| trail.family == SceneTrailFamily::Trajectory));
    }

    #[test]
    fn render_scene_extracts_selected_body_counts_and_camera_from_runtime() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());
        let spark = BodyId("spark".into());
        let sun = BodyId("sun".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth.clone(),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d {
                            x: 100.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: moon.clone(),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        source_mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: spark.clone(),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        source_mass_kg: 0.0,
                        radius_m: 25.0,
                        position_m: Vector3d {
                            x: 410_000_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                3,
            )
            .expect("spawn tracer should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: sun,
                        body_class: BodyClass::Star,
                        mass_kg: 1.989e30,
                        source_mass_kg: 1.989e30,
                        radius_m: 696_000_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                4,
            )
            .expect("spawn star should succeed");

        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                5,
            )
            .expect("setting observer mode should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon.clone()),
                },
                6,
            )
            .expect("focus body should succeed");

        let scene = runtime.render_scene();
        assert_eq!(scene.body_count, 4);
        assert_eq!(scene.tracer_count, 1);
        assert_eq!(scene.trail_count, 8);
        assert_eq!(scene.observer_mode, ObserverMode::FollowSelected);
        assert_eq!(scene.bodies.len(), 4);
        assert_eq!(
            scene
                .bodies
                .iter()
                .find(|body| body.body_id == moon)
                .map(|body| body.kind),
            Some(SceneBodyKind::Moon),
        );
        assert_eq!(scene.tracers.len(), 1);
        assert_eq!(scene.trails.len(), 8);
        assert_eq!(scene.lights.len(), 1);
        assert_eq!(scene.packet_metadata.tracer_family, SceneItemFamily::Tracer);
        assert_eq!(
            scene.packet_metadata.tracer_resolution_band,
            SceneDetailBand::Far
        );
        assert_eq!(scene.packet_metadata.trail_family, SceneItemFamily::Trail);
        assert_eq!(
            scene.packet_metadata.trail_horizon_band,
            SceneDetailBand::Far
        );
        assert_eq!(
            scene.packet_metadata.trail_simplification_budget_samples,
            96
        );
        assert!(scene.scene_revision.contains("branch=main"));
        assert!(scene
            .tracers
            .iter()
            .any(|tracer| tracer.source_body_id == spark));
        assert!(scene
            .lights
            .iter()
            .any(|light| light.light_id == "light:sun"));
        assert!(scene.trails.iter().all(|trail| !trail.samples_m.is_empty()));
        assert!(scene
            .trails
            .iter()
            .any(|trail| trail.family == SceneTrailFamily::Trajectory));
        assert!(scene
            .trails
            .iter()
            .any(|trail| trail.family == SceneTrailFamily::Prediction));
        assert!(scene
            .trails
            .iter()
            .any(|trail| trail.source_body_id == moon && trail.head_highlighted));
        assert!(
            scene
                .bodies
                .iter()
                .find(|body| body.body_id == moon)
                .expect("moon should be present")
                .selected
        );
        assert_eq!(scene.camera.target_m.x, 384_400_000.0);
        assert!(scene.camera.position_m.z > scene.camera.target_m.z);
        assert_eq!(scene.diagnostics.frame_number, 0);
        assert!(scene.diagnostics.gpu_upload_ms > 0.0);
        assert!(scene.scene_revision.contains("|diag:frame=0"));
    }

    #[test]
    fn orbit_archives_and_scene_trails_separate_history_prediction_and_live_trajectory() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth.clone(),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 1_000.0,
                            z: 0.0,
                        },
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: Some("baseline".into()),
                },
                2,
            )
            .expect("checkpoint should succeed");

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 60.0,
                },
                3,
            )
            .expect("advance epoch should succeed");

        let archives = runtime.orbit_archives(&OrbitArchiveQuery::default());
        let earth_families = archives
            .iter()
            .filter(|archive| archive.source_body_id == earth)
            .map(|archive| archive.family)
            .collect::<Vec<_>>();

        assert!(earth_families.contains(&OrbitArchiveFamily::Trajectory));
        assert!(earth_families.contains(&OrbitArchiveFamily::HistoricalOrbit));
        assert!(earth_families.contains(&OrbitArchiveFamily::Prediction));

        let scene = runtime.render_scene();
        let scene_families = scene
            .trails
            .iter()
            .filter(|trail| trail.source_body_id == earth)
            .map(|trail| trail.family)
            .collect::<Vec<_>>();

        assert!(scene_families.contains(&SceneTrailFamily::Trajectory));
        assert!(scene_families.contains(&SceneTrailFamily::HistoricalOrbit));
        assert!(scene_families.contains(&SceneTrailFamily::Prediction));
    }

    #[test]
    fn telemetry_report_captures_runtime_state_and_scene_diagnostics() {
        let mut runtime = new_runtime();
        let package_digest = digest(0x80);
        let package_id = package_id_for(&PackageKind::Scenario, &package_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-telemetry",
                    vec![package_locator(
                        PackageKind::Scenario,
                        semver(1, 2, 0),
                        package_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 2, 0),
                    package_digest,
                    "cache://pkg-scenario-telemetry-v120",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("manifest package should mount");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("planet".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d {
                            x: 0.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("planet spawn should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("tracer".into()),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        source_mass_kg: 0.0,
                        radius_m: 10.0,
                        position_m: Vector3d {
                            x: 100.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 0.0,
                            z: 0.0,
                        },
                    },
                },
                2,
            )
            .expect("tracer spawn should succeed");

        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 2.0,
                },
                3,
            )
            .expect("playback rate should change");
        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                4,
            )
            .expect("observer mode set should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("tracer".into())),
                },
                5,
            )
            .expect("focus body should succeed");
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 10.0,
                },
                6,
            )
            .expect("advance should succeed");

        let report = runtime.telemetry_report();
        let rendered_scene = runtime.render_scene();

        assert_eq!(report.scenario_id, ScenarioId("sol-system".into()));
        assert_eq!(report.branch_id, BranchId("main".into()));
        assert_eq!(report.total_bodies, 2);
        assert_eq!(report.total_tracers, 1);
        assert_eq!(
            report.timeline_semantics,
            TimelineSemantics::BranchedSandbox
        );
        assert_eq!(report.total_trail_samples, 4);
        assert_eq!(
            report.hardware_profile,
            HardwareProfile::offline_reference()
        );
        assert_eq!(report.active_checkpoint_id, None);
        assert_eq!(report.playback.sim_seconds_per_real_second, 2.0);
        assert_eq!(report.observer.mode, ObserverMode::FollowSelected);
        assert_eq!(report.observer.focus_body_id, Some(BodyId("tracer".into())));
        assert_eq!(
            report.total_trail_samples,
            report
                .trail_history_counts
                .iter()
                .map(|entry| entry.sample_count)
                .sum::<usize>()
        );
        assert_eq!(report.trail_history_counts.len(), 2);
        assert_eq!(
            report.trail_history_counts[0].body_id,
            BodyId("planet".into())
        );
        assert_eq!(report.trail_history_counts[0].sample_count, 2);
        assert_eq!(
            report.trail_history_counts[1].body_id,
            BodyId("tracer".into())
        );
        assert_eq!(report.trail_history_counts[1].sample_count, 2);
        assert_eq!(
            report
                .mounted_manifest
                .as_ref()
                .expect("manifest should be present")
                .manifest_id,
            "manifest-telemetry"
        );
        assert_eq!(
            report
                .mounted_manifest
                .as_ref()
                .expect("manifest should be present")
                .manifest_version,
            "1.0.0"
        );
        assert_eq!(
            report
                .mounted_manifest
                .as_ref()
                .expect("manifest should be present")
                .mounted_packages,
            1
        );
        assert!(report.scene_revision.starts_with(
            "scenario=sol-system|branch=main|epoch=10.000000|observer=FollowSelected"
        ));
        assert!(report.scene_revision.starts_with(
            &rendered_scene
                .scene_revision
                .split("|diag:")
                .next()
                .unwrap_or_default()
        ));
        assert_eq!(
            report.diagnostics.frame_number,
            rendered_scene.diagnostics.frame_number
        );

        assert!(report.invariants.total_energy_j.is_finite());
    }

    #[test]
    fn telemetry_report_uses_manifest_presence_none_when_unmounted() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("solo".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 1_000.0,
                        source_mass_kg: 1_000.0,
                        radius_m: 1_000.0,
                        position_m: Vector3d {
                            x: 42.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("body spawn should succeed");

        let report = runtime.telemetry_report();
        assert_eq!(report.total_bodies, 1);
        assert_eq!(report.total_tracers, 0);
        assert_eq!(report.total_trail_samples, 1);
        assert!(report.mounted_manifest.is_none());
        assert_eq!(report.active_checkpoint_id, None);
        assert_eq!(
            report.trail_history_counts,
            vec![RuntimeTrailHistoryCount {
                body_id: BodyId("solo".into()),
                sample_count: 1,
            }]
        );
    }

    #[test]
    fn checkpoint_and_branch_restore_checkpoint_state() {
        let mut runtime = new_runtime();
        let body_id = BodyId("probe-1".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: body_id.clone(),
                        body_class: BodyClass::Spacecraft,
                        mass_kg: 1_500.0,
                        source_mass_kg: 0.0,
                        radius_m: 2.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn should succeed");

        advance_staged_for_test(&mut runtime, 120.0, 2);

        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: Some("coast-start".into()),
                },
                3,
            )
            .expect("checkpoint creation should succeed");

        let checkpoint_id = checkpoint_events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.checkpoint_id.clone())
                }
                _ => None,
            })
            .expect("checkpoint event should be emitted");

        runtime
            .apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: body_id.clone(),
                    position_m: Vector3d {
                        x: 200.0,
                        y: 300.0,
                        z: 400.0,
                    },
                    velocity_mps: Vector3d {
                        x: 1.0,
                        y: 2.0,
                        z: 3.0,
                    },
                },
                4,
            )
            .expect("state mutation should succeed");

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("mission-alt".into())),
                },
                5,
            )
            .expect("branch creation should succeed");

        assert_eq!(runtime.active_branch_id(), &BranchId("mission-alt".into()));
        assert_eq!(runtime.snapshot().epoch_seconds, 120.0);
        assert_eq!(runtime.snapshot().bodies[0].position_m, Vector3d::default());
    }

    #[test]
    fn branch_snapshot_reports_origin_checkpoint() {
        let mut runtime = new_runtime();
        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: Some("origin".into()),
                },
                1,
            )
            .expect("checkpoint creation should succeed");

        let checkpoint_descriptor = checkpoint_events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.clone())
                }
                _ => None,
            })
            .expect("checkpoint event should exist");

        let branch_events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: checkpoint_descriptor.checkpoint_id.clone(),
                    new_branch_id: Some(BranchId("provenance".into())),
                },
                2,
            )
            .expect("branch creation should succeed");

        let snapshot = branch_events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::SnapshotPublished(snapshot) => Some(snapshot.clone()),
                _ => None,
            })
            .expect("snapshot should be published after branch creation");

        assert_eq!(
            snapshot.active_checkpoint,
            Some(checkpoint_descriptor.clone())
        );
    }

    #[test]
    fn checkpoint_descriptor_tracks_command_sequence() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 10.0,
                },
                1,
            )
            .expect("advance should succeed");

        let events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(solarlab_domain::CheckpointId("manual-01".into())),
                    label: None,
                },
                2,
            )
            .expect("checkpoint should succeed");

        let descriptor = events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.clone())
                }
                _ => None,
            })
            .expect("checkpoint descriptor event should exist");

        assert_eq!(descriptor.command_sequence, 2);
        assert_eq!(descriptor.epoch_seconds, 10.0);
        assert_eq!(descriptor.checkpoint_id.0, "manual-01");
    }

    #[test]
    fn invalid_time_inputs_leave_runtime_unchanged() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        let before = runtime.snapshot();
        let ordinal = runtime.next_command_ordinal;

        for value in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, 0.0, -1.0] {
            let err = runtime
                .apply_command(
                    WorldCommand::AdvanceEpoch {
                        delta_seconds: value,
                    },
                    3,
                )
                .expect_err("invalid epoch delta must fail");
            assert!(
                matches!(err, RuntimeError::InvalidEpochDelta(v) if v.to_bits() == value.to_bits())
            );
            let err = runtime
                .apply_command(
                    WorldCommand::SetPlaybackRate {
                        sim_seconds_per_real_second: value,
                    },
                    3,
                )
                .expect_err("invalid playback rate must fail");
            assert!(
                matches!(err, RuntimeError::InvalidPlaybackRate(v) if v.to_bits() == value.to_bits())
            );
            assert_eq!(runtime.snapshot(), before);
            assert_eq!(runtime.next_command_ordinal, ordinal);
        }

        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 2.0,
                },
                4,
            )
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 30.0,
                },
                5,
            )
            .unwrap();
        assert_eq!(runtime.snapshot().playback.sim_seconds_per_real_second, 2.0);
        assert_eq!(runtime.snapshot().epoch_seconds, 30.0);
    }

    #[test]
    fn rejects_unrepresentable_advance_before_mutation_but_allows_empty_world_jumps() {
        let mut runtime = new_runtime();
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: f64::MAX,
                },
                1,
            )
            .unwrap();
        let before = runtime.snapshot();
        let ordinal = runtime.next_command_ordinal;
        assert!(matches!(
            runtime.apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: f64::MAX
                },
                2
            ),
            Err(RuntimeError::InvalidEpochDelta(_))
        ));
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.next_command_ordinal, ordinal);

        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        runtime.config.physics.max_substep_seconds = f64::MIN_POSITIVE;
        let before = runtime.snapshot();
        let ordinal = runtime.next_command_ordinal;
        assert!(matches!(
            runtime.apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 1.0 }, 3),
            Err(RuntimeError::InvalidEpochDelta(_))
        ));
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.next_command_ordinal, ordinal);
        runtime.config.physics.max_substep_seconds = 60.0;
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 30.0,
                },
                4,
            )
            .unwrap();
        assert_eq!(runtime.snapshot().epoch_seconds, 30.0);
    }

    #[test]
    fn staged_advance_preserves_strict_schedule_and_atomic_history() {
        for delta in [130.0, 130.5] {
            let mut runtime = new_runtime();
            spawn_two_body_system(&mut runtime);
            let before = runtime.snapshot();
            let before_revision = runtime.snapshot_revision();
            let mut oracle = super::world_bodies_to_solver_state(&before.bodies);
            solarlab_physics::advance_authoritative_scalar(
                &runtime.config.physics,
                &mut oracle,
                delta,
            );
            let op = runtime
                .begin_advance([1, 2], 0, before_revision + 1, delta, 7)
                .unwrap()
                .clone();
            assert_eq!(op.total_steps, delta.ceil() as usize);
            assert_eq!(runtime.snapshot(), before);
            assert_eq!(runtime.snapshot_revision(), before_revision);
            assert!(matches!(
                runtime.apply_command(WorldCommand::ResumePlayback, 8),
                Err(RuntimeError::AdvanceBusy)
            ));
            assert!(matches!(
                runtime.begin_advance([3, 4], 0, before_revision + 1, delta, 7),
                Err(RuntimeError::AdvanceBusy)
            ));
            assert_eq!(
                runtime
                    .begin_advance([1, 2], 0, before_revision + 1, delta, 7)
                    .unwrap()
                    .generation,
                op.generation
            );
            let first = runtime
                .continue_advance(op.generation, 0)
                .unwrap()
                .0
                .clone();
            assert_eq!(first.completed_steps, 64);
            assert_eq!(runtime.snapshot(), before);
            assert!(matches!(
                runtime.continue_advance(op.generation, 0),
                Err(RuntimeError::AdvanceStale)
            ));
            let second = runtime
                .continue_advance(op.generation, 64)
                .unwrap()
                .0
                .clone();
            let done = runtime
                .continue_advance(op.generation, second.completed_steps)
                .unwrap()
                .0
                .clone();
            assert_eq!(done.state, super::AdvanceState::Committed);
            assert_eq!(runtime.snapshot().epoch_seconds, delta);
            assert_eq!(
                super::world_bodies_to_solver_state(&runtime.snapshot().bodies),
                oracle
            );
            assert_eq!(runtime.snapshot_revision(), before_revision + 1);
            assert_eq!(runtime.query_advance().unwrap().generation, op.generation);
        }
    }

    #[test]
    fn catalog_jupiter_io_staged_chunks_match_strict_short_horizon() {
        let seed =
            solarlab_data::scenario_pack_seed("showcase.jupiter-system").expect("catalog scenario");
        let selected: Vec<_> = seed
            .bodies
            .into_iter()
            .filter(|body| ["sun", "jupiter", "io"].contains(&body.body_id.as_str()))
            .collect();
        assert_eq!(selected.len(), 3);
        for delta in [130.0, 130.5] {
            let mut runtime = new_runtime();
            for (index, body) in selected.iter().enumerate() {
                runtime
                    .apply_command(
                        WorldCommand::SpawnBody {
                            body: BodyState {
                                body_id: BodyId(body.body_id.clone()),
                                body_class: body.body_class.clone(),
                                mass_kg: body.mass_kg,
                                source_mass_kg: BodyState::default_source_mass_kg(
                                    &body.body_class,
                                    body.mass_kg,
                                ),
                                radius_m: body.radius_m,
                                position_m: body.position_m,
                                velocity_mps: body.velocity_mps,
                            },
                        },
                        index as i64,
                    )
                    .unwrap();
            }
            let before = runtime.snapshot();
            let mut strict = super::world_bodies_to_solver_state(&before.bodies);
            let invariants = solarlab_physics::advance_authoritative_scalar(
                &runtime.config.physics,
                &mut strict,
                delta,
            );
            let base = runtime.snapshot_revision() + 1;
            let op = runtime
                .begin_advance([81, 82], 0, base, delta, 7)
                .unwrap()
                .clone();
            let mut completed = 0;
            let mut chunks = Vec::new();
            loop {
                let (progress, events) =
                    runtime.continue_advance(op.generation, completed).unwrap();
                chunks.push(progress.completed_steps - completed);
                completed = progress.completed_steps;
                if progress.state == super::AdvanceState::Committed {
                    assert!(!events.is_empty());
                    break;
                }
                assert!(events.is_empty());
            }
            assert_eq!(
                chunks,
                if delta == 130.0 {
                    vec![64, 64, 2]
                } else {
                    vec![64, 64, 3]
                }
            );
            let after = runtime.snapshot();
            let actual = super::world_bodies_to_solver_state(&after.bodies);
            for (a, b) in actual.iter().zip(&strict) {
                for (x, y) in [
                    (a.mass_kg, b.mass_kg),
                    (a.position_m.x, b.position_m.x),
                    (a.position_m.y, b.position_m.y),
                    (a.position_m.z, b.position_m.z),
                    (a.velocity_mps.x, b.velocity_mps.x),
                    (a.velocity_mps.y, b.velocity_mps.y),
                    (a.velocity_mps.z, b.velocity_mps.z),
                ] {
                    assert_eq!(x.to_bits(), y.to_bits());
                }
            }
            assert_eq!(after.invariants, invariants);
            assert_eq!(after.epoch_seconds, delta);
            assert_eq!(runtime.snapshot_revision(), base);
        }
    }

    #[test]
    fn cancellation_and_empty_world_begin_do_not_commit() {
        let mut runtime = new_runtime();
        let before = runtime.snapshot();
        let ordinal = runtime.snapshot_revision();
        let op = runtime
            .begin_advance([5, 6], 0, 1, f64::MAX, 1)
            .unwrap()
            .clone();
        assert_eq!(op.total_steps, 0);
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(
            runtime.cancel_advance(op.generation).unwrap().state,
            super::AdvanceState::Cancelled
        );
        assert_eq!(runtime.snapshot_revision(), ordinal);
        assert_eq!(runtime.snapshot(), before);
        let next = runtime
            .begin_advance([7, 8], op.generation, 1, f64::MAX, 2)
            .unwrap()
            .clone();
        assert!(matches!(
            runtime.begin_advance([5, 6], 0, 1, f64::MAX, 1),
            Err(RuntimeError::AdvanceBusy)
        ));
        assert_eq!(
            runtime
                .continue_advance(next.generation, 0)
                .unwrap()
                .0
                .state,
            super::AdvanceState::Committed
        );
        assert_eq!(runtime.snapshot().epoch_seconds, f64::MAX);
        assert_eq!(runtime.snapshot_revision(), ordinal + 1);
    }

    #[test]
    fn cancellation_after_partial_chunk_discards_staged_work() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        let before = runtime.snapshot();
        let revision = runtime.snapshot_revision();
        let op = runtime
            .begin_advance([9, 10], 0, revision + 1, 130.5, 11)
            .unwrap()
            .clone();
        assert_eq!(
            runtime
                .continue_advance(op.generation, 0)
                .unwrap()
                .0
                .completed_steps,
            64
        );
        assert_eq!(
            runtime.cancel_advance(op.generation).unwrap().state,
            super::AdvanceState::Cancelled
        );
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.snapshot_revision(), revision);
        assert_eq!(
            runtime.query_advance().unwrap().state,
            super::AdvanceState::Cancelled
        );
        let next = runtime
            .begin_advance([11, 12], op.generation, revision + 1, 2.0, 12)
            .unwrap()
            .clone();
        assert!(matches!(
            runtime.begin_advance([9, 10], 0, revision + 1, 130.5, 11),
            Err(RuntimeError::AdvanceBusy)
        ));
        assert_eq!(
            runtime.cancel_advance(next.generation).unwrap().state,
            super::AdvanceState::Cancelled
        );
        assert!(matches!(
            runtime.begin_advance([9, 10], 0, revision + 1, 130.5, 11),
            Err(RuntimeError::AdvanceStale)
        ));
        runtime.advance_generation = i64::MAX as u64;
        assert!(matches!(
            runtime.begin_advance([13, 14], i64::MAX as u64, revision + 1, 1.0, 13),
            Err(RuntimeError::AdvanceStale)
        ));
    }

    #[test]
    fn playback_tick_integrates_the_representable_committed_delta() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        runtime.active_branch_mut().world.epoch_seconds = 1.0e16;
        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 6.0,
                },
                1,
            )
            .unwrap();
        runtime
            .apply_command(WorldCommand::ResumePlayback, 2)
            .unwrap();
        let mut strict = runtime.clone();
        strict
            .apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 2.0 }, 3)
            .unwrap();
        let before_epoch = runtime.snapshot().epoch_seconds;
        let result = runtime.playback_tick(0.5, 3).unwrap();
        assert_eq!(result.requested_seconds, 3.0);
        assert_eq!(result.applied_seconds, 2.0);
        assert!(result.limited);
        assert_eq!(
            result.committed_epoch_seconds - before_epoch,
            result.applied_seconds
        );
        assert_eq!(result.committed_revision, runtime.snapshot_revision());
        assert_eq!(runtime.snapshot(), strict.snapshot());

        let mut fractional = new_runtime();
        fractional.active_branch_mut().world.epoch_seconds = 1.0;
        fractional
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 0.2,
                },
                1,
            )
            .unwrap();
        fractional
            .apply_command(WorldCommand::ResumePlayback, 2)
            .unwrap();
        let before_epoch = fractional.snapshot().epoch_seconds;
        let result = fractional.playback_tick(0.5, 3).unwrap();
        assert!(result.applied_seconds > 0.0 && result.applied_seconds <= result.requested_seconds);
        assert_eq!(
            result.committed_epoch_seconds - before_epoch,
            result.applied_seconds
        );
        assert_eq!(
            fractional.snapshot().epoch_seconds,
            result.committed_epoch_seconds
        );

        runtime.active_branch_mut().world.epoch_seconds = f64::MAX;
        let before = runtime.snapshot();
        let revision = runtime.snapshot_revision();
        assert!(matches!(
            runtime.playback_tick(0.5, 4),
            Err(RuntimeError::InvalidEpochDelta(_))
        ));
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.snapshot_revision(), revision);
    }

    #[test]
    fn stale_begin_base_after_failed_command_cannot_reserve() {
        let mut runtime = new_runtime();
        let observed_base = runtime.snapshot_revision() + 1;
        assert!(matches!(
            runtime.apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("missing".into()))
                },
                1
            ),
            Err(RuntimeError::UnknownBody(_))
        ));
        assert!(matches!(
            runtime.begin_advance([31, 32], 0, observed_base, 10.0, 2),
            Err(RuntimeError::AdvanceStale)
        ));
        assert!(runtime.query_advance().is_none());
        assert_eq!(runtime.advance_generation, 0);
        let actual_base = runtime.snapshot_revision() + 1;
        let op = runtime
            .begin_advance([31, 32], 0, actual_base, 10.0, 2)
            .unwrap()
            .clone();
        assert_eq!(runtime.query_advance().unwrap().intent, [31, 32]);
        assert_eq!(
            runtime
                .begin_advance([31, 32], 0, actual_base, 10.0, 2)
                .unwrap()
                .generation,
            op.generation
        );
        assert!(matches!(
            runtime.begin_advance([31, 32], 0, observed_base, 10.0, 2),
            Err(RuntimeError::AdvanceStale)
        ));
        assert_eq!(runtime.query_advance().unwrap().generation, op.generation);
    }

    #[test]
    fn ordinal_boundary_preserves_terminal_recovery_and_rejects_new_work() {
        let mut runtime = new_runtime();
        runtime.next_command_ordinal = i64::MAX as u64 - 1;
        let base = runtime.next_command_ordinal();
        let op = runtime
            .begin_advance([51, 52], 0, base, 1.0, 3)
            .unwrap()
            .clone();
        let (done, events) = runtime.continue_advance(op.generation, 0).unwrap();
        assert_eq!(done.state, super::AdvanceState::Committed);
        assert!(!events.is_empty());
        assert_eq!(runtime.next_command_ordinal(), i64::MAX as u64);
        let before = runtime.snapshot();
        assert!(matches!(
            runtime.begin_advance([53, 54], op.generation, i64::MAX as u64, 1.0, 4),
            Err(RuntimeError::AdvanceWorkLimit)
        ));
        assert_eq!(runtime.snapshot(), before);
        runtime.active_branch_mut().world.playback.paused = false;
        let before = runtime.snapshot();
        assert!(matches!(
            runtime.playback_tick(0.5, 5),
            Err(RuntimeError::AdvanceWorkLimit)
        ));
        assert_eq!(runtime.snapshot(), before);
        assert!(matches!(
            runtime.apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("missing".into()))
                },
                6
            ),
            Err(RuntimeError::UnknownBody(_))
        ));
        assert_eq!(runtime.next_command_ordinal(), i64::MAX as u64 + 1);
        let terminal = runtime.query_advance().unwrap();
        assert_eq!(terminal.state, super::AdvanceState::Committed);
        assert_eq!(terminal.generation, op.generation);
        let (terminal, repeated_events) = runtime
            .continue_advance(op.generation, op.total_steps)
            .unwrap();
        assert_eq!(terminal.state, super::AdvanceState::Committed);
        assert!(repeated_events.is_empty());
    }

    #[test]
    fn final_staged_commit_delivers_events_exactly_once() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        let mut direct = runtime.clone();
        let direct_events = direct
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 10.5,
                },
                7,
            )
            .unwrap();
        let generation = runtime
            .begin_advance([21, 22], 0, runtime.snapshot_revision() + 1, 10.5, 7)
            .unwrap()
            .generation;
        let (operation, events) = runtime.continue_advance(generation, 0).unwrap();
        assert_eq!(operation.state, super::AdvanceState::Committed);
        let completed = operation.completed_steps;
        assert_eq!(events, direct_events);
        let (operation, repeated_events) = runtime.continue_advance(generation, completed).unwrap();
        assert_eq!(operation.state, super::AdvanceState::Committed);
        assert!(repeated_events.is_empty());
        assert_eq!(runtime.snapshot(), direct.snapshot());
    }

    #[test]
    fn oversized_synchronous_and_limited_tick_are_truthful() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        let before = runtime.snapshot();
        let ordinal = runtime.snapshot_revision();
        assert!(matches!(
            runtime.apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 65.0
                },
                1
            ),
            Err(RuntimeError::AdvanceWorkLimit)
        ));
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.snapshot_revision(), ordinal);
        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 1000.0,
                },
                2,
            )
            .unwrap();
        runtime
            .apply_command(WorldCommand::ResumePlayback, 3)
            .unwrap();
        let tick = runtime.playback_tick(0.5, 4).unwrap();
        assert_eq!(tick.requested_seconds, 500.0);
        assert_eq!(tick.applied_seconds, 64.0);
        assert!(tick.limited);
        assert_eq!(runtime.snapshot().epoch_seconds, 64.0);
    }

    #[test]
    fn rejects_absorbed_epoch_delta_before_integrating_moving_bodies() {
        let mut runtime = new_runtime();
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: f64::MAX,
                },
                1,
            )
            .unwrap();
        spawn_two_body_system(&mut runtime);
        let before = runtime.snapshot();
        let ordinal = runtime.next_command_ordinal;
        assert!(matches!(
            runtime.apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 1.0 }, 3),
            Err(RuntimeError::InvalidEpochDelta(1.0))
        ));
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.next_command_ordinal, ordinal);
    }

    #[test]
    fn prediction_rejects_absorbed_and_overflowing_epochs() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        runtime.config.physics.max_substep_seconds = f64::MAX;
        let snapshot = runtime.snapshot();
        let ids = vec![snapshot.bodies[0].body_id.clone()];
        let predict = |epoch, delta, count| {
            super::build_prediction_archives(
                &snapshot.bodies,
                epoch,
                &ids,
                delta,
                count,
                &runtime.config.physics,
                &runtime.hardware_profile.cpu_features,
            )
        };
        assert!(predict(f64::MAX, 1.0, 1).is_empty());
        assert!(predict(f64::MAX / 2.0, f64::MAX / 2.0, 2).is_empty());
        let normal = predict(0.0, 1.0, 2);
        assert_eq!(normal.len(), 1);
        assert_eq!(normal[0].samples[0].epoch_seconds, 1.0);
        assert_eq!(normal[0].samples[1].epoch_seconds, 2.0);
    }

    #[test]
    fn prediction_does_not_publish_samples_for_unrepresentable_steps() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);
        runtime.config.physics.max_substep_seconds = f64::MIN_POSITIVE;
        let snapshot = runtime.snapshot();
        let ids = vec![snapshot.bodies[0].body_id.clone()];
        let archives = super::build_prediction_archives(
            &snapshot.bodies,
            snapshot.epoch_seconds,
            &ids,
            1.0,
            2,
            &runtime.config.physics,
            &runtime.hardware_profile.cpu_features,
        );
        assert!(archives.is_empty());
    }

    #[test]
    fn advance_epoch_moves_bodies_and_updates_invariants() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);

        let before = runtime.snapshot();
        let before_distance = body_separation_x(&before);

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 30.0,
                },
                3,
            )
            .expect("advance should succeed");

        let after = runtime.snapshot();
        let after_distance = body_separation_x(&after);

        assert_eq!(after.epoch_seconds, 30.0);
        assert!(after_distance < before_distance);
        assert!(after.invariants.total_energy_j.is_finite());
    }

    #[test]
    fn moon_like_bodies_move_between_epoch_snapshots() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("sun".into()),
                        body_class: BodyClass::Star,
                        mass_kg: 1.98847e30,
                        source_mass_kg: 1.98847e30,
                        radius_m: 696_340_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn sun should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("moon".into()),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        source_mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 1_022.0,
                            z: 0.0,
                        },
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");

        let snapshot_t0 = runtime.snapshot();
        let moon_t0 = body_position(&snapshot_t0, &BodyId("moon".into())).clone();

        advance_staged_for_test(&mut runtime, 86_400.0, 3);

        let snapshot_t1 = runtime.snapshot();
        let moon_t1 = body_position(&snapshot_t1, &BodyId("moon".into()));
        let movement = displacement_magnitude(&moon_t1, &moon_t0);

        assert!(
            movement > 1.0e6,
            "Expected Moon-like body to move between epochs, movement={movement}"
        );
    }

    #[test]
    fn fallback_pluto_like_orbit_changes_relative_position_with_epoch_delta() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("sun".into()),
                        body_class: BodyClass::Star,
                        mass_kg: 1.98847e30,
                        source_mass_kg: 1.98847e30,
                        radius_m: 696_340_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn sun should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("pluto".into()),
                        body_class: BodyClass::DwarfPlanet,
                        mass_kg: 1.309e22,
                        source_mass_kg: 1.309e22,
                        radius_m: 1_188_000.0,
                        position_m: Vector3d {
                            x: 5_900_000_000_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 4_700.0,
                            z: 0.0,
                        },
                    },
                },
                2,
            )
            .expect("spawn pluto should succeed");

        let snapshot_t0 = runtime.snapshot();
        let sun_t0 = body_position(&snapshot_t0, &BodyId("sun".into()));
        let pluto_t0 = body_position(&snapshot_t0, &BodyId("pluto".into()));
        let relative_t0 = relative_position(&pluto_t0, &sun_t0);

        advance_staged_for_test(&mut runtime, 31_536_000.0, 3);

        let snapshot_t1 = runtime.snapshot();
        let sun_t1 = body_position(&snapshot_t1, &BodyId("sun".into()));
        let pluto_t1 = body_position(&snapshot_t1, &BodyId("pluto".into()));
        let relative_t1 = relative_position(&pluto_t1, &sun_t1);
        let movement = displacement_magnitude(&relative_t1, &relative_t0);

        assert!(
            movement > 1.0e6,
            "Expected Pluto fallback-style orbit to propagate across epochs, movement={movement}"
        );
    }

    #[test]
    fn canonical_major_seed_propagates_moon_and_pluto_with_epoch() {
        let mut runtime = new_runtime();
        runtime.config.physics.max_substep_seconds = 900.0;
        seed_major_bodies(&mut runtime);

        let snapshot_t0 = runtime.snapshot();
        let earth_t0 = body_position(&snapshot_t0, &BodyId("earth".into()));
        let moon_t0 = body_position(&snapshot_t0, &BodyId("moon".into()));
        let sun_t0 = body_position(&snapshot_t0, &BodyId("sun".into()));
        let pluto_t0 = body_position(&snapshot_t0, &BodyId("pluto".into()));
        let moon_relative_t0 = relative_position(&moon_t0, &earth_t0);
        let pluto_relative_t0 = relative_position(&pluto_t0, &sun_t0);

        advance_staged_for_test(&mut runtime, 86_400.0, 99);

        let snapshot_t1 = runtime.snapshot();
        let earth_t1 = body_position(&snapshot_t1, &BodyId("earth".into()));
        let moon_t1 = body_position(&snapshot_t1, &BodyId("moon".into()));
        let sun_t1 = body_position(&snapshot_t1, &BodyId("sun".into()));
        let pluto_t1 = body_position(&snapshot_t1, &BodyId("pluto".into()));
        let moon_relative_t1 = relative_position(&moon_t1, &earth_t1);
        let pluto_relative_t1 = relative_position(&pluto_t1, &sun_t1);

        let moon_relative_movement_m = displacement_magnitude(&moon_relative_t1, &moon_relative_t0);
        let pluto_relative_movement_m =
            displacement_magnitude(&pluto_relative_t1, &pluto_relative_t0);

        assert!(
            moon_relative_movement_m > 1.0e6,
            "Expected canonical-seed Moon orbit to propagate across epochs, movement={moon_relative_movement_m}"
        );
        assert!(
            pluto_relative_movement_m > 1.0e6,
            "Expected canonical-seed Pluto orbit to propagate across epochs, movement={pluto_relative_movement_m}"
        );
    }

    #[test]
    fn advance_epoch_is_deterministic_for_identical_command_streams() {
        let mut runtime_a = new_runtime();
        let mut runtime_b = new_runtime();
        spawn_two_body_system(&mut runtime_a);
        spawn_two_body_system(&mut runtime_b);

        runtime_a
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 45.0,
                },
                3,
            )
            .expect("advance should succeed");
        runtime_b
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 45.0,
                },
                3,
            )
            .expect("advance should succeed");

        assert_eq!(runtime_a.snapshot(), runtime_b.snapshot());
    }

    #[test]
    fn tracer_and_spacecraft_masses_do_not_perturb_authoritative_bodies() {
        let mut baseline = new_runtime();
        let mut with_tracer = new_runtime();
        spawn_two_body_system(&mut baseline);
        spawn_two_body_system(&mut with_tracer);

        let tracer = BodyState {
            body_id: BodyId("teaching-probe".into()),
            body_class: BodyClass::Spacecraft,
            mass_kg: 1.0e30,
            source_mass_kg: 0.0,
            radius_m: 10.0,
            position_m: Vector3d {
                x: 8.0e7,
                y: 1.0e7,
                z: 0.0,
            },
            velocity_mps: Vector3d {
                x: 0.0,
                y: -1_000.0,
                z: 0.0,
            },
        };
        assert_eq!(tracer.source_mass_kg(), 0.0);
        with_tracer
            .apply_command(WorldCommand::SpawnBody { body: tracer }, 3)
            .expect("probe spawn should succeed");

        for runtime in [&mut baseline, &mut with_tracer] {
            advance_staged_for_test(runtime, 120.0, 4);
        }

        let baseline_snapshot = baseline.snapshot();
        let tracer_snapshot = with_tracer.snapshot();
        for body_id in [BodyId("primary".into()), BodyId("secondary".into())] {
            assert_eq!(
                body_position(&baseline_snapshot, &body_id),
                body_position(&tracer_snapshot, &body_id),
                "Massive body {body_id:?} must not be perturbed by teaching probes"
            );
        }
        assert_ne!(
            body_position(&tracer_snapshot, &BodyId("teaching-probe".into())).x,
            8.0e7,
            "The probe should still respond to massive-body gravity as a tracer"
        );
    }

    #[test]
    fn comet_mass_is_not_promoted_to_gravity_source_mass() {
        assert_eq!(
            BodyState::default_source_mass_kg(&BodyClass::Comet, 2.2e14),
            0.0,
        );
    }

    #[test]
    fn rejects_invalid_commands() {
        let mut runtime = new_runtime();

        let err = runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("missing".into())),
                },
                1,
            )
            .expect_err("focus should fail for unknown body");

        assert_eq!(err, RuntimeError::UnknownBody(BodyId("missing".into())));

        let err = runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 0.0,
                },
                2,
            )
            .expect_err("zero playback rate must fail");

        assert_eq!(err, RuntimeError::InvalidPlaybackRate(0.0));
    }

    #[test]
    fn rejects_duplicate_checkpoint_ids_across_branches() {
        let mut runtime = new_runtime();
        let checkpoint_id = CheckpointId("manual-dup".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("initial checkpoint should succeed");

        let err = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                2,
            )
            .expect_err("duplicate checkpoint id must fail");

        assert_eq!(err, RuntimeError::DuplicateCheckpoint(checkpoint_id));
    }

    #[test]
    fn branch_creation_command_headers_use_new_branch() {
        let mut runtime = new_runtime();
        let checkpoint_id = CheckpointId("branch-source".into());
        let new_branch_id = BranchId("split-path".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("checkpoint creation should succeed");

        let events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(new_branch_id.clone()),
                },
                2,
            )
            .expect("branch creation should succeed");

        let header_branch = events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(header)) => {
                    Some(header.branch_id.clone())
                }
                _ => None,
            })
            .expect("command event should exist");

        assert_eq!(header_branch, new_branch_id.clone());

        let branch_state = runtime
            .branches
            .get(&new_branch_id)
            .expect("new branch should exist");

        let command_entry = branch_state
            .command_log
            .last()
            .expect("history entry should be logged on new branch");

        assert_eq!(command_entry.header.branch_id, new_branch_id);
    }

    #[test]
    fn automatic_ids_skip_manual_overlaps() {
        let mut runtime = new_runtime();
        let manual_checkpoint_id = CheckpointId("cp-005".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(manual_checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("manual checkpoint creation should succeed");

        let auto_checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: None,
                },
                2,
            )
            .expect("automatic checkpoint creation should succeed");

        let auto_checkpoint_id = checkpoint_id_from_events(&auto_checkpoint_events);
        assert_eq!(auto_checkpoint_id, CheckpointId("cp-000006".into()));

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: manual_checkpoint_id.clone(),
                    new_branch_id: Some(BranchId("branch-010".into())),
                },
                3,
            )
            .expect("manual branch creation should succeed");

        let branch_checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: None,
                },
                4,
            )
            .expect("checkpoint on manual branch should succeed");

        let branch_checkpoint_id = checkpoint_id_from_events(&branch_checkpoint_events);

        let auto_branch_events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: branch_checkpoint_id,
                    new_branch_id: None,
                },
                5,
            )
            .expect("automatic branch creation should succeed");

        let auto_branch_id = branch_id_from_events(&auto_branch_events);
        assert_eq!(auto_branch_id, BranchId("branch-011".into()));
    }

    #[test]
    fn branch_creation_emits_branch_event_before_command() {
        let mut runtime = new_runtime();
        let checkpoint_id = CheckpointId("order-source".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("checkpoint creation should succeed");

        let events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("order-branch".into())),
                },
                2,
            )
            .expect("branch creation should succeed");

        assert!(matches!(
            events[0],
            RuntimeEvent::HistoryAppended(HistoryEvent::BranchCreated(_))
        ));
        assert!(matches!(
            events[1],
            RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(_))
        ));
        assert!(matches!(events[2], RuntimeEvent::SnapshotPublished(_)));
    }

    #[test]
    fn apply_update_manifest_commits_installed_state_but_keeps_mount_explicit() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x11);
        let package_id = package_id_for(&package_kind, &package_digest);
        let manifest = manifest(
            "manifest-alpha",
            vec![package_locator(
                package_kind.clone(),
                semver(1, 0, 0),
                package_digest.clone(),
                true,
            )],
        );
        let fetched = vec![stored_package(
            package_kind,
            semver(1, 0, 0),
            package_digest,
            "cache://pkg-scenario-alpha-v1",
        )];

        let apply_result = runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(fetched),
            })
            .expect("manifest apply should succeed");

        assert_eq!(apply_result.plan.selected_package_ids, vec![package_id]);
        assert_eq!(
            apply_result
                .mounted_manifest
                .expect("installed manifest should exist")
                .mounted_packages
                .len(),
            0
        );
        assert_eq!(
            runtime
                .snapshot()
                .mounted_manifest
                .expect("snapshot should include installed manifest metadata")
                .mounted_packages
                .len(),
            0
        );
    }

    #[test]
    fn mount_package_updates_runtime_snapshot_and_rejects_uninstalled_package() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x12);
        let package_id = package_id_for(&package_kind, &package_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-alpha",
                    vec![package_locator(
                        package_kind.clone(),
                        semver(1, 0, 0),
                        package_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    package_kind,
                    semver(1, 0, 0),
                    package_digest,
                    "cache://pkg-scenario-alpha-v1",
                )]),
            })
            .expect("manifest apply should seed local data state");

        let err = runtime
            .mount_package(MountPackageCommand {
                package_id: "pkg-missing".to_owned(),
            })
            .expect_err("mounting unknown package should fail");
        assert_eq!(
            err,
            RuntimeError::PackageNotInstalled("pkg-missing".to_owned())
        );

        let mounted = runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed for installed package");

        assert_eq!(mounted.mounted_packages.len(), 1);
        assert_eq!(mounted.mounted_packages[0].package_id, package_id);
        assert_eq!(
            runtime
                .snapshot()
                .mounted_manifest
                .expect("snapshot should include mounted package")
                .mounted_packages
                .len(),
            1
        );
    }

    #[test]
    fn mounted_packages_are_checkpoint_and_branch_state() {
        let mut runtime = new_runtime();
        let initial_kind = PackageKind::Scenario;
        let initial_digest = digest(0x31);
        let initial_package_id = package_id_for(&initial_kind, &initial_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-alpha",
                    vec![package_locator(
                        initial_kind.clone(),
                        semver(1, 0, 0),
                        initial_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    initial_kind,
                    semver(1, 0, 0),
                    initial_digest,
                    "cache://pkg-scenario-alpha-v1",
                )]),
            })
            .expect("initial apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: initial_package_id.clone(),
            })
            .expect("initial mount should succeed");

        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(CheckpointId("pkg-cp".into())),
                    label: Some("pkg-mounted".into()),
                },
                1,
            )
            .expect("checkpoint creation should succeed");
        let checkpoint_id = checkpoint_id_from_events(&checkpoint_events);

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-beta",
                    vec![package_locator(
                        PackageKind::CatalogPack,
                        semver(2, 0, 0),
                        digest(0x32),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::CatalogPack,
                    semver(2, 0, 0),
                    digest(0x32),
                    "cache://pkg-catalog-beta-v2",
                )]),
            })
            .expect("second apply should supersede installed state");
        let second_package_id = package_id_for(&PackageKind::CatalogPack, &digest(0x32));
        runtime
            .mount_package(MountPackageCommand {
                package_id: second_package_id,
            })
            .expect("second mount should succeed");

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("pkg-restored".into())),
                },
                2,
            )
            .expect("branch from checkpoint should succeed");

        let snapshot = runtime.snapshot();
        let mounted_manifest = snapshot
            .mounted_manifest
            .expect("mounted manifest should be restored from checkpoint");
        assert_eq!(mounted_manifest.manifest_id, "manifest-alpha".to_owned());
        assert_eq!(
            mounted_manifest
                .mounted_packages
                .iter()
                .map(|pkg| pkg.package_id.as_str())
                .collect::<Vec<_>>(),
            vec![initial_package_id.as_str()]
        );
    }

    #[test]
    fn extract_render_scene_uses_manifest_metadata_as_provenance_when_available() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x22);
        let package_id = package_id_for(&package_kind, &package_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-alpha",
                    vec![package_locator(
                        package_kind.clone(),
                        semver(1, 2, 3),
                        package_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    package_kind,
                    semver(1, 2, 3),
                    package_digest,
                    "cache://pkg-scenario-alpha-v123",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed");

        let scene = extract_render_scene(&runtime.snapshot());
        let provenance = scene.provenance.expect("provenance should be present");

        assert_eq!(provenance.source, "stable".to_owned());
        assert_eq!(provenance.version, "1.0.0".to_owned());
        assert_eq!(provenance.manifest_id, "manifest-alpha".to_owned());
        assert_eq!(provenance.manifest_digest, None);
        assert_eq!(provenance.package_digest, Some(digest(0x22)));
    }

    #[test]
    fn extract_render_scene_includes_manifest_digest_when_available() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x23);
        let package_id = package_id_for(&package_kind, &package_digest);
        let manifest_digest = digest(0x44);
        let mut manifest = manifest(
            "manifest-beta",
            vec![package_locator(
                package_kind.clone(),
                semver(1, 2, 3),
                package_digest.clone(),
                true,
            )],
        );
        manifest.manifest_digest = Some(manifest_digest.clone());

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    package_kind,
                    semver(1, 2, 3),
                    package_digest,
                    "cache://pkg-scenario-beta-v123",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed");

        let scene = extract_render_scene(&runtime.snapshot());
        let provenance = scene.provenance.expect("provenance should be present");

        assert_eq!(provenance.manifest_id, "manifest-beta".to_owned());
        assert_eq!(
            provenance.manifest_digest,
            Some(super::format_manifest_digest(&manifest_digest))
        );
        assert_eq!(provenance.package_digest, Some(digest(0x23)));
    }

    #[test]
    fn scene_revision_changes_when_observer_mode_changes() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth,
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: moon.clone(),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        source_mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon),
                },
                3,
            )
            .expect("focus body should succeed");
        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                4,
            )
            .expect("observer mode should succeed");

        let initial_revision = runtime.render_scene().scene_revision;

        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowHost,
                },
                5,
            )
            .expect("observer mode change should succeed");

        assert_ne!(runtime.render_scene().scene_revision, initial_revision);
    }

    #[test]
    fn scene_revision_changes_when_selected_body_changes() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth.clone(),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: moon.clone(),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        source_mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon),
                },
                3,
            )
            .expect("focus body should succeed");

        let moon_revision = runtime.render_scene().scene_revision;

        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(earth),
                },
                4,
            )
            .expect("focus body change should succeed");

        assert_ne!(runtime.render_scene().scene_revision, moon_revision);
    }

    #[test]
    fn extract_render_scene_trail_history_is_bounded_and_checkpoint_scoped() {
        let mut runtime = new_runtime();
        let tracer = BodyId("trail-probe".into());
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: tracer.clone(),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        source_mass_kg: 0.0,
                        radius_m: 20.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn tracer should succeed");

        for i in 0..(super::TRAIL_HISTORY_MAX_SAMPLES + 20) {
            runtime
                .apply_command(
                    WorldCommand::SetBodyKinematics {
                        body_id: tracer.clone(),
                        position_m: Vector3d {
                            x: i as f64,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                    (i + 2) as i64,
                )
                .expect("kinematics update should succeed");
        }

        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(CheckpointId("trail-cp".into())),
                    label: Some("trail checkpoint".into()),
                },
                500,
            )
            .expect("checkpoint creation should succeed");
        let checkpoint_id = checkpoint_id_from_events(&checkpoint_events);

        for i in 0..5 {
            runtime
                .apply_command(
                    WorldCommand::SetBodyKinematics {
                        body_id: tracer.clone(),
                        position_m: Vector3d {
                            x: 1_000.0 + i as f64,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                    600 + i as i64,
                )
                .expect("post-checkpoint update should succeed");
        }

        let scene_after_extra_updates = runtime.render_scene();
        let trail_after_extra_updates = scene_after_extra_updates
            .trails
            .iter()
            .find(|trail| trail.source_body_id == tracer)
            .expect("trail should exist after updates");
        assert_eq!(
            trail_after_extra_updates.samples_m.len(),
            super::TRAIL_HISTORY_MAX_SAMPLES
        );

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("trail-restored".into())),
                },
                700,
            )
            .expect("branch restore should succeed");

        let restored_scene = runtime.render_scene();
        let restored_trail = restored_scene
            .trails
            .iter()
            .find(|trail| trail.source_body_id == tracer)
            .expect("restored trail should exist");
        assert_eq!(
            restored_trail.samples_m.len(),
            super::TRAIL_HISTORY_MAX_SAMPLES
        );
        let restored_last_x = restored_trail
            .samples_m
            .last()
            .expect("restored trail should have a last sample")
            .x;
        assert!(restored_last_x < 1_000.0);
    }

    #[test]
    fn scene_revision_changes_when_tracer_or_light_families_change() {
        let mut runtime = new_runtime();
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("planet-a".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn planet should succeed");

        let base_revision = runtime.render_scene().scene_revision;
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("tracer-a".into()),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        source_mass_kg: 0.0,
                        radius_m: 10.0,
                        position_m: Vector3d {
                            x: 10.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn tracer should succeed");
        let tracer_revision = runtime.render_scene().scene_revision;
        assert_ne!(tracer_revision, base_revision);

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("star-a".into()),
                        body_class: BodyClass::Star,
                        mass_kg: 1.989e30,
                        source_mass_kg: 1.989e30,
                        radius_m: 696_000_000.0,
                        position_m: Vector3d {
                            x: -10.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                3,
            )
            .expect("spawn star should succeed");
        let light_revision = runtime.render_scene().scene_revision;
        assert_ne!(light_revision, tracer_revision);
    }

    #[test]
    fn extract_render_scene_uses_deterministic_set_digest_for_multi_package_manifests() {
        let mut runtime = new_runtime();
        let scenario_digest = digest(0x51);
        let catalog_digest = digest(0x52);
        let scenario_id = package_id_for(&PackageKind::Scenario, &scenario_digest);
        let catalog_id = package_id_for(&PackageKind::CatalogPack, &catalog_digest);

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-gamma",
                    vec![
                        package_locator(
                            PackageKind::Scenario,
                            semver(1, 0, 0),
                            scenario_digest.clone(),
                            true,
                        ),
                        package_locator(
                            PackageKind::CatalogPack,
                            semver(1, 0, 0),
                            catalog_digest.clone(),
                            true,
                        ),
                    ],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![
                    stored_package(
                        PackageKind::Scenario,
                        semver(1, 0, 0),
                        scenario_digest,
                        "cache://pkg-scenario-gamma-v100",
                    ),
                    stored_package(
                        PackageKind::CatalogPack,
                        semver(1, 0, 0),
                        catalog_digest,
                        "cache://pkg-catalog-gamma-v100",
                    ),
                ]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: scenario_id,
            })
            .expect("scenario mount should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: catalog_id,
            })
            .expect("catalog mount should succeed");

        let provenance = runtime
            .render_scene()
            .provenance
            .expect("provenance should be present");

        let package_digest = provenance
            .package_digest
            .expect("set digest should be present for multiple mounted packages");
        assert_eq!(package_digest.algorithm, "mounted-set/v1".to_owned());
        assert!(!package_digest.value.is_empty());
    }

    #[test]
    fn scene_revision_changes_when_manifest_provenance_changes() {
        let mut runtime = new_runtime();
        let initial_revision = runtime.render_scene().scene_revision;
        let package_digest = digest(0x61);
        let package_id = package_id_for(&PackageKind::Scenario, &package_digest);
        let mut manifest = manifest(
            "manifest-delta",
            vec![package_locator(
                PackageKind::Scenario,
                semver(1, 0, 0),
                package_digest.clone(),
                true,
            )],
        );
        manifest.manifest_digest = Some(digest(0x62));

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 0, 0),
                    package_digest,
                    "cache://pkg-scenario-delta-v100",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand { package_id })
            .expect("mount should succeed");

        assert_ne!(runtime.render_scene().scene_revision, initial_revision);
    }

    #[test]
    fn scene_revision_changes_when_manifest_channel_or_version_changes() {
        let mut runtime = new_runtime();
        let package_digest = digest(0x71);
        let package_id = package_id_for(&PackageKind::Scenario, &package_digest);

        let mut manifest_alpha = manifest(
            "manifest-shared",
            vec![package_locator(
                PackageKind::Scenario,
                semver(1, 0, 0),
                package_digest.clone(),
                true,
            )],
        );
        manifest_alpha.channel = "stable".to_owned();

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest_alpha,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 0, 0),
                    package_digest.clone(),
                    "cache://pkg-scenario-shared-v100",
                )]),
            })
            .expect("first manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed");
        let initial_revision = runtime.render_scene().scene_revision;

        let mut manifest_beta = manifest(
            "manifest-shared",
            vec![package_locator(
                PackageKind::Scenario,
                semver(1, 0, 1),
                package_digest.clone(),
                true,
            )],
        );
        manifest_beta.channel = "beta".to_owned();
        manifest_beta.manifest_version = semver(1, 0, 1);

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest_beta,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 0, 1),
                    package_digest,
                    "cache://pkg-scenario-shared-v100",
                )]),
            })
            .expect("second manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand { package_id })
            .expect("remount should succeed");

        assert_ne!(runtime.render_scene().scene_revision, initial_revision);
    }

    #[test]
    fn major_body_orbit_telemetry_stays_within_legacy_thresholds() {
        let metrics = run_major_body_telemetry(900.0, 32);

        const RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX: f64 = 1.0e-6;
        const BARYCENTER_DRIFT_M_MAX: f64 = 50.0;
        const BARYCENTER_VELOCITY_DRIFT_MPS_MAX: f64 = 1.0e-3;
        const ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;
        const BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX: f64 = 10.0;
        const BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX: f64 = 1.0e-3;
        const MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;

        assert!(
            metrics.relative_angular_momentum_drift <= RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX,
            "relative_angular_momentum_drift={} exceeded {}",
            metrics.relative_angular_momentum_drift,
            RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX
        );
        assert!(
            metrics.barycenter_drift_m <= BARYCENTER_DRIFT_M_MAX,
            "barycenter_drift_m={} exceeded {}",
            metrics.barycenter_drift_m,
            BARYCENTER_DRIFT_M_MAX
        );
        assert!(
            metrics.barycenter_velocity_drift_mps <= BARYCENTER_VELOCITY_DRIFT_MPS_MAX,
            "barycenter_velocity_drift_mps={} exceeded {}",
            metrics.barycenter_velocity_drift_mps,
            BARYCENTER_VELOCITY_DRIFT_MPS_MAX
        );
        assert!(
            metrics.angular_momentum_fine_baseline_error_ratio
                <= ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX,
            "angular_momentum_fine_baseline_error_ratio={} exceeded {}",
            metrics.angular_momentum_fine_baseline_error_ratio,
            ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX
        );
        assert!(
            metrics.barycenter_fine_baseline_distance_error_m
                <= BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX,
            "barycenter_fine_baseline_distance_error_m={} exceeded {}",
            metrics.barycenter_fine_baseline_distance_error_m,
            BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX
        );
        assert!(
            metrics.barycenter_fine_baseline_velocity_error_mps
                <= BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX,
            "barycenter_fine_baseline_velocity_error_mps={} exceeded {}",
            metrics.barycenter_fine_baseline_velocity_error_mps,
            BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX
        );
        assert!(
            metrics.moon_earth_fine_baseline_error_ratio
                <= MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX,
            "moon_earth_fine_baseline_error_ratio={} exceeded {}",
            metrics.moon_earth_fine_baseline_error_ratio,
            MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX
        );
        assert!(
            metrics.absolute_angular_momentum_drift_kg_m2ps.is_finite(),
            "absolute_angular_momentum_drift_kg_m2ps should stay finite"
        );
    }

    fn checkpoint_id_from_events(events: &[RuntimeEvent]) -> CheckpointId {
        events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.checkpoint_id.clone())
                }
                _ => None,
            })
            .expect("checkpoint event should exist")
    }

    fn branch_id_from_events(events: &[RuntimeEvent]) -> BranchId {
        events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::BranchCreated(descriptor)) => {
                    Some(descriptor.branch_id.clone())
                }
                _ => None,
            })
            .expect("branch event should exist")
    }

    fn advance_staged_for_test(runtime: &mut WorldRuntime, delta: f64, recorded: i64) {
        let prior = runtime.query_advance().map_or(0, |op| op.generation);
        let op = runtime
            .begin_advance(
                [prior + 1, 0],
                prior,
                runtime.snapshot_revision() + 1,
                delta,
                recorded,
            )
            .expect("begin advance")
            .clone();
        let mut completed = 0;
        loop {
            let progress = runtime
                .continue_advance(op.generation, completed)
                .expect("advance chunk")
                .0
                .clone();
            if progress.state == super::AdvanceState::Committed {
                break;
            }
            completed = progress.completed_steps;
        }
    }

    pub(super) fn new_runtime() -> WorldRuntime {
        new_runtime_with_scenario("sol-system")
    }

    pub(super) fn new_runtime_with_scenario(scenario_id: &str) -> WorldRuntime {
        WorldRuntime::new(
            ScenarioId(scenario_id.into()),
            BranchId("main".into()),
            RuntimeConfig {
                physics: PhysicsPolicy {
                    solver_backend: SolverBackend::ReferenceScalar,
                    integrator: IntegratorKind::LeapfrogKickDriftKick,
                    collision_model: CollisionModel::None,
                    max_substep_seconds: 1.0,
                },
                timeline_semantics: TimelineSemantics::BranchedSandbox,
                live_updates_enabled: false,
            },
            HardwareProfile::offline_reference(),
            0,
        )
    }

    fn semver(major: u32, minor: u32, patch: u32) -> SemVer {
        SemVer::new(major, minor, patch)
    }

    fn digest(fill: u8) -> Digest {
        Digest {
            algorithm: "sha256".to_owned(),
            value: vec![fill; 32],
        }
    }

    fn compatibility_target() -> CompatibilityTarget {
        CompatibilityTarget {
            runtime_contract: semver(1, 0, 0),
            schema_version: "schema.v1".to_owned(),
            capabilities: BTreeSet::from(["render.3d".to_owned(), "gravity.nbody".to_owned()]),
            platform: "android-arm64".to_owned(),
        }
    }

    fn package_locator(
        kind: PackageKind,
        package_version: SemVer,
        digest: Digest,
        required: bool,
    ) -> PackageLocator {
        let package_id = package_id_for(&kind, &digest);
        PackageLocator {
            package_id: package_id.clone(),
            kind,
            package_version,
            schema_version: "schema.v1".to_owned(),
            digest,
            relative_uri: format!("packages/{package_id}.zip"),
            uncompressed_size_bytes: 1_024,
            required,
            compatibility: PackageCompatibility {
                runtime_contract_min: semver(1, 0, 0),
                runtime_contract_max: semver(1, 2, 0),
                required_capabilities: BTreeSet::from(["gravity.nbody".to_owned()]),
                supported_platforms: BTreeSet::from(["android-arm64".to_owned()]),
            },
        }
    }

    fn stored_package(
        kind: PackageKind,
        package_version: SemVer,
        digest: Digest,
        local_store_uri: &str,
    ) -> StoredPackage {
        let package_id = package_id_for(&kind, &digest);
        StoredPackage {
            package_id,
            kind,
            digest,
            package_version,
            schema_version: "schema.v1".to_owned(),
            uncompressed_size_bytes: 1_024,
            local_store_uri: local_store_uri.to_owned(),
        }
    }

    fn manifest(manifest_id: &str, packages: Vec<PackageLocator>) -> UpdateManifest {
        UpdateManifest {
            manifest_id: manifest_id.to_owned(),
            manifest_version: semver(1, 0, 0),
            channel: "stable".to_owned(),
            packages,
            manifest_digest: None,
            full_snapshot: true,
            supersedes_manifest_ids: Vec::new(),
        }
    }

    fn fetched_map(packages: Vec<StoredPackage>) -> BTreeMap<String, StoredPackage> {
        packages
            .into_iter()
            .map(|package| (package.package_id.clone(), package))
            .collect()
    }

    fn package_id_for(kind: &PackageKind, digest: &Digest) -> String {
        digest.content_id(kind)
    }

    fn spawn_two_body_system(runtime: &mut WorldRuntime) {
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("primary".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        source_mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d {
                            x: -2.0e7,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("primary spawn should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("secondary".into()),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.348e22,
                        source_mass_kg: 7.348e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 2.0e7,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("secondary spawn should succeed");
    }

    fn body_separation_x(snapshot: &super::WorldSnapshot) -> f64 {
        let primary = BodyId("primary".into());
        let secondary = BodyId("secondary".into());
        let left = snapshot
            .bodies
            .iter()
            .find(|body| body.body_id == primary)
            .expect("primary should exist");
        let right = snapshot
            .bodies
            .iter()
            .find(|body| body.body_id == secondary)
            .expect("secondary should exist");
        (right.position_m.x - left.position_m.x).abs()
    }

    fn body_position(snapshot: &super::WorldSnapshot, body_id: &BodyId) -> Vector3d {
        let body = snapshot
            .bodies
            .iter()
            .find(|body| body.body_id == *body_id)
            .expect("body should exist");
        body.position_m
    }

    fn relative_position(from: &Vector3d, to: &Vector3d) -> Vector3d {
        Vector3d {
            x: from.x - to.x,
            y: from.y - to.y,
            z: from.z - to.z,
        }
    }

    fn displacement_magnitude(a: &Vector3d, b: &Vector3d) -> f64 {
        let dx = a.x - b.x;
        let dy = a.y - b.y;
        let dz = a.z - b.z;
        (dx * dx + dy * dy + dz * dz).sqrt()
    }

    struct TelemetryMetrics {
        relative_energy_drift: f64,
        absolute_angular_momentum_drift_kg_m2ps: f64,
        relative_angular_momentum_drift: f64,
        barycenter_drift_m: f64,
        barycenter_velocity_drift_mps: f64,
        angular_momentum_fine_baseline_error_ratio: f64,
        barycenter_fine_baseline_distance_error_m: f64,
        barycenter_fine_baseline_velocity_error_mps: f64,
        moon_earth_fine_baseline_error_ratio: f64,
    }

    fn run_major_body_telemetry(step_seconds: f64, steps: usize) -> TelemetryMetrics {
        let coarse = propagate_major_bodies(step_seconds, steps);
        let fine_step = step_seconds / 4.0;
        let fine = propagate_major_bodies(fine_step, steps * 4);
        let elapsed_seconds = step_seconds * steps as f64;
        let total_mass_kg = total_mass_kg(&coarse.initial_snapshot);
        let initial_angular_momentum =
            vec_magnitude(coarse.initial_invariants.angular_momentum_kg_m2ps);
        let coarse_final_angular_momentum =
            vec_magnitude(coarse.final_invariants.angular_momentum_kg_m2ps);
        let fine_final_angular_momentum =
            vec_magnitude(fine.final_invariants.angular_momentum_kg_m2ps);
        let initial_barycenter_velocity =
            barycenter_velocity_mps(&coarse.initial_invariants, total_mass_kg);
        let coarse_final_barycenter_velocity =
            barycenter_velocity_mps(&coarse.final_invariants, total_mass_kg);
        let fine_final_barycenter_velocity =
            barycenter_velocity_mps(&fine.final_invariants, total_mass_kg);
        let expected_final_barycenter = expected_barycenter_after_seconds(
            &coarse.initial_invariants,
            total_mass_kg,
            elapsed_seconds,
        );

        let relative_energy_drift = drift(
            coarse.initial_invariants.total_energy_j,
            coarse.final_invariants.total_energy_j,
        );
        let relative_angular_momentum_drift =
            drift(initial_angular_momentum, coarse_final_angular_momentum);
        let absolute_angular_momentum_drift_kg_m2ps =
            (coarse_final_angular_momentum - initial_angular_momentum).abs();
        let barycenter_velocity_drift_mps = displacement_magnitude(
            &coarse_final_barycenter_velocity,
            &initial_barycenter_velocity,
        );
        let angular_momentum_fine_baseline_error_ratio = if fine_final_angular_momentum > 0.0 {
            (coarse_final_angular_momentum - fine_final_angular_momentum).abs()
                / fine_final_angular_momentum
        } else {
            0.0
        };

        let barycenter_drift_m =
            displacement_magnitude(&expected_final_barycenter, &coarse.final_barycenter);
        let barycenter_fine_baseline_distance_error_m =
            displacement_magnitude(&coarse.final_barycenter, &fine.final_barycenter);
        let barycenter_fine_baseline_velocity_error_mps = displacement_magnitude(
            &coarse_final_barycenter_velocity,
            &fine_final_barycenter_velocity,
        );

        let moon_earth_fine_baseline_error_ratio = {
            let coarse_distance = moon_earth_distance_au(&coarse.final_snapshot);
            let fine_distance = moon_earth_distance_au(&fine.final_snapshot);
            if fine_distance > 0.0 {
                (coarse_distance - fine_distance).abs() / fine_distance
            } else {
                0.0
            }
        };

        TelemetryMetrics {
            relative_energy_drift,
            absolute_angular_momentum_drift_kg_m2ps,
            relative_angular_momentum_drift,
            barycenter_drift_m,
            barycenter_velocity_drift_mps,
            angular_momentum_fine_baseline_error_ratio,
            barycenter_fine_baseline_distance_error_m,
            barycenter_fine_baseline_velocity_error_mps,
            moon_earth_fine_baseline_error_ratio,
        }
    }

    struct PropagationResult {
        initial_snapshot: WorldSnapshot,
        final_snapshot: WorldSnapshot,
        initial_invariants: PhysicsInvariants,
        final_invariants: PhysicsInvariants,
        final_barycenter: Vector3d,
    }

    fn propagate_major_bodies(step_seconds: f64, steps: usize) -> PropagationResult {
        let mut runtime = new_runtime();
        runtime.config.physics.max_substep_seconds = step_seconds.min(60.0);
        seed_major_bodies(&mut runtime);

        let initial_snapshot = runtime.snapshot();
        let initial_invariants = compute_world_invariants(&initial_snapshot.bodies);

        for i in 0..steps {
            runtime
                .apply_command(
                    WorldCommand::AdvanceEpoch {
                        delta_seconds: step_seconds,
                    },
                    (i as i64) + 1_000,
                )
                .expect("advance epoch should succeed");
        }

        let final_snapshot = runtime.snapshot();
        let final_invariants = compute_world_invariants(&final_snapshot.bodies);
        let final_barycenter = final_invariants.barycenter_m;

        PropagationResult {
            initial_snapshot,
            final_snapshot,
            initial_invariants,
            final_invariants,
            final_barycenter,
        }
    }

    pub(super) fn seed_major_bodies(runtime: &mut WorldRuntime) {
        use solarlab_data::canonical_startup_seed;

        let seed = canonical_startup_seed();
        let major_bodies: Vec<BodyState> = seed
            .bodies
            .into_iter()
            .filter(|body| {
                !matches!(body.body_class, BodyClass::SmallBody | BodyClass::Comet)
            })
            .map(|body| {
                let source_mass_kg =
                    BodyState::default_source_mass_kg(&body.body_class, body.mass_kg);
                BodyState {
                    body_id: BodyId(body.body_id),
                    body_class: body.body_class,
                    mass_kg: body.mass_kg,
                    source_mass_kg,
                    radius_m: body.radius_m,
                    position_m: body.position_m,
                    velocity_mps: body.velocity_mps,
                }
            })
            .collect();

        let branch = runtime.active_branch_mut();
        branch.world.bodies = major_bodies;
        branch.world.invariants = compute_world_invariants(&branch.world.bodies);
        branch.world.trail_history_by_body.clear();
        record_trail_samples_from_bodies(
            &branch.world.bodies,
            &mut branch.world.trail_history_by_body,
            branch.world.epoch_seconds,
        );
    }

    fn drift(initial: f64, final_value: f64) -> f64 {
        if initial.abs() <= f64::EPSILON {
            0.0
        } else {
            (final_value - initial).abs() / initial.abs()
        }
    }

    fn total_mass_kg(snapshot: &WorldSnapshot) -> f64 {
        snapshot
            .bodies
            .iter()
            .map(|body| body.source_mass_kg())
            .sum()
    }

    fn expected_barycenter_after_seconds(
        invariants: &PhysicsInvariants,
        total_mass_kg: f64,
        elapsed_seconds: f64,
    ) -> Vector3d {
        if total_mass_kg <= 0.0 {
            return invariants.barycenter_m;
        }

        Vector3d {
            x: invariants.barycenter_m.x
                + (invariants.linear_momentum_kg_mps.x / total_mass_kg) * elapsed_seconds,
            y: invariants.barycenter_m.y
                + (invariants.linear_momentum_kg_mps.y / total_mass_kg) * elapsed_seconds,
            z: invariants.barycenter_m.z
                + (invariants.linear_momentum_kg_mps.z / total_mass_kg) * elapsed_seconds,
        }
    }

    fn barycenter_velocity_mps(invariants: &PhysicsInvariants, total_mass_kg: f64) -> Vector3d {
        if total_mass_kg <= 0.0 {
            return Vector3d::default();
        }

        Vector3d {
            x: invariants.linear_momentum_kg_mps.x / total_mass_kg,
            y: invariants.linear_momentum_kg_mps.y / total_mass_kg,
            z: invariants.linear_momentum_kg_mps.z / total_mass_kg,
        }
    }

    fn moon_earth_distance_au(snapshot: &WorldSnapshot) -> f64 {
        const AU_M: f64 = 1.495_978_707e11;
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());

        let earth_pos = body_position(snapshot, &earth);
        let moon_pos = body_position(snapshot, &moon);
        displacement_magnitude(&earth_pos, &moon_pos) / AU_M
    }
