//! Narrow, runtime-gated FP64 NEON experiment boundary. Constant lane intrinsics
//! assemble and extract register values; no foreign pointers are accepted.
//! Unsafe is needed solely for target-feature intrinsics, as in physics.
use crate::kernels::Source;
use crate::model::{Body, V3};

pub fn available() -> bool {
    #[cfg(target_arch = "aarch64")]
    {
        std::arch::is_aarch64_feature_detected!("neon")
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        false
    }
}

pub fn evaluate(
    bodies: &[Body],
    sources: &[Source],
    out: &mut [V3],
    width: usize,
    fixed_three: bool,
) -> bool {
    if !available() || bodies.len() != out.len() || (fixed_three && sources.len() != 3) {
        return false;
    }
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: actual process NEON support checked above. Typed, bounded
        // slices provide bounded scalar accesses; lane operations use registers.
        unsafe {
            if fixed_three {
                blocked::<2, 3>(bodies, sources, out);
            } else {
                match width {
                    2 => blocked::<1, 0>(bodies, sources, out),
                    4 => blocked::<2, 0>(bodies, sources, out),
                    6 => blocked::<3, 0>(bodies, sources, out),
                    8 => blocked::<4, 0>(bodies, sources, out),
                    16 => blocked::<8, 0>(bodies, sources, out),
                    _ => return false,
                }
            }
        }
        true
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        let _ = width;
        false
    }
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn blocked<const PAIRS: usize, const FIXED_SOURCES: usize>(
    bodies: &[Body],
    sources: &[Source],
    out: &mut [V3],
) {
    use crate::model::MIN_R2;
    use std::arch::aarch64::*;
    if bodies.is_empty() {
        return;
    }
    let zero = vdupq_n_f64(0.0);
    let one = vdupq_n_f64(1.0);
    let minimum = vdupq_n_f64(MIN_R2);
    let source_count = if FIXED_SOURCES == 0 {
        sources.len()
    } else {
        FIXED_SOURCES
    };
    for start in (0..bodies.len()).step_by(PAIRS * 2) {
        let mut px = [zero; PAIRS];
        let mut py = [zero; PAIRS];
        let mut pz = [zero; PAIRS];
        let mut ax = [zero; PAIRS];
        let mut ay = [zero; PAIRS];
        let mut az = [zero; PAIRS];
        for pair in 0..PAIRS {
            let first = (start + pair * 2).min(bodies.len() - 1);
            let second = (first + 1).min(bodies.len() - 1);
            px[pair] = vsetq_lane_f64::<1>(bodies[second].p[0], vdupq_n_f64(bodies[first].p[0]));
            py[pair] = vsetq_lane_f64::<1>(bodies[second].p[1], vdupq_n_f64(bodies[first].p[1]));
            pz[pair] = vsetq_lane_f64::<1>(bodies[second].p[2], vdupq_n_f64(bodies[first].p[2]));
        }
        for source in sources.iter().take(source_count) {
            let sx = vdupq_n_f64(source.p[0]);
            let sy = vdupq_n_f64(source.p[1]);
            let sz = vdupq_n_f64(source.p[2]);
            let mu = vdupq_n_f64(source.mu);
            // Independent vector dependency chains expose sqrt/div latency
            // overlap. Register pressure at PAIRS=8 is an experiment, not a
            // presumption that the widest block wins.
            let mut dx = [zero; PAIRS];
            let mut dy = [zero; PAIRS];
            let mut dz = [zero; PAIRS];
            let mut inverse = [zero; PAIRS];
            for pair in 0..PAIRS {
                dx[pair] = vsubq_f64(sx, px[pair]);
                dy[pair] = vsubq_f64(sy, py[pair]);
                dz[pair] = vsubq_f64(sz, pz[pair]);
                let squared = vmaxq_f64(
                    vfmaq_f64(
                        vfmaq_f64(vmulq_f64(dz[pair], dz[pair]), dy[pair], dy[pair]),
                        dx[pair],
                        dx[pair],
                    ),
                    minimum,
                );
                inverse[pair] = vdivq_f64(one, vsqrtq_f64(squared));
            }
            for pair in 0..PAIRS {
                let factor = vmulq_f64(
                    mu,
                    vmulq_f64(vmulq_f64(inverse[pair], inverse[pair]), inverse[pair]),
                );
                ax[pair] = vfmaq_f64(ax[pair], dx[pair], factor);
                ay[pair] = vfmaq_f64(ay[pair], dy[pair], factor);
                az[pair] = vfmaq_f64(az[pair], dz[pair], factor);
            }
        }
        for pair in 0..PAIRS {
            let index = start + pair * 2;
            if index < out.len() {
                out[index] = [
                    vgetq_lane_f64::<0>(ax[pair]),
                    vgetq_lane_f64::<0>(ay[pair]),
                    vgetq_lane_f64::<0>(az[pair]),
                ];
            }
            if index + 1 < out.len() {
                out[index + 1] = [
                    vgetq_lane_f64::<1>(ax[pair]),
                    vgetq_lane_f64::<1>(ay[pair]),
                    vgetq_lane_f64::<1>(az[pair]),
                ];
            }
        }
    }
}
