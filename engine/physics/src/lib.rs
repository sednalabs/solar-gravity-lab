use std::collections::BTreeSet;
use std::fs;

use solarlab_domain::Vector3d;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SolverBackend {
    ReferenceScalar,
    SimdArm64,
    SimdX64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum IntegratorKind {
    LeapfrogKickDriftKick,
    AdaptiveSymplectic,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CollisionModel {
    None,
    Merge,
    Elastic,
    Fragmentation,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsPolicy {
    pub solver_backend: SolverBackend,
    pub integrator: IntegratorKind,
    pub collision_model: CollisionModel,
    pub max_substep_seconds: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PlaybackSubstepPlan {
    pub total_seconds: f64,
    pub max_substep_seconds: f64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PhysicsInvariants {
    pub total_energy_j: f64,
    pub linear_momentum_kg_mps: Vector3d,
    pub angular_momentum_kg_m2ps: Vector3d,
    pub barycenter_m: Vector3d,
}

/// Authoritative state for a massive body participating in mutual gravity.
///
/// Massive bodies exert force on all other massive bodies and tracers.
/// Position and velocity use double-precision (f64) to maintain orbital
/// stability over long simulation durations.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MassiveBodyState {
    pub mass_kg: f64,
    pub position_m: Vector3d,
    pub velocity_mps: Vector3d,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SolverExecutionReport {
    pub requested_backend: SolverBackend,
    pub effective_backend: SolverBackend,
    pub path_id: String,
    pub fallback_reason: Option<String>,
    pub active_cpu_features: Vec<String>,
}

impl SolverExecutionReport {
    #[must_use]
    pub fn reference_scalar() -> Self {
        Self {
            requested_backend: SolverBackend::ReferenceScalar,
            effective_backend: SolverBackend::ReferenceScalar,
            path_id: "scalar.reference".to_owned(),
            fallback_reason: None,
            active_cpu_features: detect_cpu_features(),
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SolverEquivalenceMetrics {
    pub compared_components: usize,
    pub bitwise_equal_components: usize,
    pub max_abs_error: f64,
    pub max_relative_error: f64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct BackendEquivalenceReport {
    pub metrics: SolverEquivalenceMetrics,
    pub energy_relative_error: f64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct DispatchDecision {
    effective_backend: SolverBackend,
    path_id: String,
    fallback_reason: Option<String>,
}

const G_M3_PER_KG_S2: f64 = 6.67430e-11;
const MIN_DISTANCE_M2: f64 = 1.0e-12;
const MAX_SIMULATION_SUBSTEP_SECONDS: f64 = 3_600.0;
const PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK: f64 = 12.0;
const HOST_RELATIVE_PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK: f64 = 24.0;
const HOST_RELATIVE_SHORT_WINDOW_MAX_SECONDS: f64 = 86_400.0;
const HOST_RELATIVE_SHORT_WINDOW_MAX_EFFECTIVE_SUBSTEP_SECONDS: f64 = 10_800.0;
const PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS: f64 = 32_400.0;
const HIGH_SPEED_PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS: f64 = 21_600.0;
const HIGH_SPEED_PLAYBACK_THRESHOLD_SIM_SECONDS_PER_REAL_SECOND: f64 = 604_800.0;
const HOST_RELATIVE_SHORT_WINDOW_THRESHOLD_SIM_SECONDS_PER_REAL_SECOND: f64 = 2_592_000.0;

pub fn advance_authoritative_scalar(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
) -> PhysicsInvariants {
    if bodies.is_empty() || delta_seconds <= 0.0 {
        return compute_invariants(bodies);
    }

    let max_substep_seconds =
        if policy.max_substep_seconds.is_finite() && policy.max_substep_seconds > 0.0 {
            policy.max_substep_seconds
        } else {
            delta_seconds
        };
    let step_count = (delta_seconds / max_substep_seconds).ceil().max(1.0) as usize;
    let dt_seconds = delta_seconds / step_count as f64;

    for _ in 0..step_count {
        integrate_substep(bodies, dt_seconds);
    }

    compute_invariants(bodies)
}

pub fn advance_authoritative(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
) -> (PhysicsInvariants, SolverExecutionReport) {
    let active_cpu_features = detect_cpu_features();
    advance_authoritative_with_features(policy, bodies, delta_seconds, &active_cpu_features)
}

pub fn advance_authoritative_with_features(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
    active_cpu_features: &[String],
) -> (PhysicsInvariants, SolverExecutionReport) {
    let normalized_features = normalize_cpu_features(active_cpu_features);
    let decision = dispatch_solver_backend(&policy.solver_backend, &normalized_features);
    let invariants = match decision.effective_backend {
        SolverBackend::ReferenceScalar => {
            advance_authoritative_scalar(policy, bodies, delta_seconds)
        }
        SolverBackend::SimdArm64 => advance_authoritative_arm64(policy, bodies, delta_seconds),
        SolverBackend::SimdX64 => advance_authoritative_x64(policy, bodies, delta_seconds),
    };

    (
        invariants,
        SolverExecutionReport {
            requested_backend: policy.solver_backend.clone(),
            effective_backend: decision.effective_backend,
            path_id: decision.path_id,
            fallback_reason: decision.fallback_reason,
            active_cpu_features: normalized_features,
        },
    )
}

#[must_use]
pub fn detect_cpu_features() -> Vec<String> {
    if let Some(features) = override_cpu_features() {
        return features;
    }

    let mut features = BTreeSet::new();

    add_arm64_features(&mut features);
    add_x64_features(&mut features);

    features.into_iter().collect()
}

#[cfg(target_arch = "aarch64")]
fn add_arm64_features(features: &mut BTreeSet<String>) {
    // Runtime feature detection gives non-Linux ARM64 hosts a truthful baseline
    // even when /proc/cpuinfo is unavailable.
    if std::arch::is_aarch64_feature_detected!("neon") {
        features.insert("neon".to_owned());
    }

    let tokens = cpuinfo_feature_tokens();
    if has_any_token(&tokens, &["asimd", "neon"]) {
        features.insert("neon".to_owned());
    }
    if has_any_token(&tokens, &["fma", "fhm", "asimdfhm"]) {
        features.insert("fma".to_owned());
    }
    if has_any_token(&tokens, &["sve2"]) {
        features.insert("sve2".to_owned());
    }
    if has_any_token(&tokens, &["sme", "sme2"]) {
        features.insert("sme".to_owned());
    }
}

#[cfg(not(target_arch = "aarch64"))]
fn add_arm64_features(_features: &mut BTreeSet<String>) {}

#[cfg(target_arch = "x86_64")]
fn add_x64_features(features: &mut BTreeSet<String>) {
    if std::arch::is_x86_feature_detected!("sse2") {
        features.insert("sse2".to_owned());
    }
    if std::arch::is_x86_feature_detected!("avx2") {
        features.insert("avx2".to_owned());
    }
    if std::arch::is_x86_feature_detected!("fma") {
        features.insert("fma".to_owned());
    }

    let tokens = cpuinfo_feature_tokens();
    if has_any_token(&tokens, &["sse2"]) {
        features.insert("sse2".to_owned());
    }
    if has_any_token(&tokens, &["avx2"]) {
        features.insert("avx2".to_owned());
    }
    if has_any_token(&tokens, &["fma"]) {
        features.insert("fma".to_owned());
    }
}

#[cfg(not(target_arch = "x86_64"))]
fn add_x64_features(_features: &mut BTreeSet<String>) {}

#[must_use]
pub fn solver_equivalence_metrics(
    actual: &[MassiveBodyState],
    expected: &[MassiveBodyState],
) -> SolverEquivalenceMetrics {
    let mut metrics = SolverEquivalenceMetrics::default();
    let paired_len = actual.len().min(expected.len());
    for i in 0..paired_len {
        let actual_body = actual[i];
        let expected_body = expected[i];
        for (lhs, rhs) in [
            (actual_body.position_m.x, expected_body.position_m.x),
            (actual_body.position_m.y, expected_body.position_m.y),
            (actual_body.position_m.z, expected_body.position_m.z),
            (actual_body.velocity_mps.x, expected_body.velocity_mps.x),
            (actual_body.velocity_mps.y, expected_body.velocity_mps.y),
            (actual_body.velocity_mps.z, expected_body.velocity_mps.z),
        ] {
            update_equivalence_metrics(lhs, rhs, &mut metrics);
        }
    }
    metrics
}

#[must_use]
pub fn compare_arm64_kernel_to_scalar(
    policy: &PhysicsPolicy,
    initial_bodies: &[MassiveBodyState],
    delta_seconds: f64,
) -> BackendEquivalenceReport {
    let mut scalar_bodies = initial_bodies.to_vec();
    let mut arm64_bodies = initial_bodies.to_vec();

    let scalar_invariants = advance_authoritative_scalar(policy, &mut scalar_bodies, delta_seconds);
    let arm64_invariants = advance_authoritative_arm64(policy, &mut arm64_bodies, delta_seconds);
    let metrics = solver_equivalence_metrics(&arm64_bodies, &scalar_bodies);

    let energy_scale = scalar_invariants.total_energy_j.abs().max(1.0);
    let energy_relative_error =
        (arm64_invariants.total_energy_j - scalar_invariants.total_energy_j).abs() / energy_scale;

    BackendEquivalenceReport {
        metrics,
        energy_relative_error,
    }
}

#[must_use]
pub fn playback_substep_plan(
    total_seconds: f64,
    collision_model: &CollisionModel,
    sim_seconds_per_real_second: f64,
) -> PlaybackSubstepPlan {
    PlaybackSubstepPlan {
        total_seconds,
        max_substep_seconds: effective_playback_max_substep_seconds(
            total_seconds,
            collision_model,
            sim_seconds_per_real_second,
        ),
    }
}

#[must_use]
pub fn effective_playback_max_substep_seconds(
    total_seconds: f64,
    collision_model: &CollisionModel,
    sim_seconds_per_real_second: f64,
) -> f64 {
    if !total_seconds.is_finite() || total_seconds <= 0.0 {
        return MAX_SIMULATION_SUBSTEP_SECONDS;
    }

    if collision_model != &CollisionModel::None {
        return MAX_SIMULATION_SUBSTEP_SECONDS;
    }

    let is_high_speed_playback =
        sim_seconds_per_real_second >= HIGH_SPEED_PLAYBACK_THRESHOLD_SIM_SECONDS_PER_REAL_SECOND;
    let max_effective_substep_seconds = if is_high_speed_playback {
        HIGH_SPEED_PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS
    } else {
        PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS
    };
    let adaptive_substep = total_seconds / PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK;
    let preset_based_cap = adaptive_substep.clamp(
        MAX_SIMULATION_SUBSTEP_SECONDS,
        max_effective_substep_seconds,
    );

    if !should_apply_host_relative_short_window_cap(total_seconds, sim_seconds_per_real_second) {
        return preset_based_cap;
    }

    let host_relative_adaptive_substep =
        total_seconds / HOST_RELATIVE_PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK;
    let host_relative_cap = host_relative_adaptive_substep.clamp(
        MAX_SIMULATION_SUBSTEP_SECONDS,
        HOST_RELATIVE_SHORT_WINDOW_MAX_EFFECTIVE_SUBSTEP_SECONDS,
    );
    preset_based_cap.min(host_relative_cap)
}

fn should_apply_host_relative_short_window_cap(
    total_seconds: f64,
    sim_seconds_per_real_second: f64,
) -> bool {
    sim_seconds_per_real_second >= HOST_RELATIVE_SHORT_WINDOW_THRESHOLD_SIM_SECONDS_PER_REAL_SECOND
        && total_seconds <= HOST_RELATIVE_SHORT_WINDOW_MAX_SECONDS
}

fn advance_authoritative_arm64(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
) -> PhysicsInvariants {
    if bodies.is_empty() || delta_seconds <= 0.0 {
        return compute_invariants(bodies);
    }

    let max_substep_seconds =
        if policy.max_substep_seconds.is_finite() && policy.max_substep_seconds > 0.0 {
            policy.max_substep_seconds
        } else {
            delta_seconds
        };
    let step_count = (delta_seconds / max_substep_seconds).ceil().max(1.0) as usize;
    let dt_seconds = delta_seconds / step_count as f64;

    for _ in 0..step_count {
        integrate_substep_arm64(bodies, dt_seconds);
    }

    compute_invariants(bodies)
}

fn advance_authoritative_x64(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
) -> PhysicsInvariants {
    advance_authoritative_scalar(policy, bodies, delta_seconds)
}

pub fn compute_invariants(bodies: &[MassiveBodyState]) -> PhysicsInvariants {
    if bodies.is_empty() {
        return PhysicsInvariants::default();
    }

    let mut total_mass = 0.0_f64;
    let mut barycenter_numerator = Vector3d::default();
    let mut linear_momentum = Vector3d::default();
    let mut angular_momentum = Vector3d::default();
    let mut kinetic_energy_j = 0.0_f64;

    for body in bodies {
        total_mass += body.mass_kg;
        barycenter_numerator = add(barycenter_numerator, scale(body.position_m, body.mass_kg));

        let momentum = scale(body.velocity_mps, body.mass_kg);
        linear_momentum = add(linear_momentum, momentum);
        angular_momentum = add(angular_momentum, cross(body.position_m, momentum));
        kinetic_energy_j += 0.5 * body.mass_kg * norm_squared(body.velocity_mps);
    }

    let mut potential_energy_j = 0.0_f64;
    for i in 0..bodies.len() {
        for j in (i + 1)..bodies.len() {
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let distance_m = norm(delta).max(MIN_DISTANCE_M2.sqrt());
            potential_energy_j -=
                G_M3_PER_KG_S2 * bodies[i].mass_kg * bodies[j].mass_kg / distance_m;
        }
    }

    PhysicsInvariants {
        total_energy_j: kinetic_energy_j + potential_energy_j,
        linear_momentum_kg_mps: linear_momentum,
        angular_momentum_kg_m2ps: angular_momentum,
        barycenter_m: if total_mass > 0.0 {
            scale(barycenter_numerator, 1.0 / total_mass)
        } else {
            Vector3d::default()
        },
    }
}

/// Performs a single Kick-Drift-Kick (Leapfrog) integration substep.
///
/// The sequence is:
/// 1. Compute initial accelerations (a0) based on current positions.
/// 2. Kick: Update velocities by half a time step (v = v + a0 * dt/2).
/// 3. Drift: Update positions by a full time step (r = r + v * dt).
/// 4. Recompute accelerations (a1) at the new positions.
/// 5. Kick: Update velocities by the remaining half time step (v = v + a1 * dt/2).
///
/// This method is second-order accurate and symplectic, ensuring that the
/// orbital energy of the system remains stable (non-drifting) over many steps.
fn integrate_substep(bodies: &mut [MassiveBodyState], dt_seconds: f64) {
    let a0 = pairwise_gravity_accelerations(bodies);

    for i in 0..bodies.len() {
        bodies[i].velocity_mps = add(bodies[i].velocity_mps, scale(a0[i], 0.5 * dt_seconds));
        bodies[i].position_m = add(
            bodies[i].position_m,
            scale(bodies[i].velocity_mps, dt_seconds),
        );
    }

    let a1 = pairwise_gravity_accelerations(bodies);
    for i in 0..bodies.len() {
        bodies[i].velocity_mps = add(bodies[i].velocity_mps, scale(a1[i], 0.5 * dt_seconds));
    }
}

fn integrate_substep_arm64(bodies: &mut [MassiveBodyState], dt_seconds: f64) {
    let a0 = pairwise_gravity_accelerations_arm64(bodies);
    let half_dt = 0.5 * dt_seconds;

    for i in 0..bodies.len() {
        bodies[i].velocity_mps.x = a0[i].x.mul_add(half_dt, bodies[i].velocity_mps.x);
        bodies[i].velocity_mps.y = a0[i].y.mul_add(half_dt, bodies[i].velocity_mps.y);
        bodies[i].velocity_mps.z = a0[i].z.mul_add(half_dt, bodies[i].velocity_mps.z);
        bodies[i].position_m.x = bodies[i]
            .velocity_mps
            .x
            .mul_add(dt_seconds, bodies[i].position_m.x);
        bodies[i].position_m.y = bodies[i]
            .velocity_mps
            .y
            .mul_add(dt_seconds, bodies[i].position_m.y);
        bodies[i].position_m.z = bodies[i]
            .velocity_mps
            .z
            .mul_add(dt_seconds, bodies[i].position_m.z);
    }

    let a1 = pairwise_gravity_accelerations_arm64(bodies);
    for i in 0..bodies.len() {
        bodies[i].velocity_mps.x = a1[i].x.mul_add(half_dt, bodies[i].velocity_mps.x);
        bodies[i].velocity_mps.y = a1[i].y.mul_add(half_dt, bodies[i].velocity_mps.y);
        bodies[i].velocity_mps.z = a1[i].z.mul_add(half_dt, bodies[i].velocity_mps.z);
    }
}

fn pairwise_gravity_accelerations(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    for i in 0..bodies.len() {
        for j in (i + 1)..bodies.len() {
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let distance_sq = norm_squared(delta).max(MIN_DISTANCE_M2);
            let inv_distance = distance_sq.sqrt().recip();
            let inv_distance_cubed = inv_distance * inv_distance * inv_distance;

            let accel_i = scale(
                delta,
                G_M3_PER_KG_S2 * bodies[j].mass_kg * inv_distance_cubed,
            );
            let accel_j = scale(
                delta,
                -G_M3_PER_KG_S2 * bodies[i].mass_kg * inv_distance_cubed,
            );

            accelerations[i] = add(accelerations[i], accel_i);
            accelerations[j] = add(accelerations[j], accel_j);
        }
    }
    accelerations
}

fn pairwise_gravity_accelerations_arm64(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    for i in 0..bodies.len() {
        for j in (i + 1)..bodies.len() {
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let distance_sq = norm_squared(delta).max(MIN_DISTANCE_M2);
            let inv_distance = distance_sq.sqrt().recip();
            let inv_distance_cubed = inv_distance * inv_distance * inv_distance;
            let scale_i = G_M3_PER_KG_S2 * bodies[j].mass_kg * inv_distance_cubed;
            let scale_j = -G_M3_PER_KG_S2 * bodies[i].mass_kg * inv_distance_cubed;

            accelerations[i].x = delta.x.mul_add(scale_i, accelerations[i].x);
            accelerations[i].y = delta.y.mul_add(scale_i, accelerations[i].y);
            accelerations[i].z = delta.z.mul_add(scale_i, accelerations[i].z);

            accelerations[j].x = delta.x.mul_add(scale_j, accelerations[j].x);
            accelerations[j].y = delta.y.mul_add(scale_j, accelerations[j].y);
            accelerations[j].z = delta.z.mul_add(scale_j, accelerations[j].z);
        }
    }
    accelerations
}

fn dispatch_solver_backend(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
) -> DispatchDecision {
    dispatch_solver_backend_for_host(
        requested_backend,
        active_cpu_features,
        cfg!(target_arch = "aarch64"),
        cfg!(target_arch = "x86_64"),
    )
}

fn dispatch_solver_backend_for_host(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
    is_aarch64_host: bool,
    is_x64_host: bool,
) -> DispatchDecision {
    let has_feature = |feature: &str| active_cpu_features.iter().any(|value| value == feature);

    match requested_backend {
        SolverBackend::ReferenceScalar => DispatchDecision {
            effective_backend: SolverBackend::ReferenceScalar,
            path_id: "scalar.reference".to_owned(),
            fallback_reason: None,
        },
        SolverBackend::SimdArm64 => {
            if !is_aarch64_host {
                return DispatchDecision {
                    effective_backend: SolverBackend::ReferenceScalar,
                    path_id: "scalar.reference".to_owned(),
                    fallback_reason: Some(
                        "simd-arm64 requested on non-aarch64 host; using scalar oracle".to_owned(),
                    ),
                };
            }
            if !has_feature("neon") {
                return DispatchDecision {
                    effective_backend: SolverBackend::ReferenceScalar,
                    path_id: "scalar.reference".to_owned(),
                    fallback_reason: Some(
                        "simd-arm64 requested but neon capability not detected".to_owned(),
                    ),
                };
            }

            let path_id = if has_feature("sme") {
                "simd.arm64.sme"
            } else if has_feature("sve2") {
                "simd.arm64.sve2"
            } else if has_feature("fma") {
                "simd.arm64.neon-fma"
            } else {
                "simd.arm64.neon"
            };

            DispatchDecision {
                effective_backend: SolverBackend::SimdArm64,
                path_id: path_id.to_owned(),
                fallback_reason: None,
            }
        }
        SolverBackend::SimdX64 => {
            let x64_fallback = || {
                DispatchDecision {
                effective_backend: SolverBackend::ReferenceScalar,
                path_id: "scalar.reference".to_owned(),
                fallback_reason: Some(
                    "simd-x64 requested but dedicated x64 kernel is not implemented; using scalar oracle".to_owned(),
                ),
            }
            };

            if !is_x64_host {
                return x64_fallback();
            }
            if !has_feature("sse2") && !has_feature("avx2") {
                return x64_fallback();
            }
            x64_fallback()
        }
    }
}

fn override_cpu_features() -> Option<Vec<String>> {
    let raw = std::env::var("SOLARLAB_FORCE_CPU_FEATURES").ok()?;
    let mut dedupe = BTreeSet::new();
    for token in raw.split(',') {
        let normalized = token.trim().to_ascii_lowercase();
        if !normalized.is_empty() {
            dedupe.insert(normalized);
        }
    }
    Some(dedupe.into_iter().collect())
}

fn cpuinfo_feature_tokens() -> BTreeSet<String> {
    let Ok(contents) = fs::read_to_string("/proc/cpuinfo") else {
        return BTreeSet::new();
    };

    let mut features = BTreeSet::new();
    for line in contents.lines() {
        let Some((key, values)) = line.split_once(':') else {
            continue;
        };
        let normalized_key = key.trim().to_ascii_lowercase();
        if normalized_key != "features" && normalized_key != "flags" {
            continue;
        }
        for value in values.split_whitespace() {
            features.insert(value.trim().to_ascii_lowercase());
        }
    }
    features
}

fn has_any_token(tokens: &BTreeSet<String>, candidates: &[&str]) -> bool {
    candidates
        .iter()
        .any(|candidate| tokens.contains(*candidate))
}

fn update_equivalence_metrics(lhs: f64, rhs: f64, metrics: &mut SolverEquivalenceMetrics) {
    metrics.compared_components += 1;
    if lhs.to_bits() == rhs.to_bits() {
        metrics.bitwise_equal_components += 1;
    }

    let absolute_error = (lhs - rhs).abs();
    if absolute_error > metrics.max_abs_error {
        metrics.max_abs_error = absolute_error;
    }

    let denominator = rhs.abs().max(1.0);
    let relative_error = absolute_error / denominator;
    if relative_error > metrics.max_relative_error {
        metrics.max_relative_error = relative_error;
    }
}

fn normalize_cpu_features(features: &[String]) -> Vec<String> {
    let mut dedupe = BTreeSet::new();
    for feature in features {
        let normalized = feature.trim().to_ascii_lowercase();
        if !normalized.is_empty() {
            dedupe.insert(normalized);
        }
    }
    dedupe.into_iter().collect()
}

fn add(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x + b.x,
        y: a.y + b.y,
        z: a.z + b.z,
    }
}

fn subtract(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}

fn scale(v: Vector3d, scalar: f64) -> Vector3d {
    Vector3d {
        x: v.x * scalar,
        y: v.y * scalar,
        z: v.z * scalar,
    }
}

fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    }
}

fn norm(v: Vector3d) -> f64 {
    norm_squared(v).sqrt()
}

fn norm_squared(v: Vector3d) -> f64 {
    v.x * v.x + v.y * v.y + v.z * v.z
}

#[cfg(test)]
mod tests {
    use super::{
        advance_authoritative, advance_authoritative_scalar, compare_arm64_kernel_to_scalar,
        compute_invariants, detect_cpu_features, dispatch_solver_backend_for_host,
        effective_playback_max_substep_seconds, norm, pairwise_gravity_accelerations,
        playback_substep_plan, subtract, CollisionModel, IntegratorKind, MassiveBodyState,
        PhysicsPolicy, SolverBackend, G_M3_PER_KG_S2, MIN_DISTANCE_M2,
    };
    use solarlab_domain::Vector3d;

    #[test]
    fn scalar_authoritative_solver_moves_bodies_toward_each_other() {
        let policy = test_policy();
        let mut bodies = vec![
            MassiveBodyState {
                mass_kg: 5.972e24,
                position_m: Vector3d {
                    x: -2.0e7,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 7.348e22,
                position_m: Vector3d {
                    x: 2.0e7,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ];

        let initial_left_x = bodies[0].position_m.x;
        let initial_right_x = bodies[1].position_m.x;

        let invariants = advance_authoritative_scalar(&policy, &mut bodies, 60.0);

        assert!(bodies[0].position_m.x > initial_left_x);
        assert!(bodies[1].position_m.x < initial_right_x);
        assert!(invariants.total_energy_j.is_finite());
    }

    #[test]
    fn advance_authoritative_reports_effective_backend_and_path() {
        let policy = test_policy();
        let mut bodies = moon_earth_playback_scenario();
        let (invariants, report) = advance_authoritative(&policy, &mut bodies, 60.0);

        assert!(invariants.total_energy_j.is_finite());
        assert_eq!(report.requested_backend, SolverBackend::ReferenceScalar);
        assert_eq!(report.effective_backend, SolverBackend::ReferenceScalar);
        assert_eq!(report.path_id, "scalar.reference");
        assert!(report.fallback_reason.is_none());
    }

    #[test]
    fn invariants_capture_barycenter_and_linear_momentum() {
        let bodies = vec![
            MassiveBodyState {
                mass_kg: 2.0,
                position_m: Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d {
                    x: 3.0,
                    y: 0.0,
                    z: 0.0,
                },
            },
            MassiveBodyState {
                mass_kg: 1.0,
                position_m: Vector3d {
                    x: 2.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
            },
        ];

        let invariants = compute_invariants(&bodies);

        assert!((invariants.barycenter_m.x - 0.0).abs() < 1.0e-12);
        assert!((invariants.linear_momentum_kg_mps.x - 5.0).abs() < 1.0e-12);
    }

    #[test]
    fn pairwise_kernel_matches_reference_mixed_loop() {
        let bodies = mixed_gravity_scenario_for_parity();
        let actual = pairwise_gravity_accelerations(&bodies);
        let expected = legacy_reference_accelerations(&bodies);

        for i in 0..actual.len() {
            assert_vector_close(actual[i], expected[i], 1e-15);
        }
    }

    #[test]
    fn pairwise_kernel_returns_zero_with_no_secondary_sources() {
        let body = MassiveBodyState {
            mass_kg: 1.0e9,
            position_m: Vector3d {
                x: 42.0,
                y: -7.0,
                z: 13.0,
            },
            velocity_mps: Vector3d {
                x: 1.0,
                y: -0.5,
                z: 2.0,
            },
        };
        let policy = test_policy();

        let mut only_body = vec![body];
        let before = only_body.clone();
        let accelerations = pairwise_gravity_accelerations(&only_body);
        let mut stationary_body = vec![MassiveBodyState {
            mass_kg: body.mass_kg,
            position_m: body.position_m,
            velocity_mps: Vector3d::default(),
        }];

        for component in [
            &accelerations[0].x,
            &accelerations[0].y,
            &accelerations[0].z,
        ] {
            assert_eq!(*component, 0.0);
        }

        advance_authoritative_scalar(&policy, &mut only_body, 10.0);
        advance_authoritative_scalar(&policy, &mut stationary_body, 10.0);

        assert_eq!(before[0].velocity_mps.x, only_body[0].velocity_mps.x);
        assert_eq!(before[0].velocity_mps.y, only_body[0].velocity_mps.y);
        assert_eq!(before[0].velocity_mps.z, only_body[0].velocity_mps.z);
        assert_eq!(
            before[0].position_m.x + 10.0 * before[0].velocity_mps.x,
            only_body[0].position_m.x
        );
        assert_eq!(
            before[0].position_m.y + 10.0 * before[0].velocity_mps.y,
            only_body[0].position_m.y
        );
        assert_eq!(
            before[0].position_m.z + 10.0 * before[0].velocity_mps.z,
            only_body[0].position_m.z
        );

        assert_eq!(42.0, stationary_body[0].position_m.x);
        assert_eq!(-7.0, stationary_body[0].position_m.y);
        assert_eq!(13.0, stationary_body[0].position_m.z);
        assert_eq!(0.0, stationary_body[0].velocity_mps.x);
        assert_eq!(0.0, stationary_body[0].velocity_mps.y);
        assert_eq!(0.0, stationary_body[0].velocity_mps.z);
    }

    #[test]
    fn pairwise_kernel_stabilizes_edge_distance_and_conserves_pairwise_force() {
        let bodies = vec![
            MassiveBodyState {
                mass_kg: 1.0e24,
                position_m: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 1.0e20,
                position_m: Vector3d {
                    x: 1.0e-8,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ];

        let accelerations = pairwise_gravity_accelerations(&bodies);

        for body_accel in &accelerations {
            assert!(body_accel.x.is_finite());
            assert!(body_accel.y.is_finite());
            assert!(body_accel.z.is_finite());
        }

        let force_balance_x =
            bodies[0].mass_kg * accelerations[0].x + bodies[1].mass_kg * accelerations[1].x;
        let force_balance_y =
            bodies[0].mass_kg * accelerations[0].y + bodies[1].mass_kg * accelerations[1].y;
        let force_balance_z =
            bodies[0].mass_kg * accelerations[0].z + bodies[1].mass_kg * accelerations[1].z;

        assert!(force_balance_x.abs() < 1e-6);
        assert!(force_balance_y.abs() < 1e-6);
        assert!(force_balance_z.abs() < 1e-6);
    }

    #[test]
    fn simd_arm64_dispatch_prioritizes_sme_then_sve2_then_fma() {
        let requested = SolverBackend::SimdArm64;
        let sme_decision = dispatch_solver_backend_for_host(
            &requested,
            &[
                "neon".to_owned(),
                "fma".to_owned(),
                "sve2".to_owned(),
                "sme".to_owned(),
            ],
            true,
            false,
        );
        let sve2_decision = dispatch_solver_backend_for_host(
            &requested,
            &["neon".to_owned(), "fma".to_owned(), "sve2".to_owned()],
            true,
            false,
        );
        let fma_decision = dispatch_solver_backend_for_host(
            &requested,
            &["neon".to_owned(), "fma".to_owned()],
            true,
            false,
        );
        let neon_decision =
            dispatch_solver_backend_for_host(&requested, &["neon".to_owned()], true, false);

        assert_eq!(sme_decision.path_id, "simd.arm64.sme");
        assert_eq!(sve2_decision.path_id, "simd.arm64.sve2");
        assert_eq!(fma_decision.path_id, "simd.arm64.neon-fma");
        assert_eq!(neon_decision.path_id, "simd.arm64.neon");
    }

    #[test]
    fn simd_arm64_dispatch_falls_back_without_neon_or_wrong_host() {
        let requested = SolverBackend::SimdArm64;
        let non_arm_host =
            dispatch_solver_backend_for_host(&requested, &["neon".to_owned()], false, true);
        let missing_neon =
            dispatch_solver_backend_for_host(&requested, &["fma".to_owned()], true, false);

        assert_eq!(
            non_arm_host.effective_backend,
            SolverBackend::ReferenceScalar
        );
        assert!(non_arm_host.fallback_reason.is_some());
        assert_eq!(
            missing_neon.effective_backend,
            SolverBackend::ReferenceScalar
        );
        assert!(missing_neon.fallback_reason.is_some());
    }

    #[test]
    fn ordinary_playback_intervals_keep_existing_one_hour_cap() {
        let effective =
            effective_playback_max_substep_seconds(3_600.0, &CollisionModel::None, 3_600.0);

        assert_eq!(effective, 3_600.0);
    }

    #[test]
    fn large_playback_intervals_raise_max_substep_adaptively() {
        let effective =
            effective_playback_max_substep_seconds(86_400.0, &CollisionModel::None, 86_400.0);

        assert_eq!(effective, 7_200.0);
    }

    #[test]
    fn very_large_non_high_speed_intervals_clamp_at_bounded_upper_cap() {
        let effective =
            effective_playback_max_substep_seconds(864_000.0, &CollisionModel::None, 86_400.0);

        assert_eq!(effective, 32_400.0);
    }

    #[test]
    fn high_speed_playback_clamps_with_tighter_cap() {
        let effective =
            effective_playback_max_substep_seconds(864_000.0, &CollisionModel::None, 2_592_000.0);

        assert_eq!(effective, 21_600.0);
    }

    #[test]
    fn highest_playback_rate_keeps_worst_case_tick_fanout_bounded() {
        let worst_case_tick_seconds = 2_592_000.0 * 0.25;
        let effective = effective_playback_max_substep_seconds(
            worst_case_tick_seconds,
            &CollisionModel::None,
            2_592_000.0,
        );
        let substep_count = (worst_case_tick_seconds / effective).ceil() as usize;

        assert_eq!(effective, 21_600.0);
        assert!(
            substep_count <= 30,
            "expected <= 30 substeps, got {substep_count}"
        );
    }

    #[test]
    fn runtime_tick_planning_uses_high_speed_substep_cap() {
        let simulated_tick_seconds = 2_592_000.0 * 0.25;
        let plan =
            playback_substep_plan(simulated_tick_seconds, &CollisionModel::None, 2_592_000.0);
        let substep_count = (plan.total_seconds / plan.max_substep_seconds).ceil() as usize;

        assert_eq!(plan.max_substep_seconds, 21_600.0);
        assert!(
            substep_count <= 30,
            "expected <= 30 substeps, got {substep_count}"
        );
    }

    #[test]
    fn collision_enabled_playback_modes_keep_conservative_one_hour_cap() {
        for collision_model in [
            CollisionModel::Merge,
            CollisionModel::Elastic,
            CollisionModel::Fragmentation,
        ] {
            let effective =
                effective_playback_max_substep_seconds(864_000.0, &collision_model, 2_592_000.0);

            assert_eq!(effective, 3_600.0);
        }
    }

    #[test]
    fn playback_policy_drift_is_lower_than_coarse_legacy_cap() {
        let playback_tick_seconds = 864_000.0;
        let playback_ticks = 3;
        let baseline_max_substep_seconds = 3_600.0;
        let policy_max_substep_seconds = effective_playback_max_substep_seconds(
            playback_tick_seconds,
            &CollisionModel::None,
            2_592_000.0,
        );
        let coarse_legacy_max_substep_seconds = 43_200.0;

        let baseline = advance_snapshot(
            playback_ticks,
            playback_tick_seconds,
            baseline_max_substep_seconds,
        );
        let coarse_legacy = advance_snapshot(
            playback_ticks,
            playback_tick_seconds,
            coarse_legacy_max_substep_seconds,
        );
        let policy = advance_snapshot(
            playback_ticks,
            playback_tick_seconds,
            policy_max_substep_seconds,
        );

        let baseline_moon_from_earth = moon_from_earth(&baseline);
        let coarse_legacy_moon_from_earth = moon_from_earth(&coarse_legacy);
        let policy_moon_from_earth = moon_from_earth(&policy);
        let coarse_legacy_drift_m = norm(subtract(
            coarse_legacy_moon_from_earth,
            baseline_moon_from_earth,
        ));
        let policy_drift_m = norm(subtract(policy_moon_from_earth, baseline_moon_from_earth));

        assert_eq!(policy_max_substep_seconds, 21_600.0);
        assert!(policy_drift_m < coarse_legacy_drift_m);
    }

    #[test]
    fn playback_policy_keeps_short_window_turning_angle_jitter_below_coarse_legacy() {
        let playback_tick_seconds = 86_400.0;
        let playback_window_ticks = 6;
        let fine_max_substep_seconds = 120.0;
        let coarse_legacy_max_substep_seconds = 43_200.0;
        let policy_max_substep_seconds = effective_playback_max_substep_seconds(
            playback_tick_seconds,
            &CollisionModel::None,
            2_592_000.0,
        );

        let baseline = host_relative_turning_angles(
            playback_window_ticks,
            playback_tick_seconds,
            fine_max_substep_seconds,
        );
        let policy = host_relative_turning_angles(
            playback_window_ticks,
            playback_tick_seconds,
            policy_max_substep_seconds,
        );
        let coarse_legacy = host_relative_turning_angles(
            playback_window_ticks,
            playback_tick_seconds,
            coarse_legacy_max_substep_seconds,
        );

        let policy_max_turning_error = max_turning_angle_error(&policy, &baseline);
        let coarse_legacy_max_turning_error = max_turning_angle_error(&coarse_legacy, &baseline);

        assert_eq!(policy_max_substep_seconds, 3_600.0);
        assert_eq!(baseline.len(), playback_window_ticks);
        assert_eq!(policy.len(), playback_window_ticks);
        assert_eq!(coarse_legacy.len(), playback_window_ticks);
        assert!(policy_max_turning_error < coarse_legacy_max_turning_error);
    }

    #[test]
    fn host_relative_short_window_cap_tightens_legacy_high_speed_path_without_regression() {
        let playback_tick_seconds = 86_400.0;
        let playback_window_ticks = 6;
        let fine_max_substep_seconds = 120.0;
        let legacy_policy_cap = legacy_high_speed_policy_cap(playback_tick_seconds);
        let policy_plan =
            playback_substep_plan(playback_tick_seconds, &CollisionModel::None, 2_592_000.0);
        let policy_cap = policy_plan.max_substep_seconds;
        let two_day_plan = playback_substep_plan(
            2.0 * playback_tick_seconds,
            &CollisionModel::None,
            2_592_000.0,
        );

        let baseline = host_relative_turning_angles(
            playback_window_ticks,
            playback_tick_seconds,
            fine_max_substep_seconds,
        );
        let legacy_policy = host_relative_turning_angles(
            playback_window_ticks,
            playback_tick_seconds,
            legacy_policy_cap,
        );
        let current_policy =
            host_relative_turning_angles(playback_window_ticks, playback_tick_seconds, policy_cap);
        let legacy_policy_max_turning_error = max_turning_angle_error(&legacy_policy, &baseline);
        let current_policy_max_turning_error = max_turning_angle_error(&current_policy, &baseline);

        assert_eq!(legacy_policy_cap, 7_200.0);
        assert_eq!(policy_plan.total_seconds, playback_tick_seconds);
        assert_eq!(policy_cap, 3_600.0);
        assert_eq!(two_day_plan.max_substep_seconds, 14_400.0);
        assert!(policy_cap < legacy_policy_cap);
        assert!(current_policy_max_turning_error <= legacy_policy_max_turning_error);
    }

    #[test]
    fn arm64_solver_kernel_is_scalar_oracle_equivalent_with_strict_tolerance() {
        let policy = test_policy();
        let report =
            compare_arm64_kernel_to_scalar(&policy, &moon_earth_playback_scenario(), 86_400.0);
        let metrics = report.metrics;

        assert!(metrics.compared_components > 0);
        assert!(metrics.bitwise_equal_components > 0);
        assert!(
            metrics.max_abs_error <= 1.0e-3,
            "max_abs_error {} exceeded tolerance",
            metrics.max_abs_error
        );
        assert!(
            metrics.max_relative_error <= 1.0e-12,
            "max_relative_error {} exceeded tolerance",
            metrics.max_relative_error
        );
        assert!(
            report.energy_relative_error <= 1.0e-12,
            "relative total energy error {} exceeded tolerance",
            report.energy_relative_error
        );
    }

    #[test]
    fn host_arm64_capability_true_activation_is_truthful() {
        if !cfg!(target_arch = "aarch64") {
            return;
        }

        let mut policy = test_policy();
        policy.solver_backend = SolverBackend::SimdArm64;
        let mut bodies = moon_earth_playback_scenario();
        let (_, report) = advance_authoritative(&policy, &mut bodies, 60.0);
        let features = detect_cpu_features();

        if features.iter().any(|feature| feature == "neon") {
            assert_eq!(report.effective_backend, SolverBackend::SimdArm64);
        } else {
            assert_eq!(report.effective_backend, SolverBackend::ReferenceScalar);
            assert!(report.fallback_reason.is_some());
        }

        if features.iter().any(|feature| feature == "sme") {
            assert_eq!(report.path_id, "simd.arm64.sme");
        } else if features.iter().any(|feature| feature == "sve2") {
            assert_eq!(report.path_id, "simd.arm64.sve2");
        }
    }

    fn assert_vector_close(actual: Vector3d, expected: Vector3d, eps: f64) {
        assert!((actual.x - expected.x).abs() <= eps);
        assert!((actual.y - expected.y).abs() <= eps);
        assert!((actual.z - expected.z).abs() <= eps);
    }

    fn advance_snapshot(
        ticks: usize,
        tick_seconds: f64,
        max_substep_seconds: f64,
    ) -> Vec<MassiveBodyState> {
        let policy = PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds,
        };
        let mut bodies = moon_earth_playback_scenario();

        for _ in 0..ticks {
            advance_authoritative_scalar(&policy, &mut bodies, tick_seconds);
        }

        bodies
    }

    fn host_relative_turning_angles(
        ticks: usize,
        tick_seconds: f64,
        max_substep_seconds: f64,
    ) -> Vec<f64> {
        let policy = PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds,
        };
        let mut bodies = moon_earth_playback_scenario();
        let mut host_relative_vectors = Vec::with_capacity(ticks + 1);
        host_relative_vectors.push(moon_from_earth(&bodies));

        for _ in 0..ticks {
            advance_authoritative_scalar(&policy, &mut bodies, tick_seconds);
            host_relative_vectors.push(moon_from_earth(&bodies));
        }

        host_relative_vectors
            .windows(2)
            .map(|window| turning_angle(window[0], window[1]))
            .collect()
    }

    fn max_turning_angle_error(actual: &[f64], expected: &[f64]) -> f64 {
        actual
            .iter()
            .zip(expected.iter())
            .map(|(a, b)| (a - b).abs())
            .fold(0.0_f64, f64::max)
    }

    fn legacy_high_speed_policy_cap(total_seconds: f64) -> f64 {
        (total_seconds / 12.0).clamp(3_600.0, 21_600.0)
    }

    fn turning_angle(previous: Vector3d, current: Vector3d) -> f64 {
        let denominator = norm(previous) * norm(current);
        if denominator == 0.0 {
            return 0.0;
        }

        let cosine = dot(previous, current) / denominator;
        cosine.clamp(-1.0, 1.0).acos()
    }

    fn moon_from_earth(bodies: &[MassiveBodyState]) -> Vector3d {
        subtract(bodies[2].position_m, bodies[1].position_m)
    }

    fn legacy_reference_accelerations(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
        let mut accelerations = vec![Vector3d::default(); bodies.len()];
        for i in 0..bodies.len() {
            let body = bodies[i];
            let body_position = body.position_m;
            let mut acceleration_x = 0.0;
            let mut acceleration_y = 0.0;
            let mut acceleration_z = 0.0;

            for source_index in 0..bodies.len() {
                if source_index == i {
                    continue;
                }

                let source = bodies[source_index];
                let dx = source.position_m.x - body_position.x;
                let dy = source.position_m.y - body_position.y;
                let dz = source.position_m.z - body_position.z;
                let distance_squared = (dx * dx) + (dy * dy) + (dz * dz) + MIN_DISTANCE_M2;

                if distance_squared == 0.0 {
                    continue;
                }

                let inv_distance = distance_squared.sqrt().recip();
                let inv_distance_cubed = inv_distance * inv_distance * inv_distance;
                let scale = G_M3_PER_KG_S2 * source.mass_kg * inv_distance_cubed;
                acceleration_x += dx * scale;
                acceleration_y += dy * scale;
                acceleration_z += dz * scale;
            }

            accelerations[i] = Vector3d {
                x: acceleration_x,
                y: acceleration_y,
                z: acceleration_z,
            };
        }
        accelerations
    }

    fn mixed_gravity_scenario_for_parity() -> Vec<MassiveBodyState> {
        vec![
            MassiveBodyState {
                mass_kg: 1.98847e30,
                position_m: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 5.972168e24,
                position_m: Vector3d {
                    x: 1.496e11,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 7.342e22,
                position_m: Vector3d {
                    x: 1.499844e11,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 10.0,
                position_m: Vector3d {
                    x: 1.0408e11,
                    y: 3.0e10,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ]
    }

    fn moon_earth_playback_scenario() -> Vec<MassiveBodyState> {
        vec![
            MassiveBodyState {
                mass_kg: 1.98847e30,
                position_m: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 5.972168e24,
                position_m: Vector3d {
                    x: 1.496e11,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d {
                    x: 0.0,
                    y: 29_780.0,
                    z: 0.0,
                },
            },
            MassiveBodyState {
                mass_kg: 7.342e22,
                position_m: Vector3d {
                    x: 1.496e11 + 384_400_000.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d {
                    x: 0.0,
                    y: 30_802.0,
                    z: 0.0,
                },
            },
        ]
    }

    fn dot(a: Vector3d, b: Vector3d) -> f64 {
        a.x * b.x + a.y * b.y + a.z * b.z
    }

    fn test_policy() -> PhysicsPolicy {
        PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: 1.0,
        }
    }
}
