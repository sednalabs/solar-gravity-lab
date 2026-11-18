use crate::kernels::ForceMode;

pub const ALL_FIXTURES: &[&str] = &[
    "sparse3",
    "canonical15",
    "dense192",
    "dense256",
    "close-encounter",
    "kepler",
];
const SPARSE: &[&str] = &["sparse3", "canonical15"];
const SMALL_SOURCES: &[&str] = &["sparse3", "close-encounter", "kepler"];
const DENSE: &[&str] = &["dense192", "dense256"];
const SCIENCE: &[&str] = &["kepler", "close-encounter"];
const KEPLER: &[&str] = &["kepler"];

#[derive(Clone, Copy, PartialEq)]
pub enum Algorithm {
    Production(bool),
    Leapfrog,
    PersistentWorkers(usize),
    Adaptive,
    Close,
    Yoshida,
    Kepler,
    Radau,
    Rk4,
    Extrapolate(usize),
}

pub struct Case {
    pub id: &'static str,
    pub label: &'static str,
    pub family: &'static str,
    pub recommendations: &'static [u32],
    pub fixtures: &'static [&'static str],
    pub algorithm: Algorithm,
    pub force: ForceMode,
    pub baseline: &'static str,
    pub tolerance: f64,
    pub note: &'static str,
}

impl Case {
    pub fn comparison_kind(&self) -> &'static str {
        if self.physics_model() != "Newtonian" {
            "different_force_model_cost"
        } else if matches!(self.family, "numerical" | "approximate") {
            "accuracy_cost_experiment"
        } else {
            "equivalent_kernel"
        }
    }

    pub fn physics_model(&self) -> &'static str {
        match self.force {
            ForceMode::FirstPostNewtonian => "1PN",
            ForceMode::J2 => "J2",
            ForceMode::Radiation => "radiation",
            _ => "Newtonian",
        }
    }

    pub fn control_scope_note(&self) -> &'static str {
        match self.comparison_kind() {
            "different_force_model_cost" => {
                "The timing control is Newtonian production scalar; the candidate uses a different force model and RK4. This measures added model/integrator cost, not an equivalent-physics speedup. Accuracy uses a converged same-model finer-step reference."
            }
            "accuracy_cost_experiment" => {
                "The control uses Newtonian production scalar at the same simulated duration. Different integration, approximation or precision policies require joint accuracy/cost assessment; raw timing ratios do not establish an equivalent-kernel speedup."
            }
            _ => {
                "Candidate and control use the same Newtonian model, fixture and fixed-step schedule. Timing comparisons remain conditional on the declared state-error budget and observed device conditions."
            }
        }
    }
}

macro_rules! force_case {
    ($id:literal, $label:literal, $mode:expr, $ids:expr, $fixtures:expr, $note:literal) => {
        Case {
            id: $id,
            label: $label,
            family: "cpu",
            recommendations: $ids,
            fixtures: $fixtures,
            algorithm: Algorithm::Leapfrog,
            force: $mode,
            baseline: "production-scalar",
            tolerance: 2.0e-11,
            note: $note,
        }
    };
}

