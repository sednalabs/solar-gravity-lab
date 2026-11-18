//! Explicit conservative-coast and encounter solvers with one gravity law.
//!
//! Yoshida's fourth-order composition uses signed intermediate gravity steps;
//! it is never used to run fuel consumption or rigid-body contacts backwards.
//! Dormand–Prince controls a local error estimate; it is not symplectic and its
//! tolerances do not assert a corresponding global or ephemeris accuracy.
use crate::{
    fixed_step_plan_with_source_count, GravityKernel, GravityScratch, IntegratorKind,
    MassiveBodyState, PhysicsInvariants, PhysicsPolicy, SolverBackend, SolverExecutionReport,
    G_M3_PER_KG_S2, MAX_INCREMENTAL_STEPS,
};
use solarlab_domain::science::{AdaptiveTolerance, ScientificSettings, ZonalHarmonic};
use solarlab_domain::spacecraft::{add, dot, finite, magnitude, scale, sub};
use solarlab_domain::{BodyId, Vector3d};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ScientificError {
    InvalidSettings,
    InvalidState,
    InvalidFlightState,
    BodyOrderMismatch,
    UnknownHarmonicBody,
    HarmonicRequiresMassiveSource,
    InsideHarmonicReferenceRadius,
    WorkLimit,
    StepTooSmall,
    NonFinite,
}

impl std::fmt::Display for ScientificError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::InvalidSettings => "invalid or unsupported scientific settings",
            Self::InvalidState => "nonfinite position/velocity or negative source mass",
            Self::InvalidFlightState => {
                "spacecraft controls, binding or local contact state is invalid"
            }
            Self::BodyOrderMismatch => "body identifiers do not match the integration plan",
            Self::UnknownHarmonicBody => "gravity model references an unknown body",
            Self::HarmonicRequiresMassiveSource => {
                "J2 requires a positive gravitational source mass"
            }
            Self::InsideHarmonicReferenceRadius => {
                "J2 exterior model was entered below its reference radius"
            }
            Self::WorkLimit => "integration exhausted its bounded work budget",
            Self::StepTooSmall => {
                "requested local accuracy or encounter bound needs a smaller step"
            }
            Self::NonFinite => "integration produced a nonfinite intermediate state",
        })
    }
}

impl std::error::Error for ScientificError {}

