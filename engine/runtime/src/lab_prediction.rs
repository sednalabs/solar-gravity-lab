//! Bounded sampling for the lab's display prediction, separate from solver timesteps.
//!
//! A one-hour sample gap aliases a low Earth orbit into chords through the planet.
//! If an added body's local gravity/motion timescale would have fewer than 64
//! samples per revolution, all displayed prediction archives share 96 epochs over
//! at most 1.5 of that timescale, capped by the usual 12-hour horizon. Sharing the
//! shorter horizon keeps Earth references exactly aligned without extra prediction
//! passes. Sun/Earth/Moon alone, public archive queries and other scenarios retain
//! their original settings. This is a local sampling bound, not an orbital fit or
//! an adaptive integration policy; authority and raw trajectory history do not change.
use super::{BodyState, OrbitArchiveQuery};

const G: f64 = 6.67430e-11;
const SAMPLES_PER_LOCAL_PERIOD: f64 = 64.0;
const DISPLAY_PREDICTION_SAMPLES: usize = 96;

pub(super) fn configure_display_query(
    query: &mut OrbitArchiveQuery,
    scenario_id: &str,
    bodies: &[BodyState],
) {
    if scenario_id != solarlab_data::EARTH_MOON_LAB_ID || !query.include_prediction {
        return;
    }
    let original_horizon = query.prediction_step_seconds * query.prediction_sample_count as f64;
    if !original_horizon.is_finite() || original_horizon <= 0.0 {
        return;
    }
    let Some(period) = shortest_added_body_local_period(bodies) else {
        return;
    };
    if period >= query.prediction_step_seconds * SAMPLES_PER_LOCAL_PERIOD {
        return;
    }
    let horizon = original_horizon.min(period * 1.5);
    query.prediction_step_seconds = horizon / DISPLAY_PREDICTION_SAMPLES as f64;
    query.prediction_sample_count = DISPLAY_PREDICTION_SAMPLES;
}

