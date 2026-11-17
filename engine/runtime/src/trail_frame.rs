//! Presentation coordinates for the Earth-centred laboratory. Authoritative archive
//! positions remain barycentric; only the scene's copied trail vertices are rebased.
use super::{OrbitArchive, Vector3d, WorldSnapshot};

pub(super) fn earth_relative_samples(
    snapshot: &WorldSnapshot,
    archive: &OrbitArchive,
) -> Option<Vec<Vector3d>> {
    if snapshot.scenario_id.0 != solarlab_data::EARTH_MOON_LAB_ID {
        return None;
    }
    let current_earth = snapshot
        .bodies
        .iter()
        .find(|body| body.body_id.0 == "earth")?
        .position_m;
    archive
        .samples
        .iter()
        .map(|sample| {
            let mut references = snapshot
                .orbit_archives
                .iter()
                .filter(|reference| {
                    reference.source_body_id.0 == "earth" && reference.family == archive.family
                })
                .flat_map(|reference| &reference.samples)
                .filter(|reference| reference.epoch_seconds == sample.epoch_seconds);
            let earth_at_sample = references.next()?.position_m;
            // Edits/checkpoints can share an epoch but record different Earth positions.
            // Without a unique reference the archive stays entirely in its original
            // frame; never extrapolate Earth or mix rebased and absolute trail points.
            if references.any(|reference| reference.position_m != earth_at_sample) {
                return None;
            }
            let position = Vector3d {
                x: current_earth.x + (sample.position_m.x - earth_at_sample.x),
                y: current_earth.y + (sample.position_m.y - earth_at_sample.y),
                z: current_earth.z + (sample.position_m.z - earth_at_sample.z),
            };
            (position.x.is_finite() && position.y.is_finite() && position.z.is_finite())
                .then_some(position)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{extract_render_scene, BodyClass, BodyId, BodyState, WorldCommand};
    use solarlab_history::{OrbitArchiveFamily, OrbitArchiveQuery, OrbitSample};

    fn point(x: f64, y: f64, z: f64) -> Vector3d {
        Vector3d { x, y, z }
    }

    fn add(a: Vector3d, b: Vector3d) -> Vector3d {
        point(a.x + b.x, a.y + b.y, a.z + b.z)
    }

    fn subtract(a: Vector3d, b: Vector3d) -> Vector3d {
        point(a.x - b.x, a.y - b.y, a.z - b.z)
    }

    fn fixture() -> WorldSnapshot {
        let mut snapshot =
            crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID).snapshot();
        snapshot.epoch_seconds = 20.0;
        for (id, class, current, past) in [
            (
                "earth",
                BodyClass::Planet,
                point(200.0, 600.0, 800.0),
                point(100.0, 200.0, 300.0),
            ),
            (
                "moon",
                BodyClass::Moon,
                point(220.0, 640.0, 860.0),
                point(130.0, 220.0, 310.0),
            ),
        ] {
            snapshot.bodies.push(BodyState {
                body_id: BodyId(id.into()),
                body_class: class,
                mass_kg: 1.0,
                source_mass_kg: 1.0,
                radius_m: 1.0,
                position_m: current,
                velocity_mps: Vector3d::default(),
            });
            for family in [
                OrbitArchiveFamily::Trajectory,
                OrbitArchiveFamily::HistoricalOrbit,
                OrbitArchiveFamily::Prediction,
            ] {
                snapshot.orbit_archives.push(OrbitArchive {
                    archive_id: format!("{family:?}:{id}"),
                    source_body_id: BodyId(id.into()),
                    family,
                    max_samples: 2,
                    samples: vec![
                        OrbitSample {
                            epoch_seconds: 10.0,
                            position_m: past,
                        },
                        OrbitSample {
                            epoch_seconds: 20.0,
                            position_m: current,
                        },
                    ],
                });
            }
        }
        snapshot
    }

    #[test]
    fn lab_trails_remove_common_translation_at_each_sample_and_keep_current_endpoints() {
        let snapshot = fixture();
        let mut moving = snapshot.clone();
        let current_translation = point(9.0e10, -7.0e10, 6.0e10);
        for body in &mut moving.bodies {
            body.position_m = add(body.position_m, current_translation);
        }
        for archive in &mut moving.orbit_archives {
            for sample in &mut archive.samples {
                let translation = if sample.epoch_seconds == 10.0 {
                    point(-1.0e10, 4.0e10, 1.0e10)
                } else {
                    current_translation
                };
                sample.position_m = add(sample.position_m, translation);
            }
        }
        for (original, translated) in snapshot.orbit_archives.iter().zip(&moving.orbit_archives) {
            let baseline = earth_relative_samples(&snapshot, original).unwrap();
            let rebased = earth_relative_samples(&moving, translated).unwrap();
            assert_eq!(
                rebased
                    .iter()
                    .map(|p| subtract(*p, current_translation))
                    .collect::<Vec<_>>(),
                baseline,
                "family {:?} should discard the shared motion at each epoch",
                original.family,
            );
            let current_body = moving
                .bodies
                .iter()
                .find(|body| body.body_id == original.source_body_id)
                .unwrap();
            assert_eq!(rebased.last(), Some(&current_body.position_m));
            if original.source_body_id.0 == "moon" {
                assert_eq!(baseline[0], point(230.0, 620.0, 810.0));
            }
        }
    }

    #[test]
    fn incomplete_or_ambiguous_references_keep_the_entire_trail_in_its_original_frame() {
        for failure in [
            "missing epoch",
            "ambiguous epoch",
            "missing earth",
            "other scenario",
        ] {
            let mut snapshot = fixture();
            let earth = snapshot
                .orbit_archives
                .iter_mut()
                .find(|archive| archive.archive_id == "Trajectory:earth")
                .unwrap();
            match failure {
                "missing epoch" => {
                    earth.samples.remove(0);
                }
                "ambiguous epoch" => earth.samples.push(OrbitSample {
                    epoch_seconds: 10.0,
                    position_m: point(111.0, 222.0, 333.0),
                }),
                "missing earth" => snapshot.bodies.retain(|body| body.body_id.0 != "earth"),
                _ => snapshot.scenario_id.0 = "sol-system".into(),
            }
            let archive = snapshot
                .orbit_archives
                .iter()
                .find(|archive| archive.archive_id == "Trajectory:moon")
                .unwrap();
            assert_eq!(
                earth_relative_samples(&snapshot, archive),
                None,
                "{failure}"
            );
            let scene = extract_render_scene(&snapshot);
            let trail = scene
                .trails
                .iter()
                .find(|trail| trail.trail_id == archive.archive_id)
                .unwrap();
            assert_eq!(
                trail.samples_m,
                archive
                    .samples
                    .iter()
                    .map(|sample| sample.position_m)
                    .collect::<Vec<_>>(),
                "{failure}"
            );
        }
    }

    #[test]
    fn real_lab_archives_rebase_all_families_without_changing_world_or_raw_history() {
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        runtime.config.physics.max_substep_seconds = 60.0;
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: None,
                },
                2,
            )
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 60.0,
                },
                3,
            )
            .unwrap();
        let before = runtime.snapshot();
        let archives = runtime.orbit_archives(&OrbitArchiveQuery::default());
        let snapshot = runtime.snapshot_with_orbit_archives(archives.clone());
        let scene = runtime.render_scene();
        let current_earth = before
            .bodies
            .iter()
            .find(|body| body.body_id.0 == "earth")
            .unwrap()
            .position_m;
        let current_moon = before
            .bodies
            .iter()
            .find(|body| body.body_id.0 == "moon")
            .unwrap()
            .position_m;
        let moon_archives = archives
            .iter()
            .filter(|archive| archive.source_body_id.0 == "moon")
            .collect::<Vec<_>>();
        assert_eq!(moon_archives.len(), 3);
        for archive in moon_archives {
            let trail = scene
                .trails
                .iter()
                .find(|trail| trail.trail_id == archive.archive_id)
                .unwrap();
            let earth = archives
                .iter()
                .find(|reference| {
                    reference.source_body_id.0 == "earth" && reference.family == archive.family
                })
                .unwrap();
            for (sample, displayed) in archive.samples.iter().zip(&trail.samples_m) {
                let earth_sample = earth
                    .samples
                    .iter()
                    .find(|reference| reference.epoch_seconds == sample.epoch_seconds)
                    .unwrap();
                assert_eq!(
                    *displayed,
                    add(
                        current_earth,
                        subtract(sample.position_m, earth_sample.position_m)
                    )
                );
            }
            if archive.family != OrbitArchiveFamily::Prediction {
                assert_eq!(trail.samples_m.last(), Some(&current_moon));
            }
        }
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(snapshot.orbit_archives, archives);
        assert_eq!(
            runtime.orbit_archives(&OrbitArchiveQuery::default()),
            archives
        );
        assert_eq!(scene.provenance, crate::scene_provenance(&snapshot));
        for body in &scene.bodies {
            assert_eq!(
                body.position_m,
                before
                    .bodies
                    .iter()
                    .find(|source| source.body_id == body.body_id)
                    .unwrap()
                    .position_m
            );
        }
    }
}