// Existing contact/guidance functions use a unit error. Keep those legacy
// signatures while preserving specific orbital errors in the checked flow.
impl From<()> for ScientificError {
    fn from(_: ()) -> Self {
        Self::InvalidFlightState
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct ScientificStepReport {
    pub requested_integrator: IntegratorKind,
    pub effective_integrator: IntegratorKind,
    pub nonconservative_split: bool,
    pub accepted_steps: u64,
    pub rejected_steps: u64,
    pub force_evaluations: u64,
    pub minimum_step_seconds: f64,
    pub maximum_step_seconds: f64,
}

impl ScientificStepReport {
    fn new(plan: &ScientificPlan) -> Self {
        Self {
            requested_integrator: plan.settings.integrator,
            effective_integrator: plan.effective_integrator,
            nonconservative_split: plan.nonconservative_split,
            accepted_steps: 0,
            rejected_steps: 0,
            force_evaluations: 0,
            minimum_step_seconds: 0.0,
            maximum_step_seconds: 0.0,
        }
    }

    pub(crate) fn accepted(&mut self, dt: f64) {
        self.minimum_step_seconds = if self.accepted_steps == 0 {
            dt.abs()
        } else {
            self.minimum_step_seconds.min(dt.abs())
        };
        self.maximum_step_seconds = self.maximum_step_seconds.max(dt.abs());
        self.accepted_steps += 1;
    }

    pub fn accumulate(&mut self, other: &Self) {
        if other.accepted_steps > 0 {
            self.minimum_step_seconds = if self.accepted_steps == 0 {
                other.minimum_step_seconds
            } else {
                self.minimum_step_seconds.min(other.minimum_step_seconds)
            };
            self.maximum_step_seconds = self.maximum_step_seconds.max(other.maximum_step_seconds);
        }
        self.accepted_steps += other.accepted_steps;
        self.rejected_steps += other.rejected_steps;
        self.force_evaluations += other.force_evaluations;
        self.nonconservative_split |= other.nonconservative_split;
        if other.nonconservative_split {
            self.effective_integrator = IntegratorKind::LeapfrogKickDriftKick;
        }
    }

    /// A mixed coast/powered operation reports that it used the order-two
    /// nonconservative split. Counts still cover all accepted gravity steps.
    pub fn apply_to_solver_report(&self, report: &mut SolverExecutionReport) {
        report.requested_integrator = self.requested_integrator;
        report.effective_integrator = self.effective_integrator;
        report.nonconservative_split = self.nonconservative_split;
    }
}

#[derive(Clone, Debug)]
struct BoundHarmonic {
    index: usize,
    model: ZonalHarmonic,
}

#[derive(Clone, Debug)]
pub(crate) struct ScientificPlan {
    pub(crate) settings: ScientificSettings,
    pub(crate) effective_integrator: IntegratorKind,
    pub(crate) nonconservative_split: bool,
    harmonics: Vec<BoundHarmonic>,
}

impl ScientificPlan {
    pub(crate) fn for_integrator(integrator: IntegratorKind) -> Self {
        Self {
            settings: ScientificSettings {
                integrator,
                ..ScientificSettings::default()
            },
            effective_integrator: integrator,
            nonconservative_split: false,
            harmonics: Vec::new(),
        }
    }
}

impl crate::FixedStepPlan {
    /// Bind authored gravity source IDs once, to the exact solver body ordering.
    /// No appearance metadata is read and no body-name preset is guessed.
    pub fn with_scientific_settings(
        mut self,
        settings: &ScientificSettings,
        body_ids: &[BodyId],
    ) -> Result<Self, ScientificError> {
        if !settings.valid() {
            return Err(ScientificError::InvalidSettings);
        }
        let mut unique_ids = std::collections::HashSet::with_capacity(body_ids.len());
        if body_ids.len() != self.body_count || body_ids.iter().any(|id| !unique_ids.insert(&id.0))
        {
            return Err(ScientificError::BodyOrderMismatch);
        }
        let harmonics = settings
            .harmonics
            .iter()
            .map(|model| {
                let index = body_ids
                    .iter()
                    .position(|id| *id == model.body_id)
                    .ok_or(ScientificError::UnknownHarmonicBody)?;
                Ok(BoundHarmonic {
                    index,
                    model: model.clone(),
                })
            })
            .collect::<Result<Vec<_>, ScientificError>>()?;
        self.science = ScientificPlan {
            settings: settings.clone(),
            effective_integrator: settings.integrator,
            nonconservative_split: false,
            harmonics,
        };
        Ok(self)
    }

    #[must_use]
    pub fn scientific_settings(&self) -> &ScientificSettings {
        &self.science.settings
    }

    #[must_use]
    pub fn effective_integrator(&self) -> IntegratorKind {
        self.science.effective_integrator
    }

    #[must_use]
    pub fn empty_scientific_report(&self) -> ScientificStepReport {
        ScientificStepReport::new(&self.science)
    }

    /// Keep the same forces and requested choice, but use a positive-time KDK
    /// gravity split while propulsion or local contacts are advanced separately.
    #[must_use]
    pub fn for_nonconservative_split(&self) -> Self {
        let mut result = self.clone();
        result.science.effective_integrator = IntegratorKind::LeapfrogKickDriftKick;
        result.science.nonconservative_split = true;
        result
    }

    pub(crate) fn kernel(&self) -> GravityKernel {
        match self.decision.effective_backend {
            SolverBackend::SimdArm64 => GravityKernel::Arm64(
                self.decision
                    .arm64_gravity_kernel
                    .unwrap_or(crate::Arm64GravityKernel::PortableScalarOracle),
            ),
            SolverBackend::SimdX64 => GravityKernel::X64Avx2,
            SolverBackend::ReferenceScalar => GravityKernel::Scalar,
        }
    }

    /// Use this force entry point in contact splitting as well as orbital flow.
    pub(crate) fn gravity_accelerations_into(
        &self,
        bodies: &[MassiveBodyState],
        sources: &[usize],
        accelerations: &mut [Vector3d],
        kernel: GravityKernel,
        scratch: &mut GravityScratch,
    ) -> Result<(), ()> {
        evaluate_accelerations(
            bodies,
            sources,
            accelerations,
            kernel,
            scratch,
            &self.science,
        )
        .map_err(|_| ())
    }

    /// Energy includes J2 potential, unlike the historical point-mass diagnostic.
    /// Fixed oblate axes exchange angular momentum with an unmodelled spin
    /// reservoir, so the full orbital angular-momentum vector is not invariant.
    pub fn compute_invariants(
        &self,
        bodies: &[MassiveBodyState],
    ) -> Result<PhysicsInvariants, ScientificError> {
        if bodies.len() != self.body_count {
            return Err(ScientificError::BodyOrderMismatch);
        }
        validate_state(bodies)?;
        let mut result = crate::compute_invariants(bodies);
        let mut correction = crate::CompensatedSum::default();
        for harmonic in &self.science.harmonics {
            let source = &bodies[harmonic.index];
            if source.mass_kg <= 0.0 {
                return Err(ScientificError::HarmonicRequiresMassiveSource);
            }
            if harmonic.model.j2 == 0.0 {
                continue;
            }
            for (index, target) in bodies.iter().enumerate() {
                if index == harmonic.index {
                    continue;
                }
                // Check passive targets too: diagnostics must not certify a
                // configuration outside the exterior model's physical domain.
                let (_, potential) = j2_field(
                    &harmonic.model,
                    source.mass_kg,
                    sub(target.position_m, source.position_m),
                )?;
                correction.add(potential * target.mass_kg);
            }
        }
        result.total_energy_j += correction.total();
        if !result.total_energy_j.is_finite() {
            return Err(ScientificError::NonFinite);
        }
        Ok(result)
    }
}

/// Checked, bounded segment API. A numerical rejection never changes `bodies`.
pub fn advance_fixed_steps_checked(
    plan: &crate::FixedStepPlan,
    bodies: &mut [MassiveBodyState],
    count: usize,
) -> Result<ScientificStepReport, ScientificError> {
    if count > MAX_INCREMENTAL_STEPS {
        return Err(ScientificError::WorkLimit);
    }
    if bodies.len() != plan.body_count {
        return Err(ScientificError::BodyOrderMismatch);
    }
    validate_state(bodies)?;
    let mut staged = bodies.to_vec();
    let report = advance_selected_steps(plan, &mut staged, count, plan.kernel())?;
    validate_state(&staged)?;
    bodies.copy_from_slice(&staged);
    Ok(report)
}

/// Blocking checked entry point for forecasts and independent comparison tools.
/// Runtime incremental advances can use the same plan and bounded segment API.
pub fn advance_authoritative_scientific(
    policy: &PhysicsPolicy,
    settings: &ScientificSettings,
    body_ids: &[BodyId],
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
    features: &[String],
) -> Result<
    (
        PhysicsInvariants,
        SolverExecutionReport,
        ScientificStepReport,
    ),
    ScientificError,
> {
    let plan = fixed_step_plan_with_source_count(
        policy,
        delta_seconds,
        bodies.len(),
        bodies.iter().filter(|body| body.mass_kg != 0.0).count(),
        features,
    )
    .ok_or(ScientificError::InvalidSettings)?
    .with_scientific_settings(settings, body_ids)?;
    validate_state(bodies)?;
    let mut staged = bodies.to_vec();
    let mut report = ScientificStepReport::new(&plan.science);
    let mut remaining = plan.total_steps;
    while remaining > 0 {
        let count = remaining.min(MAX_INCREMENTAL_STEPS);
        report.accumulate(&advance_selected_steps(
            &plan,
            &mut staged,
            count,
            plan.kernel(),
        )?);
        remaining -= count;
    }
    validate_state(&staged)?;
    let invariants = plan.compute_invariants(&staged)?;
    bodies.copy_from_slice(&staged);
    Ok((invariants, plan.report(), report))
}

fn validate_state(bodies: &[MassiveBodyState]) -> Result<(), ScientificError> {
    if bodies.iter().any(|body| {
        !body.mass_kg.is_finite()
            || body.mass_kg < 0.0
            || !finite(body.position_m)
            || !finite(body.velocity_mps)
    }) {
        Err(ScientificError::InvalidState)
    } else {
        Ok(())
    }
}

// Keep old point-mass APIs behaviorally honest when callers select a method.
// Their signatures predate recoverable rejection; the public checked API is
// the required path when failure needs to be reported to a pilot or forecast.
pub(crate) fn advance_legacy_method(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
    kernel: GravityKernel,
) -> Result<(), ScientificError> {
    let plan = fixed_step_plan_with_source_count(
        policy,
        delta_seconds,
        bodies.len(),
        bodies.iter().filter(|body| body.mass_kg != 0.0).count(),
        &[],
    )
    .ok_or(ScientificError::InvalidSettings)?;
    validate_state(bodies)?;
    let mut staged = bodies.to_vec();
    let mut remaining = plan.total_steps;
    while remaining > 0 {
        let count = remaining.min(MAX_INCREMENTAL_STEPS);
        advance_selected_steps(&plan, &mut staged, count, kernel)?;
        remaining -= count;
    }
    validate_state(&staged)?;
    bodies.copy_from_slice(&staged);
    Ok(())
}

/// Acceleration and specific potential of the J2 term, in arbitrary inertial axes.
/// The caller adds the monopole; tests differentiate the potential independently.
pub fn j2_field(
    model: &ZonalHarmonic,
    source_mass_kg: f64,
    relative_position_m: Vector3d,
) -> Result<(Vector3d, f64), ScientificError> {
    if !model.valid()
        || !source_mass_kg.is_finite()
        || source_mass_kg <= 0.0
        || !finite(relative_position_m)
    {
        return Err(ScientificError::InvalidSettings);
    }
    if model.j2 == 0.0 {
        return Ok((Vector3d::default(), 0.0));
    }
    let r = magnitude(relative_position_m);
    if r < model.reference_radius_m {
        return Err(ScientificError::InsideHarmonicReferenceRadius);
    }
    let radial = scale(relative_position_m, 1.0 / r);
    let sine_latitude = dot(model.axis_ws, radial);
    let ratio = model.reference_radius_m / r;
    let strength = G_M3_PER_KG_S2 * source_mass_kg * model.j2 * ratio * ratio / r;
    let acceleration = scale(
        sub(
            scale(radial, 5.0 * sine_latitude * sine_latitude - 1.0),
            scale(model.axis_ws, 2.0 * sine_latitude),
        ),
        1.5 * strength / r,
    );
    let potential = 0.5 * strength * (3.0 * sine_latitude * sine_latitude - 1.0);
    if !finite(acceleration) || !potential.is_finite() {
        return Err(ScientificError::NonFinite);
    }
    Ok((acceleration, potential))
}

fn evaluate_accelerations(
    bodies: &[MassiveBodyState],
    sources: &[usize],
    accelerations: &mut [Vector3d],
    kernel: GravityKernel,
    scratch: &mut GravityScratch,
    science: &ScientificPlan,
) -> Result<(), ScientificError> {
    crate::gravity_accelerations_into(bodies, sources, accelerations, kernel, scratch);
    for harmonic in &science.harmonics {
        let source = &bodies[harmonic.index];
        if source.mass_kg <= 0.0 {
            return Err(ScientificError::HarmonicRequiresMassiveSource);
        }
        for (index, target) in bodies.iter().enumerate() {
            if index == harmonic.index {
                continue;
            }
            let (acceleration, _) = j2_field(
                &harmonic.model,
                source.mass_kg,
                sub(target.position_m, source.position_m),
            )?;
            accelerations[index] = add(accelerations[index], acceleration);
            // The opposite force on the source maintains translational momentum.
            // A passive tracer receives acceleration but has no backreaction.
            accelerations[harmonic.index] = sub(
                accelerations[harmonic.index],
                scale(acceleration, target.mass_kg / source.mass_kg),
            );
        }
    }
    if accelerations.iter().any(|a| !finite(*a)) {
        return Err(ScientificError::NonFinite);
    }
    Ok(())
}

fn validate_harmonic_domain(
    bodies: &[MassiveBodyState],
    science: &ScientificPlan,
) -> Result<(), ScientificError> {
    for harmonic in &science.harmonics {
        let source = &bodies[harmonic.index];
        if source.mass_kg <= 0.0 {
            return Err(ScientificError::HarmonicRequiresMassiveSource);
        }
        if harmonic.model.j2 == 0.0 {
            continue;
        }
        for (index, body) in bodies.iter().enumerate() {
            if index != harmonic.index
                && magnitude(sub(body.position_m, source.position_m))
                    < harmonic.model.reference_radius_m
            {
                return Err(ScientificError::InsideHarmonicReferenceRadius);
            }
        }
    }
    Ok(())
}

pub(crate) fn advance_selected_steps(
    plan: &crate::FixedStepPlan,
    bodies: &mut [MassiveBodyState],
    count: usize,
    kernel: GravityKernel,
) -> Result<ScientificStepReport, ScientificError> {
    let mut report = ScientificStepReport::new(&plan.science);
    if count == 0 || bodies.is_empty() {
        return Ok(report);
    }
    if plan.science.effective_integrator == IntegratorKind::LeapfrogKickDriftKick
        && plan.science.harmonics.is_empty()
    {
        // Preserve the exact established SIMD/scalar trajectory and hot path.
        crate::integrate_fixed_steps_with_kernel(bodies, plan.dt_seconds, count, kernel);
        report.accepted_steps = count as u64;
        report.force_evaluations = count as u64 + 1;
        report.minimum_step_seconds = plan.dt_seconds;
        report.maximum_step_seconds = plan.dt_seconds;
        return Ok(report);
    }
    // Unlike ordinary KDK, these methods can reject a trial. They work on a
    // private segment copy so a failed adaptive/J2 segment is never half-applied.
    let mut staged = bodies.to_vec();
    validate_state(&staged)?;
    // Invalid input is distinct from a valid orbit whose *trial* overshoots a
    // boundary. Only the latter is eligible for adaptive step subdivision.
    validate_harmonic_domain(&staged, &plan.science)?;
    let sources = crate::source_indices(&staged);
    let mut scratch = GravityScratch::default();
    for _ in 0..count {
        match plan.science.effective_integrator {
            IntegratorKind::LeapfrogKickDriftKick => {
                kdk_step(
                    &mut staged,
                    plan.dt_seconds,
                    &sources,
                    kernel,
                    &mut scratch,
                    &plan.science,
                )?;
                report.force_evaluations += 2;
                report.accepted(plan.dt_seconds);
            }
            IntegratorKind::YoshidaFourthOrder => {
                let cube_root_two = 2.0_f64.cbrt();
                let outer = 1.0 / (2.0 - cube_root_two);
                let inner = -cube_root_two * outer;
                for coefficient in [outer, inner, outer] {
                    kdk_step(
                        &mut staged,
                        coefficient * plan.dt_seconds,
                        &sources,
                        kernel,
                        &mut scratch,
                        &plan.science,
                    )?;
                }
                report.force_evaluations += 6;
                report.accepted(plan.dt_seconds);
            }
            IntegratorKind::DormandPrince54 => {
                adaptive_interval(
                    &mut staged,
                    plan.dt_seconds,
                    &sources,
                    kernel,
                    &mut scratch,
                    &plan.science,
                    &mut report,
                )?;
            }
            IntegratorKind::AdaptiveSymplectic => return Err(ScientificError::InvalidSettings),
        }
    }
    validate_state(&staged)?;
    bodies.copy_from_slice(&staged);
    Ok(report)
}

fn kdk_step(
    bodies: &mut [MassiveBodyState],
    dt: f64,
    sources: &[usize],
    kernel: GravityKernel,
    scratch: &mut GravityScratch,
    science: &ScientificPlan,
) -> Result<(), ScientificError> {
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    evaluate_accelerations(
        bodies,
        sources,
        &mut accelerations,
        kernel,
        scratch,
        science,
    )?;
    for (body, acceleration) in bodies.iter_mut().zip(&accelerations) {
        body.velocity_mps = add(body.velocity_mps, scale(*acceleration, 0.5 * dt));
        body.position_m = add(body.position_m, scale(body.velocity_mps, dt));
    }
    evaluate_accelerations(
        bodies,
        sources,
        &mut accelerations,
        kernel,
        scratch,
        science,
    )?;
    for (body, acceleration) in bodies.iter_mut().zip(&accelerations) {
        body.velocity_mps = add(body.velocity_mps, scale(*acceleration, 0.5 * dt));
    }
    Ok(())
}

#[derive(Clone, Copy, Default)]
struct Derivative {
    position: Vector3d,
    velocity: Vector3d,
}

// Dormand & Prince (1980), fifth-order solution with fourth-order error estimate.
const A: [[f64; 6]; 7] = [
    [0.0; 6],
    [1.0 / 5.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    [3.0 / 40.0, 9.0 / 40.0, 0.0, 0.0, 0.0, 0.0],
    [44.0 / 45.0, -56.0 / 15.0, 32.0 / 9.0, 0.0, 0.0, 0.0],
    [
        19372.0 / 6561.0,
        -25360.0 / 2187.0,
        64448.0 / 6561.0,
        -212.0 / 729.0,
        0.0,
        0.0,
    ],
    [
        9017.0 / 3168.0,
        -355.0 / 33.0,
        46732.0 / 5247.0,
        49.0 / 176.0,
        -5103.0 / 18656.0,
        0.0,
    ],
    [
        35.0 / 384.0,
        0.0,
        500.0 / 1113.0,
        125.0 / 192.0,
        -2187.0 / 6784.0,
        11.0 / 84.0,
    ],
];
const ERROR: [f64; 7] = [
    35.0 / 384.0 - 5179.0 / 57600.0,
    0.0,
    500.0 / 1113.0 - 7571.0 / 16695.0,
    125.0 / 192.0 - 393.0 / 640.0,
    -2187.0 / 6784.0 + 92097.0 / 339200.0,
    11.0 / 84.0 - 187.0 / 2100.0,
    -1.0 / 40.0,
];

fn adaptive_interval(
    bodies: &mut [MassiveBodyState],
    duration: f64,
    sources: &[usize],
    kernel: GravityKernel,
    scratch: &mut GravityScratch,
    science: &ScientificPlan,
    report: &mut ScientificStepReport,
) -> Result<(), ScientificError> {
    let tolerance = &science.settings.adaptive;
    // Galilean recentering makes error control independent of large absolute
    // barycentric coordinates and common boost velocity. Restore once per outer
    // interval, not once per adaptive trial.
    let anchor = bodies
        .iter()
        .enumerate()
        .max_by(|(_, a), (_, b)| a.mass_kg.total_cmp(&b.mass_kg))
        .map_or(0, |(index, _)| index);
    let origin_position = bodies[anchor].position_m;
    let origin_velocity = bodies[anchor].velocity_mps;
    let mut state = bodies.to_vec();
    for body in &mut state {
        body.position_m = sub(body.position_m, origin_position);
        body.velocity_mps = sub(body.velocity_mps, origin_velocity);
    }
    let mut trial = state.clone();
    let mut stages = vec![vec![Derivative::default(); bodies.len()]; 7];
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    let mut elapsed = 0.0;
    let mut step = duration;
    let mut attempts = 0_u32;
    while elapsed < duration {
        if attempts >= tolerance.max_attempts_per_interval {
            return Err(ScientificError::WorkLimit);
        }
        attempts += 1;
        let remaining = duration - elapsed;
        let encounter_limit = encounter_step_limit(&state, sources, tolerance.encounter_fraction)?;
        step = step.min(remaining).min(encounter_limit);
        if !step.is_finite()
            || step <= 0.0
            || elapsed + step == elapsed
            || (step < tolerance.minimum_step_seconds && remaining > tolerance.minimum_step_seconds)
        {
            return Err(ScientificError::StepTooSmall);
        }
        let attempt = dormand_prince_trial(
            &state,
            &mut trial,
            &mut stages,
            &mut accelerations,
            step,
            sources,
            kernel,
            scratch,
            science,
            report,
        );
        let error = match attempt {
            Ok(()) => normalized_error(&state, &trial, &stages, step, sources, tolerance),
            // A trial may overshoot a boundary even though the true trajectory
            // remains exterior. Subdivide; never turn off J2 to accept the step.
            Err(ScientificError::InsideHarmonicReferenceRadius | ScientificError::NonFinite) => {
                f64::INFINITY
            }
            Err(error) => return Err(error),
        };
        if error.is_finite() && error <= 1.0 {
            state.copy_from_slice(&trial);
            elapsed = if step == remaining {
                duration
            } else {
                elapsed + step
            };
            report.accepted(step);
            let growth = if error == 0.0 {
                5.0
            } else {
                (0.9 * error.powf(-0.2)).clamp(0.2, 5.0)
            };
            step *= growth;
        } else {
            report.rejected_steps += 1;
            if step <= tolerance.minimum_step_seconds {
                return Err(ScientificError::StepTooSmall);
            }
            let shrink = if error.is_finite() {
                (0.9 * error.powf(-0.2)).clamp(0.1, 0.5)
            } else {
                0.1
            };
            step = (step * shrink).max(tolerance.minimum_step_seconds);
        }
    }
    for body in &mut state {
        body.position_m = add(
            origin_position,
            add(scale(origin_velocity, duration), body.position_m),
        );
        body.velocity_mps = add(origin_velocity, body.velocity_mps);
    }
    validate_state(&state)?;
    bodies.copy_from_slice(&state);
    Ok(())
}

fn dormand_prince_trial(
    state: &[MassiveBodyState],
    trial: &mut [MassiveBodyState],
    stages: &mut [Vec<Derivative>],
    accelerations: &mut [Vector3d],
    step: f64,
    sources: &[usize],
    kernel: GravityKernel,
    scratch: &mut GravityScratch,
    science: &ScientificPlan,
    report: &mut ScientificStepReport,
) -> Result<(), ScientificError> {
    for stage in 0..7 {
        trial.copy_from_slice(state);
        for (previous, derivatives) in stages[..stage].iter().enumerate() {
            let weight = step * A[stage][previous];
            for (body, derivative) in trial.iter_mut().zip(derivatives) {
                body.position_m = add(body.position_m, scale(derivative.position, weight));
                body.velocity_mps = add(body.velocity_mps, scale(derivative.velocity, weight));
            }
        }
        report.force_evaluations += 1;
        evaluate_accelerations(trial, sources, accelerations, kernel, scratch, science)?;
        for ((derivative, body), acceleration) in stages[stage]
            .iter_mut()
            .zip(trial.iter())
            .zip(accelerations.iter())
        {
            derivative.position = body.velocity_mps;
            derivative.velocity = *acceleration;
        }
    }
    // The seventh stage is evaluated at the accepted fifth-order endpoint.
    Ok(())
}

fn normalized_error(
    old: &[MassiveBodyState],
    new: &[MassiveBodyState],
    stages: &[Vec<Derivative>],
    step: f64,
    sources: &[usize],
    tolerance: &AdaptiveTolerance,
) -> f64 {
    let mut worst = 0.0_f64;
    for index in 0..old.len() {
        let mut position_error = Vector3d::default();
        let mut velocity_error = Vector3d::default();
        for (stage, coefficient) in stages.iter().zip(ERROR) {
            position_error = add(
                position_error,
                scale(stage[index].position, step * coefficient),
            );
            velocity_error = add(
                velocity_error,
                scale(stage[index].velocity, step * coefficient),
            );
        }
        // Scale by separation/relative speed to the nearest source, so a lunar
        // encounter is not allowed a solar-system-sized error merely because it
        // happens far from the integration origin.
        let nearest = sources
            .iter()
            .copied()
            .filter(|&source| source != index)
            .min_by(|&a, &b| {
                magnitude(sub(old[index].position_m, old[a].position_m))
                    .total_cmp(&magnitude(sub(old[index].position_m, old[b].position_m)))
            });
        let (position_scale, velocity_scale) = if let Some(source) = nearest {
            (
                magnitude(sub(old[index].position_m, old[source].position_m)).max(magnitude(sub(
                    new[index].position_m,
                    new[source].position_m,
                ))),
                magnitude(sub(old[index].velocity_mps, old[source].velocity_mps)).max(magnitude(
                    sub(new[index].velocity_mps, new[source].velocity_mps),
                )),
            )
        } else {
            (0.0, 0.0)
        };
        let position_ratio = magnitude(position_error)
            / (tolerance.absolute_position_m + tolerance.relative * position_scale);
        let velocity_ratio = magnitude(velocity_error)
            / (tolerance.absolute_velocity_mps + tolerance.relative * velocity_scale);
        if !position_ratio.is_finite() || !velocity_ratio.is_finite() {
            return f64::INFINITY;
        }
        worst = worst.max(position_ratio).max(velocity_ratio);
    }
    worst
}

fn encounter_step_limit(
    bodies: &[MassiveBodyState],
    sources: &[usize],
    fraction: f64,
) -> Result<f64, ScientificError> {
    let mut limit = f64::INFINITY;
    for (index, body) in bodies.iter().enumerate() {
        for &source in sources {
            if source == index {
                continue;
            }
            let r = magnitude(sub(body.position_m, bodies[source].position_m));
            let v = magnitude(sub(body.velocity_mps, bodies[source].velocity_mps));
            let mu = G_M3_PER_KG_S2 * (body.mass_kg + bodies[source].mass_kg);
            if !r.is_finite() || r == 0.0 || !v.is_finite() || !mu.is_finite() {
                return Err(ScientificError::NonFinite);
            }
            if v > 0.0 {
                limit = limit.min(fraction * (r / v));
            }
            // Algebraic form avoids cubing an astronomical distance first.
            limit = limit.min(fraction * r * (r / mu).sqrt());
        }
    }
    Ok(limit)
}