pub const CASES: &[Case] = &[
    Case { id:"production-selected", label:"Production dispatch", family:"cpu", recommendations:&[3,10,18], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::Production(true), force:ForceMode::Direct, baseline:"production-scalar", tolerance:2.0e-11,
        note:"Unmodified production dispatch; actual path and worker count returned. Includes PR245 cache policy." },
    Case { id:"production-scalar", label:"Production scalar control", family:"cpu", recommendations:&[2,10], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::Production(false), force:ForceMode::Direct, baseline:"production-scalar", tolerance:0.0,
        note:"Unmodified FP64 scalar production solver, same fixture and schedule." },
    force_case!("source-specialised", "Fixed source count", ForceMode::Specialised, &[11], SMALL_SOURCES, "Constant source-count loops 1..8; FP64. ISA shape not assumed."),
    force_case!("target-block-2", "Portable target block 2", ForceMode::Block(2), &[12], ALL_FIXTURES, "Two target accumulators, aligned packed sources."),
    force_case!("target-block-4", "Portable target block 4", ForceMode::Block(4), &[12], ALL_FIXTURES, "Four target accumulators, aligned packed sources."),
    force_case!("target-block-6", "Portable target block 6", ForceMode::Block(6), &[12], ALL_FIXTURES, "Six target accumulators; register pressure measured."),
    force_case!("target-block-8", "Portable target block 8", ForceMode::Block(8), &[12], ALL_FIXTURES, "Eight target accumulators; register pressure measured."),
    force_case!("target-block-16", "Portable target block 16", ForceMode::Block(16), &[12], ALL_FIXTURES, "Sixteen target accumulators; register pressure measured."),
    force_case!("neon-block-2", "NEON FP64 target block 2", ForceMode::NeonBlock(2), &[12,13], ALL_FIXTURES, "Explicit NEON FP64 sqrt/div/FMA; actual process support required."),
    force_case!("neon-block-4", "NEON FP64 target block 4", ForceMode::NeonBlock(4), &[12,13], ALL_FIXTURES, "Two independent two-lane FP64 vector chains."),
    force_case!("neon-block-6", "NEON FP64 target block 6", ForceMode::NeonBlock(6), &[12,13], ALL_FIXTURES, "Three independent two-lane FP64 vector chains."),
    force_case!("neon-block-8", "NEON FP64 target block 8", ForceMode::NeonBlock(8), &[12,13], ALL_FIXTURES, "Four independent two-lane FP64 vector chains."),
    force_case!("neon-block-16", "NEON FP64 target block 16", ForceMode::NeonBlock(16), &[12,13], ALL_FIXTURES, "Eight vector chains; spills may make this slower."),
    force_case!("neon-source-3", "NEON three-source specialisation", ForceMode::NeonSources3, &[11,13], &["sparse3"], "Three-source constant loop, four targets, actual NEON FP64."),
    force_case!("pipeline-4", "Portable independent FP64 chains", ForceMode::Pipeline4, &[13], ALL_FIXTURES, "Distance, reciprocal and accumulation grouped across targets."),
    force_case!("source-tile-16", "Source-major tile 16", ForceMode::Tile(16), &[14], ALL_FIXTURES, "Source-major 16-target tiles, source order preserved."),
    force_case!("source-tile-32", "Source-major tile 32", ForceMode::Tile(32), &[14], ALL_FIXTURES, "Source-major 32-target tiles, source order preserved."),
    force_case!("source-tile-64", "Source-major tile 64", ForceMode::Tile(64), &[14], ALL_FIXTURES, "Source-major 64-target tiles, source order preserved."),
    force_case!("soa-sources", "Structure-of-arrays source scratch", ForceMode::Soa, &[15], ALL_FIXTURES, "Reusable x/y/z/mu arrays; packing work timed."),
    force_case!("dense-tile-16", "Symmetric dense tile 16", ForceMode::Symmetric(16), &[16], DENSE, "Unique triangular pairs, equal/opposite forces, fixed tile order."),
    force_case!("dense-tile-32", "Symmetric dense tile 32", ForceMode::Symmetric(32), &[16], DENSE, "Unique triangular pairs, equal/opposite forces, fixed tile order."),
    force_case!("dense-tile-64", "Symmetric dense tile 64", ForceMode::Symmetric(64), &[16], DENSE, "Unique triangular pairs, equal/opposite forces, fixed tile order."),
    force_case!("workers-1", "One deterministic worker", ForceMode::Workers(1), &[17,18], ALL_FIXTURES, "Independent target slices preserve per-target summation order."),
    force_case!("workers-2", "Two deterministic workers", ForceMode::Workers(2), &[17,18], ALL_FIXTURES, "Per-sweep thread creation/join charged; not a persistent pool."),
    force_case!("workers-4", "Four deterministic workers", ForceMode::Workers(4), &[17,18], ALL_FIXTURES, "Per-sweep thread creation/join charged; not a persistent pool."),
    force_case!("workers-8", "Eight deterministic workers", ForceMode::Workers(8), &[17,18], ALL_FIXTURES, "Per-sweep thread creation/join charged; not a persistent pool."),
    Case { id:"persistent-workers-2", label:"Two workers retained for the segment", family:"cpu", recommendations:&[17,18], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::PersistentWorkers(2),force:ForceMode::Direct,baseline:"workers-2",tolerance:2.0e-11,
        note:"Owned bounded-channel tasks; snapshot/output allocations reused. Creation and join once per measured segment, caller affinity inherited, fixed source order." },
    Case { id:"persistent-workers-4", label:"Four workers retained for the segment", family:"cpu", recommendations:&[17,18], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::PersistentWorkers(4),force:ForceMode::Direct,baseline:"workers-4",tolerance:2.0e-11,
        note:"Owned bounded-channel tasks; snapshot/output allocations reused. Creation and join once per measured segment, caller affinity inherited, fixed source order." },
    Case { id:"persistent-workers-8", label:"Eight workers retained for the segment", family:"cpu", recommendations:&[17,18], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::PersistentWorkers(8),force:ForceMode::Direct,baseline:"workers-8",tolerance:2.0e-11,
        note:"Owned bounded-channel tasks; snapshot/output allocations reused. Creation and join once per measured segment, caller affinity inherited, fixed source order." },
    force_case!("kahan", "Kahan force accumulation", ForceMode::Kahan, &[49], ALL_FIXTURES, "Compensated FP64 force components; no fast-math."),
    force_case!("neumaier", "Neumaier force accumulation", ForceMode::Neumaier, &[49], ALL_FIXTURES, "Compensated FP64 force components with cancellation correction."),
    force_case!("pairwise", "Deterministic pairwise reduction", ForceMode::Pairwise, &[50], ALL_FIXTURES, "Fixed binary source reduction tree, independent of scheduling."),
    Case { id:"adaptive-doubling", label:"Error-controlled timestep doubling", family:"numerical", recommendations:&[51], fixtures:SCIENCE,
        algorithm:Algorithm::Adaptive, force:ForceMode::Direct, baseline:"production-scalar", tolerance:5.0e-7,
        note:"KDK local error <=1e-10, power-of-two subdivisions, bounded work. Adaptive schedule is not globally symplectic." },
    Case { id:"close-encounter-refine", label:"Close-encounter refinement", family:"numerical", recommendations:&[52], fixtures:SCIENCE,
        algorithm:Algorithm::Close, force:ForceMode::Direct, baseline:"production-scalar", tolerance:2.0e-4,
        note:"Global power-of-two KDK refinement at 0.005 of orbital/crossing times. Not local regularisation or collision handling." },
    Case { id:"yoshida4", label:"Fourth-order symmetric integrator", family:"numerical", recommendations:&[53], fixtures:SCIENCE,
        algorithm:Algorithm::Yoshida, force:ForceMode::Direct, baseline:"production-scalar", tolerance:1.0e-6,
        note:"Yoshida composition including negative middle step; conservative collision-free fixtures only." },
    Case { id:"kepler-drift", label:"Exact two-body Kepler drift", family:"numerical", recommendations:&[54], fixtures:KEPLER,
        algorithm:Algorithm::Kepler, force:ForceMode::Direct, baseline:"production-scalar", tolerance:1.0e-11,
        note:"Universal-variable Kepler solve. Building block only, not a general Wisdom-Holman planetary implementation." },
    Case { id:"radau15-reference", label:"Radau IIA order-15 reference", family:"numerical", recommendations:&[55], fixtures:SCIENCE,
        algorithm:Algorithm::Radau, force:ForceMode::Direct, baseline:"production-scalar", tolerance:1.0e-9,
        note:"Eight-stage Gauss-Radau IIA, bounded fixed-point solves. Not a port or performance claim for IAS15." },
    Case { id:"force-1pn", label:"Test-particle 1PN correction", family:"numerical", recommendations:&[56], fixtures:KEPLER,
        algorithm:Algorithm::Rk4, force:ForceMode::FirstPostNewtonian, baseline:"production-scalar", tolerance:1.0e-7,
        note:"Fixed spherical primary, synthetic c=100m/s, RK4 for velocity dependence; same-model fine RK4 oracle." },
    Case { id:"force-j2", label:"Oblate-primary J2 correction", family:"numerical", recommendations:&[56], fixtures:KEPLER,
        algorithm:Algorithm::Rk4, force:ForceMode::J2, baseline:"production-scalar", tolerance:1.0e-7,
        note:"Fixed +Z spin, J2=0.001, radius=0.2m; same-model fine RK4 oracle." },
    Case { id:"force-radiation", label:"Central radiation pressure", family:"numerical", recommendations:&[56], fixtures:KEPLER,
        algorithm:Algorithm::Rk4, force:ForceMode::Radiation, baseline:"production-scalar", tolerance:1.0e-7,
        note:"Constant beta=0.001 radial radiation pressure; excludes Poynting-Robertson drag and shadowing." },
    Case { id:"barnes-hut", label:"Barnes-Hut source octree", family:"approximate", recommendations:&[57], fixtures:DENSE,
        algorithm:Algorithm::Leapfrog, force:ForceMode::BarnesHut, baseline:"production-scalar", tolerance:2.0e-4,
        note:"Monopole theta=0.3, leaf<=4, depth<=24, no target-containing aggregate; build timed. Not FMM." },
    Case { id:"rsqrt-refine-1", label:"FP32 seed + one FP64 reciprocal correction", family:"approximate", recommendations:&[58], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::Leapfrog, force:ForceMode::Rsqrt(1), baseline:"production-scalar", tolerance:1.0e-10,
        note:"FP32 sqrt/reciprocal seed then FP64 Newton. No unverified ISA estimate claim." },
    Case { id:"rsqrt-refine-2", label:"FP32 seed + two FP64 reciprocal corrections", family:"approximate", recommendations:&[58], fixtures:ALL_FIXTURES,
        algorithm:Algorithm::Leapfrog, force:ForceMode::Rsqrt(2), baseline:"production-scalar", tolerance:2.0e-11,
        note:"FP32 sqrt/reciprocal seed then two FP64 Newton corrections." },
    Case { id:"passive-extrapolate-2", label:"Passive jerk extrapolation, interval 2", family:"approximate", recommendations:&[59], fixtures:SPARSE,
        algorithm:Algorithm::Extrapolate(2), force:ForceMode::Direct, baseline:"production-scalar", tolerance:1.0e-5,
        note:"Massive forces each kick; passive acceleration/jerk every two steps. FP64 checkpoint comparison each segment." },
    Case { id:"passive-extrapolate-4", label:"Passive jerk extrapolation, interval 4", family:"approximate", recommendations:&[59], fixtures:SPARSE,
        algorithm:Algorithm::Extrapolate(4), force:ForceMode::Direct, baseline:"production-scalar", tolerance:1.0e-5,
        note:"Massive forces each kick; passive acceleration/jerk every four steps. FP64 checkpoint comparison each segment." },
    Case { id:"fp32-forecast", label:"FP32 passive forecast with FP64 validation", family:"approximate", recommendations:&[60], fixtures:SPARSE,
        algorithm:Algorithm::Leapfrog, force:ForceMode::Float32, baseline:"production-scalar", tolerance:1.0e-5,
        note:"CPU FP32 passive forecast, FP64 sources, independent FP64 oracle/acceptance. Not GPU execution or authoritative replacement." },
];

pub fn availability(case: &Case) -> (bool, &'static str) {
    if matches!(
        case.force,
        ForceMode::NeonBlock(_) | ForceMode::NeonSources3
    ) && !crate::neon::available()
    {
        (false, "AArch64 process with actual NEON support required")
    } else {
        (true, "")
    }
}
