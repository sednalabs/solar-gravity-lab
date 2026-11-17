use serde_json::json;
use solarlab_data::{
    earth_moon_lab_bodies, EARTH_MOON_LAB_DATASET_ID, EARTH_MOON_LAB_DIGEST,
    EARTH_MOON_LAB_MAX_SUBSTEP_SECONDS, EARTH_MOON_REFERENCE_EPOCHS,
};
use solarlab_domain::Vector3d;
use solarlab_physics::{
    advance_authoritative_scalar, CollisionModel, IntegratorKind, MassiveBodyState, PhysicsPolicy,
    SolverBackend,
};

use crate::ScenarioReport;

/// External-model error and numerical convergence are deliberately separate.
/// The Horizons reference includes physics absent from this three-body model.
pub(crate) fn run() -> ScenarioReport {
    let initial: Vec<MassiveBodyState> = earth_moon_lab_bodies()
        .into_iter()
        .map(|body| MassiveBodyState {
            mass_kg: body.mass_kg,
            position_m: body.position_m,
            velocity_mps: body.velocity_mps,
        })
        .collect();
    let mut coarse = initial.clone();
    let mut fine = initial;
    let policy = PhysicsPolicy {
        solver_backend: SolverBackend::ReferenceScalar,
        integrator: IntegratorKind::LeapfrogKickDriftKick,
        collision_model: CollisionModel::None,
        max_substep_seconds: EARTH_MOON_LAB_MAX_SUBSTEP_SECONDS,
    };
    let fine_policy = PhysicsPolicy {
        max_substep_seconds: 5.0,
        ..policy.clone()
    };
    let mut previous_seconds = 0.0;
    let mut samples = Vec::new();
    let mut passed = true;
    // Broad regression ceilings around the recorded DE441 characterisation;
    // these are not precision guarantees for edited worlds or other epochs.
    let external_position_limits = [10.0, 500.0, 5_000.0];
    let numerical_position_limits = [2.0, 10.0, 50.0];
    for (index, reference) in EARTH_MOON_REFERENCE_EPOCHS.iter().skip(1).enumerate() {
        let delta = reference.elapsed_seconds - previous_seconds;
        advance_authoritative_scalar(&policy, &mut coarse, delta);
        advance_authoritative_scalar(&fine_policy, &mut fine, delta);
        let actual_position = relative(coarse[2].position_m, coarse[1].position_m);
        let fine_position = relative(fine[2].position_m, fine[1].position_m);
        let expected_position = relative(
            vector(reference.positions_m[2]),
            vector(reference.positions_m[1]),
        );
        let actual_velocity = relative(coarse[2].velocity_mps, coarse[1].velocity_mps);
        let expected_velocity = relative(
            vector(reference.velocities_mps[2]),
            vector(reference.velocities_mps[1]),
        );
        let external_error = distance(actual_position, expected_position);
        let fine_external_error = distance(fine_position, expected_position);
        let numerical_error = distance(actual_position, fine_position);
        let velocity_error = distance(actual_velocity, expected_velocity);
        passed &= external_error <= external_position_limits[index]
            && numerical_error <= numerical_position_limits[index]
            && velocity_error <= 0.02;
        samples.push(json!({
            "elapsed_days": reference.elapsed_seconds / 86_400.0,
            "moon_relative_position_error_against_de441_m": external_error,
            "fine_step_position_error_against_de441_m": fine_external_error,
            "moon_relative_position_difference_60s_vs_5s_m": numerical_error,
            "moon_relative_velocity_error_against_de441_mps": velocity_error,
            "external_position_ceiling_m": external_position_limits[index],
            "numerical_position_ceiling_m": numerical_position_limits[index],
        }));
        previous_seconds = reference.elapsed_seconds;
    }

    ScenarioReport {
        id: "earth-moon-reference",
        family: "external scientific reference",
        description: "Compare the bundled Sun–Earth–Moon experiment against recorded JPL DE441 vectors and a finer integration of the same Newtonian model.",
        passed,
        metrics: json!({
            "dataset_id": EARTH_MOON_LAB_DATASET_ID,
            "dataset_digest": EARTH_MOON_LAB_DIGEST,
            "max_substep_seconds": policy.max_substep_seconds,
            "fine_max_substep_seconds": fine_policy.max_substep_seconds,
            "samples": samples,
        }),
        thresholds: json!({
            "elapsed_days": [1, 7, 30],
            "external_position_ceiling_m": external_position_limits,
            "numerical_position_ceiling_m": numerical_position_limits,
            "external_velocity_ceiling_mps": 0.02,
        }),
        notes: vec![
            "Starting epoch: JD 2451545.0 TDB; geometric ICRF vectors relative to the solar-system barycentre. Errors here are Moon relative to Earth.".into(),
            "After initialisation the solver propagates three Newtonian point masses. Other planets, relativity, tides, gravity harmonics and collision response are absent.".into(),
            "The fine-step comparison isolates numerical convergence within this model; the DE441 comparison also includes physical-model differences. This does not qualify arbitrary edits or close encounters.".into(),
        ],
    }
}

fn vector(values: [f64; 3]) -> Vector3d {
    Vector3d {
        x: values[0],
        y: values[1],
        z: values[2],
    }
}

fn relative(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}

fn distance(a: Vector3d, b: Vector3d) -> f64 {
    let delta = relative(a, b);
    (delta.x * delta.x + delta.y * delta.y + delta.z * delta.z).sqrt()
}

#[cfg(test)]
mod tests {
    #[test]
    fn bundled_earth_moon_reference_and_numerical_convergence_stay_within_limits() {
        let report = super::run();
        assert!(report.passed, "{}", report.metrics);
    }
}
