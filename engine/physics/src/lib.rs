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
    pub fallback_code: SolverFallbackCode,
    pub fallback_reason: Option<String>,
    pub active_cpu_features: Vec<String>,
    pub schedule: SolverScheduleReport,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SolverFallbackCode {
    None,
    SimdArm64OnNonAarch64Host,
    SimdArm64MissingNeon,
    SimdX64Unavailable,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SolverScheduleMode {
    SingleWorker,
    AdaptiveTiledCandidate,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SolverScheduleReport {
    pub mode: SolverScheduleMode,
    pub active_workers: u32,
    pub candidate_workers: u32,
    pub body_count: u32,
    pub estimated_pair_count: u64,
}

impl SolverExecutionReport {
    #[must_use]
    pub fn reference_scalar() -> Self {
        Self {
            requested_backend: SolverBackend::ReferenceScalar,
            effective_backend: SolverBackend::ReferenceScalar,
            path_id: "scalar.reference".to_owned(),
            fallback_code: SolverFallbackCode::None,
            fallback_reason: None,
            active_cpu_features: detect_cpu_features(),
            schedule: solver_schedule_report(0, false),
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
    fallback_code: SolverFallbackCode,
    fallback_reason: Option<String>,
    arm64_gravity_kernel: Option<Arm64GravityKernel>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Arm64GravityKernel {
    NeonF64Pairwise,
    NeonF64TiledPairwise,
    PortableScalarOracle,
}

impl Arm64GravityKernel {
    fn best_available_for_host() -> Self {
        if arm64_neon_runtime_available() {
            Self::NeonF64Pairwise
        } else {
            Self::PortableScalarOracle
        }
    }
}

pub const CPU_FEATURE_NEON: u64 = 1 << 0;
pub const CPU_FEATURE_FP: u64 = 1 << 1;
pub const CPU_FEATURE_FP16: u64 = 1 << 2;
pub const CPU_FEATURE_FHM: u64 = 1 << 3;
pub const CPU_FEATURE_DOTPROD: u64 = 1 << 4;
pub const CPU_FEATURE_I8MM: u64 = 1 << 5;
pub const CPU_FEATURE_SVE: u64 = 1 << 6;
pub const CPU_FEATURE_SVE2: u64 = 1 << 7;
pub const CPU_FEATURE_SME: u64 = 1 << 8;
pub const CPU_FEATURE_SME2: u64 = 1 << 9;
pub const CPU_FEATURE_LSE: u64 = 1 << 10;
pub const CPU_FEATURE_LSE2: u64 = 1 << 11;
pub const CPU_FEATURE_CRC: u64 = 1 << 12;
pub const CPU_FEATURE_MOPS: u64 = 1 << 13;
pub const CPU_FEATURE_AES: u64 = 1 << 14;
pub const CPU_FEATURE_PMULL: u64 = 1 << 15;
pub const CPU_FEATURE_SHA1: u64 = 1 << 16;
pub const CPU_FEATURE_SHA2: u64 = 1 << 17;
pub const CPU_FEATURE_SHA3: u64 = 1 << 18;
pub const CPU_FEATURE_SHA512: u64 = 1 << 19;
pub const CPU_FEATURE_SM3: u64 = 1 << 20;
pub const CPU_FEATURE_SM4: u64 = 1 << 21;
pub const CPU_FEATURE_BF16: u64 = 1 << 22;
pub const CPU_FEATURE_RNG: u64 = 1 << 23;
pub const CPU_FEATURE_BTI: u64 = 1 << 24;
pub const CPU_FEATURE_MTE: u64 = 1 << 25;
pub const CPU_FEATURE_RDM: u64 = 1 << 26;
pub const CPU_FEATURE_JSCVT: u64 = 1 << 27;
pub const CPU_FEATURE_FCMA: u64 = 1 << 28;
pub const CPU_FEATURE_FLAGM: u64 = 1 << 29;
pub const CPU_FEATURE_FLAGM2: u64 = 1 << 30;
pub const CPU_FEATURE_DIT: u64 = 1 << 31;
pub const CPU_FEATURE_SB: u64 = 1 << 32;
pub const CPU_FEATURE_SSBS: u64 = 1 << 33;
pub const CPU_FEATURE_SVE_I8MM: u64 = 1 << 34;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CpuFeatureUseStatus {
    ActiveSolverCapability,
    BaselineFloatingPoint,
    ReservedUntilKernelExists,
    RuntimeUtilityNoCurrentHotPath,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CpuFeatureCatalogEntry {
    pub canonical_name: &'static str,
    pub flag: u64,
    pub status: CpuFeatureUseStatus,
    pub current_workload: &'static str,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Arm64KernelReadiness {
    Candidate,
    Implemented,
    ParityProven,
    Eligible,
    Active,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Arm64KernelCatalogEntry {
    pub path_id: &'static str,
    pub required_features: &'static [&'static str],
    pub readiness: Arm64KernelReadiness,
    pub workload: &'static str,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Arm64KernelAvailability {
    pub eligible_candidate_paths: Vec<&'static str>,
    pub blocked_candidate_paths: Vec<&'static str>,
}

pub const ARM64_KERNEL_CATALOG: &[Arm64KernelCatalogEntry] = &[
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.neon-f64-pairwise",
        required_features: &["neon"],
        readiness: Arm64KernelReadiness::Active,
        workload: "authoritative f64 pairwise gravity",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.neon-f64-tiled-pairwise",
        required_features: &["neon"],
        readiness: Arm64KernelReadiness::Active,
        workload: "authoritative f64 pairwise gravity for larger body sets",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.sve-f64-batch-candidate",
        required_features: &["sve"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "wider f64 batch gravity/tracer sweep",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.sve2-f64-batch-candidate",
        required_features: &["sve2"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "wider f64 batch gravity/tracer sweep",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.sve-i8mm-packed-assist-candidate",
        required_features: &["sve-i8mm"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "packed integer assist for render/tracer prediction",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.sme-tiled-f64-candidate",
        required_features: &["sme"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "tile-oriented f64 gravity/tracer assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.sme2-tiled-f64-candidate",
        required_features: &["sme2"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "tile-oriented f64 gravity/tracer assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.dotprod-packed-assist-candidate",
        required_features: &["dotprod"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "packed render/tracer assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.i8mm-packed-assist-candidate",
        required_features: &["i8mm"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "packed integer/matrix assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.bf16-forecast-assist-candidate",
        required_features: &["bf16"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "approximate long-horizon forecast assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.fp16-visual-assist-candidate",
        required_features: &["fp16"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "visual/tracer precision assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.fhm-visual-assist-candidate",
        required_features: &["fhm"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "fp16 fused multiply-add visual/tracer assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.rdm-vector-assist-candidate",
        required_features: &["rdm"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "rounding multiply vector assist",
    },
    Arm64KernelCatalogEntry {
        path_id: "simd.arm64.fcma-vector-assist-candidate",
        required_features: &["fcma"],
        readiness: Arm64KernelReadiness::Candidate,
        workload: "complex/vector math assist",
    },
];

pub const ARM64_CPU_FEATURE_CATALOG: &[CpuFeatureCatalogEntry] = &[
    CpuFeatureCatalogEntry {
        canonical_name: "neon",
        flag: CPU_FEATURE_NEON,
        status: CpuFeatureUseStatus::ActiveSolverCapability,
        current_workload: "simd.arm64.neon-f64-pairwise and simd.arm64.neon-f64-tiled-pairwise",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "fp",
        flag: CPU_FEATURE_FP,
        status: CpuFeatureUseStatus::BaselineFloatingPoint,
        current_workload: "baseline floating-point substrate",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "fp16",
        flag: CPU_FEATURE_FP16,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved visualization/tracer-assist precision slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "fhm",
        flag: CPU_FEATURE_FHM,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved fp16 fused multiply-add assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "dotprod",
        flag: CPU_FEATURE_DOTPROD,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved packed render/tracer assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "i8mm",
        flag: CPU_FEATURE_I8MM,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved packed integer/matrix assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sve",
        flag: CPU_FEATURE_SVE,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved wider f64 batch gravity/tracer slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sve2",
        flag: CPU_FEATURE_SVE2,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved wider f64 batch gravity/tracer slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sve-i8mm",
        flag: CPU_FEATURE_SVE_I8MM,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved SVE packed integer assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sme",
        flag: CPU_FEATURE_SME,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved matrix/tile assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sme2",
        flag: CPU_FEATURE_SME2,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved matrix/tile assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "lse",
        flag: CPU_FEATURE_LSE,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "atomic/runtime throughput capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "lse2",
        flag: CPU_FEATURE_LSE2,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "atomic/runtime throughput capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "crc",
        flag: CPU_FEATURE_CRC,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility checksum capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "mops",
        flag: CPU_FEATURE_MOPS,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "memory-operation throughput capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "aes",
        flag: CPU_FEATURE_AES,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "pmull",
        flag: CPU_FEATURE_PMULL,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sha1",
        flag: CPU_FEATURE_SHA1,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sha2",
        flag: CPU_FEATURE_SHA2,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sha3",
        flag: CPU_FEATURE_SHA3,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sha512",
        flag: CPU_FEATURE_SHA512,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sm3",
        flag: CPU_FEATURE_SM3,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sm4",
        flag: CPU_FEATURE_SM4,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility crypto capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "bf16",
        flag: CPU_FEATURE_BF16,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved approximate assist slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "rng",
        flag: CPU_FEATURE_RNG,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "utility random-number capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "bti",
        flag: CPU_FEATURE_BTI,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "platform hardening capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "mte",
        flag: CPU_FEATURE_MTE,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "platform memory-tagging capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "rdm",
        flag: CPU_FEATURE_RDM,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved SIMD rounding multiply slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "jscvt",
        flag: CPU_FEATURE_JSCVT,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "conversion support capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "fcma",
        flag: CPU_FEATURE_FCMA,
        status: CpuFeatureUseStatus::ReservedUntilKernelExists,
        current_workload: "reserved complex/vector math slice",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "flagm",
        flag: CPU_FEATURE_FLAGM,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "flag manipulation support capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "flagm2",
        flag: CPU_FEATURE_FLAGM2,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "flag manipulation support capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "dit",
        flag: CPU_FEATURE_DIT,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "data-independent timing support capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "sb",
        flag: CPU_FEATURE_SB,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "speculation barrier capability",
    },
    CpuFeatureCatalogEntry {
        canonical_name: "ssbs",
        flag: CPU_FEATURE_SSBS,
        status: CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath,
        current_workload: "speculative store bypass control capability",
    },
];

#[must_use]
pub fn arm64_cpu_feature_catalog() -> &'static [CpuFeatureCatalogEntry] {
    ARM64_CPU_FEATURE_CATALOG
}

#[must_use]
pub fn arm64_kernel_catalog() -> &'static [Arm64KernelCatalogEntry] {
    ARM64_KERNEL_CATALOG
}

#[must_use]
pub fn arm64_kernel_availability(active_cpu_features: &[String]) -> Arm64KernelAvailability {
    let normalized_features = normalize_cpu_features(active_cpu_features);
    let has_feature = |feature: &str| {
        normalized_features
            .iter()
            .any(|candidate| candidate == feature)
    };
    let requirements_met = |entry: &Arm64KernelCatalogEntry| {
        entry
            .required_features
            .iter()
            .all(|feature| has_feature(feature))
    };
    let mut availability = Arm64KernelAvailability::default();

    for entry in ARM64_KERNEL_CATALOG {
        match entry.readiness {
            Arm64KernelReadiness::Candidate if requirements_met(entry) => {
                availability.eligible_candidate_paths.push(entry.path_id);
            }
            Arm64KernelReadiness::Candidate => {
                availability.blocked_candidate_paths.push(entry.path_id);
            }
            Arm64KernelReadiness::Active
            | Arm64KernelReadiness::Implemented
            | Arm64KernelReadiness::ParityProven
            | Arm64KernelReadiness::Eligible => {}
        }
    }

    availability
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
const ADAPTIVE_TILED_SCHEDULER_MIN_BODIES: usize = 96;

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
    let decision = dispatch_solver_backend_with_body_count(
        &policy.solver_backend,
        &normalized_features,
        bodies.len(),
    );
    let schedule = solver_schedule_report(
        bodies.len(),
        decision.effective_backend == SolverBackend::SimdArm64,
    );
    let invariants = match decision.effective_backend {
        SolverBackend::ReferenceScalar => {
            advance_authoritative_scalar(policy, bodies, delta_seconds)
        }
        SolverBackend::SimdArm64 => advance_authoritative_arm64(
            policy,
            bodies,
            delta_seconds,
            decision
                .arm64_gravity_kernel
                .unwrap_or(Arm64GravityKernel::PortableScalarOracle),
        ),
        SolverBackend::SimdX64 => advance_authoritative_x64(policy, bodies, delta_seconds),
    };

    (
        invariants,
        SolverExecutionReport {
            requested_backend: policy.solver_backend.clone(),
            effective_backend: decision.effective_backend,
            path_id: decision.path_id,
            fallback_code: decision.fallback_code,
            fallback_reason: decision.fallback_reason,
            active_cpu_features: normalized_features,
            schedule,
        },
    )
}

#[must_use]
pub fn solver_execution_report_for_backend(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
) -> SolverExecutionReport {
    solver_execution_report_for_backend_with_body_count(requested_backend, active_cpu_features, 0)
}

#[must_use]
pub fn solver_execution_report_for_backend_with_body_count(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
    body_count: usize,
) -> SolverExecutionReport {
    let normalized_features = normalize_cpu_features(active_cpu_features);
    let decision = dispatch_solver_backend_with_body_count(
        requested_backend,
        &normalized_features,
        body_count,
    );
    let arm64_solver_active = decision.effective_backend == SolverBackend::SimdArm64;
    SolverExecutionReport {
        requested_backend: requested_backend.clone(),
        effective_backend: decision.effective_backend,
        path_id: decision.path_id,
        fallback_code: decision.fallback_code,
        fallback_reason: decision.fallback_reason,
        active_cpu_features: normalized_features,
        schedule: solver_schedule_report(body_count, arm64_solver_active),
    }
}

#[must_use]
pub fn solver_schedule_report(
    body_count: usize,
    arm64_solver_active: bool,
) -> SolverScheduleReport {
    let active_workers = 1;
    let worker_budget = std::thread::available_parallelism()
        .map(|value| value.get())
        .unwrap_or(1)
        .max(1);
    let candidate_workers =
        if arm64_solver_active && body_count >= ADAPTIVE_TILED_SCHEDULER_MIN_BODIES {
            worker_budget
        } else {
            1
        };
    let mode = if candidate_workers > active_workers {
        SolverScheduleMode::AdaptiveTiledCandidate
    } else {
        SolverScheduleMode::SingleWorker
    };
    let body_count_u64 = body_count as u64;
    let estimated_pair_count = body_count_u64.saturating_mul(body_count_u64.saturating_sub(1)) / 2;

    SolverScheduleReport {
        mode,
        active_workers: active_workers as u32,
        candidate_workers: candidate_workers as u32,
        body_count: body_count as u32,
        estimated_pair_count,
    }
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
    if arm64_neon_runtime_available() {
        features.insert("neon".to_owned());
    }

    for token in cpuinfo_feature_tokens() {
        insert_normalized_cpu_feature(features, &token);
    }
}

#[cfg(not(target_arch = "aarch64"))]
fn add_arm64_features(_features: &mut BTreeSet<String>) {}

#[cfg(target_arch = "aarch64")]
fn arm64_neon_runtime_available() -> bool {
    std::arch::is_aarch64_feature_detected!("neon")
}

#[cfg(not(target_arch = "aarch64"))]
fn arm64_neon_runtime_available() -> bool {
    false
}

#[cfg(target_arch = "x86_64")]
fn has_any_token(tokens: &BTreeSet<String>, aliases: &[&str]) -> bool {
    aliases.iter().any(|alias| tokens.contains(*alias))
}

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
    let arm64_invariants = advance_authoritative_arm64(
        policy,
        &mut arm64_bodies,
        delta_seconds,
        Arm64GravityKernel::best_available_for_host(),
    );
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
    gravity_kernel: Arm64GravityKernel,
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
        integrate_substep_arm64(bodies, dt_seconds, gravity_kernel);
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

fn integrate_substep_arm64(
    bodies: &mut [MassiveBodyState],
    dt_seconds: f64,
    gravity_kernel: Arm64GravityKernel,
) {
    let a0 = pairwise_gravity_accelerations_arm64(bodies, gravity_kernel);
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

    let a1 = pairwise_gravity_accelerations_arm64(bodies, gravity_kernel);
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

#[allow(unsafe_code)]
fn pairwise_gravity_accelerations_arm64(
    bodies: &[MassiveBodyState],
    gravity_kernel: Arm64GravityKernel,
) -> Vec<Vector3d> {
    match gravity_kernel {
        Arm64GravityKernel::NeonF64Pairwise => pairwise_gravity_accelerations_arm64_neon(bodies),
        Arm64GravityKernel::NeonF64TiledPairwise => {
            pairwise_gravity_accelerations_arm64_neon_tiled(bodies)
        }
        Arm64GravityKernel::PortableScalarOracle => {
            pairwise_gravity_accelerations_arm64_portable(bodies)
        }
    }
}

#[cfg(target_arch = "aarch64")]
#[allow(unsafe_code)]
fn pairwise_gravity_accelerations_arm64_neon(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    assert!(
        arm64_neon_runtime_available(),
        "Arm64 NEON solver selected without runtime NEON support"
    );
    // SAFETY: dispatch selects this kernel only after runtime NEON detection,
    // and the function does not expose raw pointers or outlive the input slice.
    unsafe { arm64_neon::pairwise_gravity_accelerations_neon_f64(bodies) }
}

#[cfg(not(target_arch = "aarch64"))]
fn pairwise_gravity_accelerations_arm64_neon(_bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    panic!("Arm64 NEON solver selected on a non-aarch64 host")
}

#[cfg(target_arch = "aarch64")]
#[allow(unsafe_code)]
fn pairwise_gravity_accelerations_arm64_neon_tiled(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    assert!(
        arm64_neon_runtime_available(),
        "Arm64 tiled NEON solver selected without runtime NEON support"
    );
    // SAFETY: dispatch selects this kernel only after runtime NEON detection,
    // and the function does not expose raw pointers or outlive the input slice.
    unsafe { arm64_neon::pairwise_gravity_accelerations_neon_f64_tiled(bodies) }
}

#[cfg(not(target_arch = "aarch64"))]
fn pairwise_gravity_accelerations_arm64_neon_tiled(_bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    panic!("Arm64 tiled NEON solver selected on a non-aarch64 host")
}

fn pairwise_gravity_accelerations_arm64_portable(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
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

#[cfg(target_arch = "aarch64")]
#[allow(unsafe_code)]
mod arm64_neon {
    use std::arch::aarch64::{
        float64x2_t, vdupq_n_f64, vfmaq_n_f64, vgetq_lane_f64, vsetq_lane_f64, vsubq_f64,
    };

    use solarlab_domain::Vector3d;

    use super::{MassiveBodyState, G_M3_PER_KG_S2, MIN_DISTANCE_M2};

    #[target_feature(enable = "neon")]
    pub unsafe fn pairwise_gravity_accelerations_neon_f64(
        bodies: &[MassiveBodyState],
    ) -> Vec<Vector3d> {
        let mut accelerations = vec![Vector3d::default(); bodies.len()];
        for i in 0..bodies.len() {
            for j in (i + 1)..bodies.len() {
                accumulate_pair(bodies, &mut accelerations, i, j);
            }
        }
        accelerations
    }

    #[target_feature(enable = "neon")]
    pub unsafe fn pairwise_gravity_accelerations_neon_f64_tiled(
        bodies: &[MassiveBodyState],
    ) -> Vec<Vector3d> {
        const TILE_SIZE: usize = 32;

        let mut accelerations = vec![Vector3d::default(); bodies.len()];
        for tile_i_start in (0..bodies.len()).step_by(TILE_SIZE) {
            let tile_i_end = (tile_i_start + TILE_SIZE).min(bodies.len());
            for tile_j_start in (tile_i_start..bodies.len()).step_by(TILE_SIZE) {
                let tile_j_end = (tile_j_start + TILE_SIZE).min(bodies.len());
                for i in tile_i_start..tile_i_end {
                    let j_start = if tile_i_start == tile_j_start {
                        i + 1
                    } else {
                        tile_j_start
                    };
                    for j in j_start..tile_j_end {
                        accumulate_pair(bodies, &mut accelerations, i, j);
                    }
                }
            }
        }
        accelerations
    }

    #[target_feature(enable = "neon")]
    unsafe fn xy_vector(x: f64, y: f64) -> float64x2_t {
        vsetq_lane_f64::<1>(y, vsetq_lane_f64::<0>(x, vdupq_n_f64(0.0)))
    }

    #[target_feature(enable = "neon")]
    unsafe fn accumulate_pair(
        bodies: &[MassiveBodyState],
        accelerations: &mut [Vector3d],
        i: usize,
        j: usize,
    ) {
        let delta_xy = vsubq_f64(
            xy_vector(bodies[j].position_m.x, bodies[j].position_m.y),
            xy_vector(bodies[i].position_m.x, bodies[i].position_m.y),
        );
        let delta_x = vgetq_lane_f64::<0>(delta_xy);
        let delta_y = vgetq_lane_f64::<1>(delta_xy);
        let delta_z = bodies[j].position_m.z - bodies[i].position_m.z;
        let distance_sq = delta_x
            .mul_add(delta_x, delta_y.mul_add(delta_y, delta_z * delta_z))
            .max(MIN_DISTANCE_M2);
        let inv_distance = distance_sq.sqrt().recip();
        let inv_distance_cubed = inv_distance * inv_distance * inv_distance;
        let scale_i = G_M3_PER_KG_S2 * bodies[j].mass_kg * inv_distance_cubed;
        let scale_j = -G_M3_PER_KG_S2 * bodies[i].mass_kg * inv_distance_cubed;

        let accel_i_xy = vfmaq_n_f64(
            xy_vector(accelerations[i].x, accelerations[i].y),
            delta_xy,
            scale_i,
        );
        accelerations[i].x = vgetq_lane_f64::<0>(accel_i_xy);
        accelerations[i].y = vgetq_lane_f64::<1>(accel_i_xy);
        accelerations[i].z = delta_z.mul_add(scale_i, accelerations[i].z);

        let accel_j_xy = vfmaq_n_f64(
            xy_vector(accelerations[j].x, accelerations[j].y),
            delta_xy,
            scale_j,
        );
        accelerations[j].x = vgetq_lane_f64::<0>(accel_j_xy);
        accelerations[j].y = vgetq_lane_f64::<1>(accel_j_xy);
        accelerations[j].z = delta_z.mul_add(scale_j, accelerations[j].z);
    }
}

fn dispatch_solver_backend_with_body_count(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
    body_count: usize,
) -> DispatchDecision {
    dispatch_solver_backend_for_host_with_body_count(
        requested_backend,
        active_cpu_features,
        cfg!(target_arch = "aarch64"),
        cfg!(target_arch = "x86_64"),
        arm64_neon_runtime_available(),
        body_count,
    )
}

#[cfg(test)]
fn dispatch_solver_backend_for_host(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
    is_aarch64_host: bool,
    is_x64_host: bool,
    arm64_neon_runtime_available: bool,
) -> DispatchDecision {
    dispatch_solver_backend_for_host_with_body_count(
        requested_backend,
        active_cpu_features,
        is_aarch64_host,
        is_x64_host,
        arm64_neon_runtime_available,
        0,
    )
}

fn dispatch_solver_backend_for_host_with_body_count(
    requested_backend: &SolverBackend,
    active_cpu_features: &[String],
    is_aarch64_host: bool,
    is_x64_host: bool,
    arm64_neon_runtime_available: bool,
    body_count: usize,
) -> DispatchDecision {
    let has_feature = |feature: &str| active_cpu_features.iter().any(|value| value == feature);

    match requested_backend {
        SolverBackend::ReferenceScalar => DispatchDecision {
            effective_backend: SolverBackend::ReferenceScalar,
            path_id: "scalar.reference".to_owned(),
            fallback_code: SolverFallbackCode::None,
            fallback_reason: None,
            arm64_gravity_kernel: None,
        },
        SolverBackend::SimdArm64 => {
            if !is_aarch64_host {
                return DispatchDecision {
                    effective_backend: SolverBackend::ReferenceScalar,
                    path_id: "scalar.reference".to_owned(),
                    fallback_code: SolverFallbackCode::SimdArm64OnNonAarch64Host,
                    fallback_reason: Some(
                        "simd-arm64 requested on non-aarch64 host; using scalar oracle".to_owned(),
                    ),
                    arm64_gravity_kernel: None,
                };
            }
            if !has_feature("neon") || !arm64_neon_runtime_available {
                return DispatchDecision {
                    effective_backend: SolverBackend::ReferenceScalar,
                    path_id: "scalar.reference".to_owned(),
                    fallback_code: SolverFallbackCode::SimdArm64MissingNeon,
                    fallback_reason: Some(
                        "simd-arm64 requested but runtime neon activation is not available"
                            .to_owned(),
                    ),
                    arm64_gravity_kernel: None,
                };
            }

            let (path_id, arm64_gravity_kernel) = arm64_kernel_for_body_count(body_count);
            DispatchDecision {
                effective_backend: SolverBackend::SimdArm64,
                path_id: path_id.to_owned(),
                fallback_code: SolverFallbackCode::None,
                fallback_reason: None,
                arm64_gravity_kernel: Some(arm64_gravity_kernel),
            }
        }
        SolverBackend::SimdX64 => {
            let x64_fallback = || {
                DispatchDecision {
                effective_backend: SolverBackend::ReferenceScalar,
                path_id: "scalar.reference".to_owned(),
                fallback_code: SolverFallbackCode::SimdX64Unavailable,
                fallback_reason: Some(
                    "simd-x64 requested but dedicated x64 kernel is not implemented; using scalar oracle".to_owned(),
                ),
                arm64_gravity_kernel: None,
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

fn arm64_kernel_for_body_count(body_count: usize) -> (&'static str, Arm64GravityKernel) {
    if body_count >= ADAPTIVE_TILED_SCHEDULER_MIN_BODIES {
        (
            "simd.arm64.neon-f64-tiled-pairwise",
            Arm64GravityKernel::NeonF64TiledPairwise,
        )
    } else {
        (
            "simd.arm64.neon-f64-pairwise",
            Arm64GravityKernel::NeonF64Pairwise,
        )
    }
}

fn override_cpu_features() -> Option<Vec<String>> {
    let raw = std::env::var("SOLARLAB_FORCE_CPU_FEATURES").ok()?;
    Some(normalize_cpu_features(
        &raw.split(',').map(ToOwned::to_owned).collect::<Vec<_>>(),
    ))
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

#[must_use]
pub fn cpu_feature_flags(features: &[String]) -> u64 {
    normalize_cpu_features(features)
        .iter()
        .filter_map(|feature| cpu_feature_flag(feature))
        .fold(0, |flags, flag| flags | flag)
}

fn cpu_feature_flag(feature: &str) -> Option<u64> {
    arm64_cpu_feature_catalog()
        .iter()
        .find(|entry| entry.canonical_name == feature)
        .map(|entry| entry.flag)
}

fn insert_normalized_cpu_feature(features: &mut BTreeSet<String>, raw_feature: &str) {
    let normalized = raw_feature.trim().to_ascii_lowercase();
    if normalized.is_empty() {
        return;
    }

    let canonical = match normalized.as_str() {
        "asimd" | "neon" => "neon",
        "fphp" | "asimdhp" | "fp16" => "fp16",
        "fhm" | "asimdfhm" => "fhm",
        "asimddp" | "dotprod" => "dotprod",
        "i8mm" => "i8mm",
        "sve" => "sve",
        "sve2" => "sve2",
        "svei8mm" | "sve_i8mm" | "sve-i8mm" => "sve-i8mm",
        "sme" => "sme",
        "sme2" => "sme2",
        "atomics" | "lse" => "lse",
        "lse2" => "lse2",
        "crc32" | "crc" => "crc",
        "mops" => "mops",
        "aes" => "aes",
        "pmull" => "pmull",
        "sha1" => "sha1",
        "sha2" => "sha2",
        "sha3" => "sha3",
        "sha512" => "sha512",
        "sm3" => "sm3",
        "sm4" => "sm4",
        "bf16" => "bf16",
        "rng" => "rng",
        "bti" => "bti",
        "mte" => "mte",
        "asimdrdm" | "rdm" => "rdm",
        "jscvt" => "jscvt",
        "fcma" => "fcma",
        "flagm" => "flagm",
        "flagm2" => "flagm2",
        "dit" => "dit",
        "sb" => "sb",
        "ssbs" => "ssbs",
        "fp" => "fp",
        _ => normalized.as_str(),
    };
    features.insert(canonical.to_owned());
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
        insert_normalized_cpu_feature(&mut dedupe, feature);
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
        advance_authoritative, advance_authoritative_arm64, advance_authoritative_scalar,
        arm64_cpu_feature_catalog, arm64_kernel_availability, arm64_kernel_catalog,
        arm64_neon_runtime_available, compare_arm64_kernel_to_scalar, compute_invariants,
        cpu_feature_flags, detect_cpu_features, dispatch_solver_backend_for_host,
        dispatch_solver_backend_for_host_with_body_count, effective_playback_max_substep_seconds,
        norm, pairwise_gravity_accelerations, playback_substep_plan,
        solver_execution_report_for_backend, solver_schedule_report, subtract, Arm64GravityKernel,
        Arm64KernelReadiness, CollisionModel, CpuFeatureUseStatus, IntegratorKind,
        MassiveBodyState, PhysicsPolicy, SolverBackend, SolverFallbackCode, SolverScheduleMode,
        CPU_FEATURE_AES, CPU_FEATURE_BF16, CPU_FEATURE_CRC, CPU_FEATURE_DOTPROD, CPU_FEATURE_FCMA,
        CPU_FEATURE_FHM, CPU_FEATURE_FP, CPU_FEATURE_FP16, CPU_FEATURE_I8MM, CPU_FEATURE_JSCVT,
        CPU_FEATURE_LSE, CPU_FEATURE_MOPS, CPU_FEATURE_NEON, CPU_FEATURE_RDM, CPU_FEATURE_SME,
        CPU_FEATURE_SME2, CPU_FEATURE_SVE, CPU_FEATURE_SVE2, CPU_FEATURE_SVE_I8MM, G_M3_PER_KG_S2,
        MIN_DISTANCE_M2,
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
    fn simd_arm64_dispatch_activates_only_implemented_neon_kernel() {
        let requested = SolverBackend::SimdArm64;
        let rich_arm64_decision = dispatch_solver_backend_for_host(
            &requested,
            &[
                "neon".to_owned(),
                "fhm".to_owned(),
                "sve2".to_owned(),
                "sme".to_owned(),
                "sme2".to_owned(),
            ],
            true,
            false,
            true,
        );
        let neon_decision =
            dispatch_solver_backend_for_host(&requested, &["neon".to_owned()], true, false, true);

        assert_eq!(rich_arm64_decision.path_id, "simd.arm64.neon-f64-pairwise");
        assert_eq!(neon_decision.path_id, "simd.arm64.neon-f64-pairwise");
        assert_eq!(rich_arm64_decision.fallback_code, SolverFallbackCode::None);
        assert_eq!(
            rich_arm64_decision.arm64_gravity_kernel,
            Some(Arm64GravityKernel::NeonF64Pairwise)
        );
    }

    #[test]
    fn simd_arm64_dispatch_falls_back_without_neon_or_wrong_host() {
        let requested = SolverBackend::SimdArm64;
        let non_arm_host =
            dispatch_solver_backend_for_host(&requested, &["neon".to_owned()], false, true, true);
        let missing_neon =
            dispatch_solver_backend_for_host(&requested, &["fma".to_owned()], true, false, true);
        let runtime_probe_rejected_neon =
            dispatch_solver_backend_for_host(&requested, &["neon".to_owned()], true, false, false);

        assert_eq!(
            non_arm_host.effective_backend,
            SolverBackend::ReferenceScalar
        );
        assert_eq!(
            non_arm_host.fallback_code,
            SolverFallbackCode::SimdArm64OnNonAarch64Host
        );
        assert!(non_arm_host.fallback_reason.is_some());
        assert_eq!(
            missing_neon.effective_backend,
            SolverBackend::ReferenceScalar
        );
        assert_eq!(
            missing_neon.fallback_code,
            SolverFallbackCode::SimdArm64MissingNeon
        );
        assert!(missing_neon.fallback_reason.is_some());
        assert_eq!(
            runtime_probe_rejected_neon.effective_backend,
            SolverBackend::ReferenceScalar
        );
        assert_eq!(
            runtime_probe_rejected_neon.fallback_code,
            SolverFallbackCode::SimdArm64MissingNeon
        );
        assert_eq!(runtime_probe_rejected_neon.arm64_gravity_kernel, None);
    }

    #[test]
    fn simd_x64_dispatch_reports_unavailable_until_dedicated_kernel_exists() {
        let decision = dispatch_solver_backend_for_host(
            &SolverBackend::SimdX64,
            &["sse2".to_owned(), "avx2".to_owned()],
            false,
            true,
            false,
        );

        assert_eq!(decision.effective_backend, SolverBackend::ReferenceScalar);
        assert_eq!(
            decision.fallback_code,
            SolverFallbackCode::SimdX64Unavailable
        );
        assert!(decision.fallback_reason.is_some());
    }

    #[test]
    fn arm64_cpu_feature_flags_normalize_android_linux_aliases() {
        let features = [
            "asimd", "asimdhp", "asimdfhm", "asimddp", "i8mm", "sve", "sve2", "sme", "sme2",
            "svei8mm", "atomics", "crc32", "mops", "fp", "aes", "bf16", "asimdrdm", "jscvt",
            "fcma",
        ]
        .iter()
        .map(|value| (*value).to_owned())
        .collect::<Vec<_>>();

        let flags = cpu_feature_flags(&features);

        assert_eq!(flags & CPU_FEATURE_NEON, CPU_FEATURE_NEON);
        assert_eq!(flags & CPU_FEATURE_FP, CPU_FEATURE_FP);
        assert_eq!(flags & CPU_FEATURE_FP16, CPU_FEATURE_FP16);
        assert_eq!(flags & CPU_FEATURE_FHM, CPU_FEATURE_FHM);
        assert_eq!(flags & CPU_FEATURE_DOTPROD, CPU_FEATURE_DOTPROD);
        assert_eq!(flags & CPU_FEATURE_I8MM, CPU_FEATURE_I8MM);
        assert_eq!(flags & CPU_FEATURE_SVE, CPU_FEATURE_SVE);
        assert_eq!(flags & CPU_FEATURE_SVE2, CPU_FEATURE_SVE2);
        assert_eq!(flags & CPU_FEATURE_SVE_I8MM, CPU_FEATURE_SVE_I8MM);
        assert_eq!(flags & CPU_FEATURE_SME, CPU_FEATURE_SME);
        assert_eq!(flags & CPU_FEATURE_SME2, CPU_FEATURE_SME2);
        assert_eq!(flags & CPU_FEATURE_LSE, CPU_FEATURE_LSE);
        assert_eq!(flags & CPU_FEATURE_CRC, CPU_FEATURE_CRC);
        assert_eq!(flags & CPU_FEATURE_MOPS, CPU_FEATURE_MOPS);
        assert_eq!(flags & CPU_FEATURE_AES, CPU_FEATURE_AES);
        assert_eq!(flags & CPU_FEATURE_BF16, CPU_FEATURE_BF16);
        assert_eq!(flags & CPU_FEATURE_RDM, CPU_FEATURE_RDM);
        assert_eq!(flags & CPU_FEATURE_JSCVT, CPU_FEATURE_JSCVT);
        assert_eq!(flags & CPU_FEATURE_FCMA, CPU_FEATURE_FCMA);
    }

    #[test]
    fn arm64_cpu_feature_catalog_separates_active_reserved_and_utility_capabilities() {
        let catalog = arm64_cpu_feature_catalog();
        let neon = catalog
            .iter()
            .find(|entry| entry.canonical_name == "neon")
            .expect("neon catalog entry");
        let sve2 = catalog
            .iter()
            .find(|entry| entry.canonical_name == "sve2")
            .expect("sve2 catalog entry");
        let mops = catalog
            .iter()
            .find(|entry| entry.canonical_name == "mops")
            .expect("mops catalog entry");

        assert_eq!(neon.status, CpuFeatureUseStatus::ActiveSolverCapability);
        assert_eq!(sve2.status, CpuFeatureUseStatus::ReservedUntilKernelExists);
        assert_eq!(
            mops.status,
            CpuFeatureUseStatus::RuntimeUtilityNoCurrentHotPath
        );
    }

    #[test]
    fn arm64_kernel_catalog_tracks_broad_experimental_isa_lanes() {
        let catalog = arm64_kernel_catalog();
        let neon = catalog
            .iter()
            .find(|entry| entry.path_id == "simd.arm64.neon-f64-pairwise")
            .expect("active neon kernel entry");
        let tiled_neon = catalog
            .iter()
            .find(|entry| entry.path_id == "simd.arm64.neon-f64-tiled-pairwise")
            .expect("active tiled neon kernel entry");
        let candidate_paths = catalog
            .iter()
            .filter(|entry| entry.readiness == Arm64KernelReadiness::Candidate)
            .map(|entry| entry.path_id)
            .collect::<Vec<_>>();

        assert_eq!(neon.readiness, Arm64KernelReadiness::Active);
        assert_eq!(neon.required_features, &["neon"]);
        assert_eq!(tiled_neon.readiness, Arm64KernelReadiness::Active);
        assert_eq!(tiled_neon.required_features, &["neon"]);
        assert!(candidate_paths.contains(&"simd.arm64.sve-f64-batch-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.sve2-f64-batch-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.sme-tiled-f64-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.sme2-tiled-f64-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.dotprod-packed-assist-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.i8mm-packed-assist-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.bf16-forecast-assist-candidate"));
        assert!(candidate_paths.contains(&"simd.arm64.fp16-visual-assist-candidate"));
    }

    #[test]
    fn arm64_kernel_availability_marks_feature_qualified_candidate_lanes() {
        let features = ["asimd", "sve2", "sme2", "i8mm", "bf16"]
            .iter()
            .map(|value| (*value).to_owned())
            .collect::<Vec<_>>();

        let availability = arm64_kernel_availability(&features);

        assert!(availability
            .eligible_candidate_paths
            .contains(&"simd.arm64.sve2-f64-batch-candidate"));
        assert!(availability
            .eligible_candidate_paths
            .contains(&"simd.arm64.sme2-tiled-f64-candidate"));
        assert!(availability
            .eligible_candidate_paths
            .contains(&"simd.arm64.i8mm-packed-assist-candidate"));
        assert!(availability
            .eligible_candidate_paths
            .contains(&"simd.arm64.bf16-forecast-assist-candidate"));
        assert!(availability
            .blocked_candidate_paths
            .contains(&"simd.arm64.sve-f64-batch-candidate"));
    }

    #[test]
    fn simd_arm64_dispatch_selects_tiled_neon_for_large_body_sets() {
        let requested = SolverBackend::SimdArm64;
        let small_scene = dispatch_solver_backend_for_host_with_body_count(
            &requested,
            &["neon".to_owned()],
            true,
            false,
            true,
            32,
        );
        let large_scene = dispatch_solver_backend_for_host_with_body_count(
            &requested,
            &["neon".to_owned()],
            true,
            false,
            true,
            128,
        );

        assert_eq!(small_scene.path_id, "simd.arm64.neon-f64-pairwise");
        assert_eq!(
            small_scene.arm64_gravity_kernel,
            Some(Arm64GravityKernel::NeonF64Pairwise)
        );
        assert_eq!(large_scene.path_id, "simd.arm64.neon-f64-tiled-pairwise");
        assert_eq!(
            large_scene.arm64_gravity_kernel,
            Some(Arm64GravityKernel::NeonF64TiledPairwise)
        );
    }

    #[test]
    fn solver_schedule_report_truthfully_marks_adaptive_tiling_as_candidate() {
        let scalar_schedule = solver_schedule_report(192, false);
        let arm64_tiny_schedule = solver_schedule_report(8, true);
        let arm64_large_schedule = solver_schedule_report(192, true);

        assert_eq!(scalar_schedule.mode, SolverScheduleMode::SingleWorker);
        assert_eq!(arm64_tiny_schedule.mode, SolverScheduleMode::SingleWorker);
        assert_eq!(arm64_large_schedule.active_workers, 1);
        assert_eq!(arm64_large_schedule.body_count, 192);
        assert_eq!(arm64_large_schedule.estimated_pair_count, 18_336);

        if arm64_large_schedule.candidate_workers > 1 {
            assert_eq!(
                arm64_large_schedule.mode,
                SolverScheduleMode::AdaptiveTiledCandidate
            );
        } else {
            assert_eq!(arm64_large_schedule.mode, SolverScheduleMode::SingleWorker);
        }
    }

    #[test]
    fn solver_execution_report_preserves_detected_extensions_without_claiming_sve_or_sme_kernel() {
        let features = ["asimd", "sve2", "sme2"]
            .iter()
            .map(|value| (*value).to_owned())
            .collect::<Vec<_>>();

        let report = solver_execution_report_for_backend(&SolverBackend::SimdArm64, &features);

        if cfg!(target_arch = "aarch64") && arm64_neon_runtime_available() {
            assert_eq!(report.effective_backend, SolverBackend::SimdArm64);
            assert_eq!(report.path_id, "simd.arm64.neon-f64-pairwise");
        } else {
            assert_eq!(report.effective_backend, SolverBackend::ReferenceScalar);
            let expected_fallback = if cfg!(target_arch = "aarch64") {
                SolverFallbackCode::SimdArm64MissingNeon
            } else {
                SolverFallbackCode::SimdArm64OnNonAarch64Host
            };
            assert_eq!(report.fallback_code, expected_fallback);
        }
        assert!(report.active_cpu_features.contains(&"neon".to_owned()));
        assert!(report.active_cpu_features.contains(&"sve2".to_owned()));
        assert!(report.active_cpu_features.contains(&"sme2".to_owned()));
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
    fn arm64_tiled_neon_kernel_is_scalar_oracle_equivalent_for_large_scenes() {
        if !cfg!(target_arch = "aarch64") || !arm64_neon_runtime_available() {
            return;
        }

        let policy = test_policy();
        let initial_bodies = dense_kernel_parity_scenario(128);
        let mut scalar_bodies = initial_bodies.clone();
        let mut tiled_bodies = initial_bodies;

        let scalar_invariants = advance_authoritative_scalar(&policy, &mut scalar_bodies, 1_200.0);
        let tiled_invariants = advance_authoritative_arm64(
            &policy,
            &mut tiled_bodies,
            1_200.0,
            Arm64GravityKernel::NeonF64TiledPairwise,
        );
        let metrics = super::solver_equivalence_metrics(&tiled_bodies, &scalar_bodies);
        let energy_scale = scalar_invariants.total_energy_j.abs().max(1.0);
        let energy_relative_error =
            (tiled_invariants.total_energy_j - scalar_invariants.total_energy_j).abs()
                / energy_scale;

        assert_eq!(metrics.compared_components, 128 * 6);
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
            energy_relative_error <= 1.0e-12,
            "relative total energy error {energy_relative_error} exceeded tolerance"
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

        let neon_active =
            features.iter().any(|feature| feature == "neon") && arm64_neon_runtime_available();

        if neon_active {
            assert_eq!(report.effective_backend, SolverBackend::SimdArm64);
        } else {
            assert_eq!(report.effective_backend, SolverBackend::ReferenceScalar);
            assert!(report.fallback_reason.is_some());
        }

        if neon_active {
            assert_eq!(report.path_id, "simd.arm64.neon-f64-pairwise");
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

    fn dense_kernel_parity_scenario(body_count: usize) -> Vec<MassiveBodyState> {
        (0..body_count)
            .map(|index| {
                let ring = (index / 16) as f64;
                let slot = (index % 16) as f64;
                let theta = slot * std::f64::consts::TAU / 16.0;
                let radius = 1.0e9 + ring * 2.5e7;
                let mass_kg = 1.0e18 + (index as f64 % 11.0) * 1.0e16;
                MassiveBodyState {
                    mass_kg,
                    position_m: Vector3d {
                        x: radius * theta.cos(),
                        y: radius * theta.sin(),
                        z: (ring - 4.0) * 5.0e6,
                    },
                    velocity_mps: Vector3d {
                        x: -theta.sin() * 720.0,
                        y: theta.cos() * 720.0,
                        z: (slot - 8.0) * 0.2,
                    },
                }
            })
            .collect()
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