pub(super) fn shortest_added_body_local_period(bodies: &[BodyState]) -> Option<f64> {
    let mut shortest = f64::INFINITY;
    for body in bodies
        .iter()
        .filter(|body| !matches!(body.body_id.0.as_str(), "sun" | "earth" | "moon"))
    {
        for source in bodies
            .iter()
            .filter(|source| source.body_id != body.body_id && source.source_mass_kg > 0.0)
        {
            let distance = (body.position_m.x - source.position_m.x)
                .hypot(body.position_m.y - source.position_m.y)
                .hypot(body.position_m.z - source.position_m.z);
            let mu = G * (source.source_mass_kg + body.source_mass_kg);
            if !distance.is_finite() || distance <= 0.0 || !mu.is_finite() || mu <= 0.0 {
                continue;
            }
            let gravity_period = std::f64::consts::TAU * distance * (distance / mu).sqrt();
            let relative_speed = (body.velocity_mps.x - source.velocity_mps.x)
                .hypot(body.velocity_mps.y - source.velocity_mps.y)
                .hypot(body.velocity_mps.z - source.velocity_mps.z);
            // The motion bound also samples fast flybys densely; radial speed makes
            // this conservative rather than assuming every added body is circular.
            let motion_period = std::f64::consts::TAU * (distance / relative_speed);
            let local_period = gravity_period.min(motion_period);
            if local_period.is_finite() && local_period > 0.0 {
                shortest = shortest.min(local_period);
            }
        }
    }
    shortest.is_finite().then_some(shortest)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{BodyClass, BodyId, Vector3d, WorldCommand, WorldRuntime};
    use solarlab_history::OrbitArchiveFamily;
    use solarlab_physics::checked_substep_count;

    fn lab() -> WorldRuntime {
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        // A fixed fine policy isolates the display-sampling regression from the
        // host's solver selection and the lab's separately tested step ceiling.
        runtime.config.physics.max_substep_seconds = 4.0;
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        runtime
    }

    fn add_probe(runtime: &mut WorldRuntime, altitude: f64) -> f64 {
        let earth = runtime
            .snapshot()
            .bodies
            .into_iter()
            .find(|b| b.body_id.0 == "earth")
            .unwrap();
        let radius = earth.radius_m + altitude;
        let speed = (G * earth.source_mass_kg / radius).sqrt();
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("probe".into()),
                        body_class: BodyClass::Spacecraft,
                        mass_kg: 1000.0,
                        source_mass_kg: 0.0,
                        radius_m: 2.0,
                        position_m: Vector3d {
                            x: earth.position_m.x + radius,
                            ..earth.position_m
                        },
                        velocity_mps: Vector3d {
                            y: earth.velocity_mps.y + speed,
                            ..earth.velocity_mps
                        },
                    },
                },
                2,
            )
            .unwrap();
        radius
    }

    fn difference(a: Vector3d, b: Vector3d) -> Vector3d {
        Vector3d {
            x: a.x - b.x,
            y: a.y - b.y,
            z: a.z - b.z,
        }
    }
    fn norm(v: Vector3d) -> f64 {
        v.x.hypot(v.y).hypot(v.z)
    }
    fn dot(a: Vector3d, b: Vector3d) -> f64 {
        a.x * b.x + a.y * b.y + a.z * b.z
    }
    fn chord_distance(a: Vector3d, b: Vector3d) -> f64 {
        let delta = difference(b, a);
        let t = (-dot(a, delta) / dot(delta, delta)).clamp(0.0, 1.0);
        norm(Vector3d {
            x: a.x + t * delta.x,
            y: a.y + t * delta.y,
            z: a.z + t * delta.z,
        })
    }

    #[test]
    fn unedited_lab_and_other_scenarios_keep_the_original_horizon() {
        let mut runtime = lab();
        let baseline = runtime.render_scene_orbit_query();
        assert_eq!(baseline.prediction_sample_count, 12);
        assert_eq!(baseline.prediction_step_seconds, 3600.0);
        add_probe(&mut runtime, 800_000.0);
        for scenario in ["sol-system", "showcase.earth-moon"] {
            let mut query = OrbitArchiveQuery::default();
            configure_display_query(&mut query, scenario, &runtime.snapshot().bodies);
            assert_eq!(query, OrbitArchiveQuery::default());
        }
    }

    #[test]
    fn close_probe_query_is_bounded_by_local_motion_and_does_not_extend_the_horizon() {
        for altitude in [800_000.0, 20_000_000.0, 36_000_000.0] {
            let mut runtime = lab();
            add_probe(&mut runtime, altitude);
            let query = runtime.render_scene_orbit_query();
            let period = shortest_added_body_local_period(&runtime.snapshot().bodies).unwrap();
            assert_eq!(query.prediction_sample_count, DISPLAY_PREDICTION_SAMPLES);
            assert!(query.prediction_step_seconds <= period / SAMPLES_PER_LOCAL_PERIOD);
            assert!(
                query.prediction_step_seconds * query.prediction_sample_count as f64 <= 43_200.0
            );
            let dense_steps =
                checked_substep_count(&runtime.config.physics, query.prediction_step_seconds)
                    .unwrap()
                    * query.prediction_sample_count;
            let original_steps =
                checked_substep_count(&runtime.config.physics, 3600.0).unwrap() * 12;
            // One shared pass and a capped horizon cost at most the old integration
            // budget plus one rounding substep per newly introduced sample boundary.
            assert!(dense_steps <= original_steps + DISPLAY_PREDICTION_SAMPLES);
            if altitude == 800_000.0 {
                assert!((period - 6043.401891245).abs() < 1e-6);
                assert!(dense_steps < original_steps / 3);
            }
        }
    }

    #[test]
    fn low_probe_prediction_stays_outside_earth_with_bounded_sagitta() {
        let mut runtime = lab();
        let radius = add_probe(&mut runtime, 800_000.0);
        let before = runtime.snapshot();
        let earth = before
            .bodies
            .iter()
            .find(|b| b.body_id.0 == "earth")
            .unwrap();
        let probe = before
            .bodies
            .iter()
            .find(|b| b.body_id.0 == "probe")
            .unwrap();
        let query = runtime.render_scene_orbit_query();
        let archives = runtime.orbit_archives(&query);
        let predicted_earth = archives
            .iter()
            .find(|a| a.source_body_id.0 == "earth" && a.family == OrbitArchiveFamily::Prediction)
            .unwrap();
        let predicted_probe = archives
            .iter()
            .find(|a| a.source_body_id.0 == "probe" && a.family == OrbitArchiveFamily::Prediction)
            .unwrap();
        assert_eq!(predicted_probe.samples.len(), 96);
        assert_eq!(predicted_earth.samples.len(), 96);
        assert!(predicted_probe
            .samples
            .iter()
            .zip(&predicted_earth.samples)
            .all(|(a, b)| a.epoch_seconds == b.epoch_seconds));
        let scene = runtime.render_scene();
        let trail = scene
            .trails
            .iter()
            .find(|t| t.trail_id == "prediction:probe")
            .unwrap();
        let points: Vec<_> = std::iter::once(difference(probe.position_m, earth.position_m))
            .chain(
                trail
                    .samples_m
                    .iter()
                    .map(|point| difference(*point, earth.position_m)),
            )
            .collect();
        let sagitta_limit =
            radius * (1.0 - (std::f64::consts::PI / SAMPLES_PER_LOCAL_PERIOD).cos()) * 1.01;
        for pair in points.windows(2) {
            let radial_a = norm(pair[0]);
            let radial_b = norm(pair[1]);
            assert!((radial_a - radius).abs() < radius * 0.0001);
            assert!((radial_b - radius).abs() < radius * 0.0001);
            let chord = chord_distance(pair[0], pair[1]);
            assert!(
                chord > earth.radius_m,
                "predicted chord must not cut through Earth"
            );
            assert!((radial_a + radial_b) * 0.5 - chord <= sagitta_limit);
        }
        // The unchanged public archive query demonstrates the former visual aliasing:
        // orbital points are valid, but each one-hour chord crosses the solid planet.
        let original = runtime.orbit_archives(&OrbitArchiveQuery::default());
        let old_earth = original
            .iter()
            .find(|a| a.source_body_id.0 == "earth" && a.family == OrbitArchiveFamily::Prediction)
            .unwrap();
        let old_probe = original
            .iter()
            .find(|a| a.source_body_id.0 == "probe" && a.family == OrbitArchiveFamily::Prediction)
            .unwrap();
        assert_eq!(old_probe.samples.len(), 12);
        let old_points: Vec<_> = old_probe
            .samples
            .iter()
            .zip(&old_earth.samples)
            .map(|(p, e)| difference(p.position_m, e.position_m))
            .collect();
        assert!(old_points
            .windows(2)
            .all(|p| chord_distance(p[0], p[1]) < earth.radius_m));
        assert_eq!(runtime.snapshot(), before);
    }
}
