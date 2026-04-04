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

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PhysicsInvariants {
    pub total_energy_j: f64,
    pub linear_momentum_kg_mps: Vector3d,
    pub angular_momentum_kg_m2ps: Vector3d,
    pub barycenter_m: Vector3d,
}
