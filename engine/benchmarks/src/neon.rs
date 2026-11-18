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

// Keep both exact and reciprocal entry points as equivalent call boundaries.
// This also makes their owning NEON functions identifiable in release ISA proof.
#[inline(never)]
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
                blocked::<2, 3, 0>(bodies, sources, out);
            } else {
                match width {
                    2 => blocked::<1, 0, 0>(bodies, sources, out),
                    4 => blocked::<2, 0, 0>(bodies, sources, out),
                    6 => blocked::<3, 0, 0>(bodies, sources, out),
                    8 => blocked::<4, 0, 0>(bodies, sources, out),
                    16 => blocked::<8, 0, 0>(bodies, sources, out),
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

/// Same four-target packing and accumulation as `neon-block-4`, replacing only
/// sqrt/divide with an actual FP64 hardware estimate and fixed refinements.
/// Rejected requests leave the output untouched; there is no scalar fallback.
#[inline(never)]
pub fn evaluate_refined(
    bodies: &[Body],
    sources: &[Source],
    out: &mut [V3],
    refinements: u32,
) -> bool {
    if !available() || bodies.len() != out.len() || !matches!(refinements, 2 | 3) {
        return false;
    }
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: process-local NEON support and output bounds checked above.
        // This uses the same typed input/lane boundary as the exact control.
        unsafe {
            match refinements {
                2 => blocked::<2, 0, 2>(bodies, sources, out),
                3 => blocked::<2, 0, 3>(bodies, sources, out),
                _ => unreachable!("refinement count was validated"),
            }
        }
        true
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        let _ = sources;
        false
    }
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn blocked<const PAIRS: usize, const FIXED_SOURCES: usize, const REFINEMENTS: usize>(
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
                inverse[pair] = if REFINEMENTS == 0 {
                    vdivq_f64(one, vsqrtq_f64(squared))
                } else {
                    refined_inverse::<REFINEMENTS>(squared)
                };
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

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn refined_inverse<const REFINEMENTS: usize>(
    squared: std::arch::aarch64::float64x2_t,
) -> std::arch::aarch64::float64x2_t {
    use std::arch::aarch64::*;
    // The gravity kernel floors r² at MIN_R2 before this boundary. Squaring a
    // finite, very large displacement may still produce +infinity. Match the
    // exact control's inverse=0 without forming infinity*0 in a Newton step.
    // NaNs propagate, so the outer independent oracle cannot accept them.
    let infinity = vceqq_f64(squared, vdupq_n_f64(f64::INFINITY));
    let finite_squared = vbslq_f64(infinity, vdupq_n_f64(1.0), squared);
    let mut inverse = vrsqrteq_f64(finite_squared);
    for _ in 0..REFINEMENTS {
        // (r²*y)*y avoids underflow of y*y for very large finite r². FRSQRTS
        // evaluates (3-a*b)/2 with its fused operation, then y *= correction.
        inverse = vmulq_f64(
            inverse,
            vrsqrtsq_f64(vmulq_f64(finite_squared, inverse), inverse),
        );
    }
    vbslq_f64(infinity, vdupq_n_f64(0.0), inverse)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reciprocal_request_rejection_preserves_output() {
        let bodies = [Body {
            mu: 0.0,
            p: [0.0; 3],
            v: [0.0; 3],
        }];
        let mut output = [[42.0; 3]];
        for refinements in [0, 1, 4, u32::MAX] {
            assert!(!evaluate_refined(&bodies, &[], &mut output, refinements));
            assert_eq!(output, [[42.0; 3]]);
        }
        let mut wrong_shape = [[42.0; 3]; 2];
        assert!(!evaluate_refined(&bodies, &[], &mut wrong_shape, 3));
        assert_eq!(wrong_shape, [[42.0; 3]; 2]);
        if !available() {
            assert!(!evaluate_refined(&bodies, &[], &mut output, 3));
            assert_eq!(output, [[42.0; 3]]);
        }
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn reciprocal_instructions_cover_finite_exponent_range_and_infinity() {
        use crate::model::MIN_R2;
        use std::arch::aarch64::*;

        assert!(
            available(),
            "Arm64 proof requires actual process NEON support"
        );
        let mut values = vec![MIN_R2, f64::MAX];
        for exponent in (-39..=1023).step_by(17) {
            for mantissa in [1.0, 1.000_001, 1.25, 1.5, 1.999_999] {
                values.push(mantissa * 2.0_f64.powi(exponent));
            }
        }
        let mut max_two: f64 = 0.0;
        let mut max_three: f64 = 0.0;
        for pair in values.chunks(2) {
            let second = *pair.get(1).unwrap_or(&pair[0]);
            // SAFETY: this test is AArch64-only and checks process support.
            let (two, three) = unsafe {
                let input = vsetq_lane_f64::<1>(second, vdupq_n_f64(pair[0]));
                let two = refined_inverse::<2>(input);
                let three = refined_inverse::<3>(input);
                (
                    [vgetq_lane_f64::<0>(two), vgetq_lane_f64::<1>(two)],
                    [vgetq_lane_f64::<0>(three), vgetq_lane_f64::<1>(three)],
                )
            };
            for lane in 0..2 {
                let input = if lane == 0 { pair[0] } else { second };
                let reference = input.sqrt().recip();
                let error_two = ((two[lane] - reference) / reference).abs();
                let error_three = ((three[lane] - reference) / reference).abs();
                assert!(
                    error_two <= 2.0e-9,
                    "two-step inverse: {input:e} {error_two:e}"
                );
                assert!(
                    error_three <= 8.0 * f64::EPSILON,
                    "three-step inverse: {input:e} {error_three:e}"
                );
                max_two = max_two.max(error_two);
                max_three = max_three.max(error_three);
            }
        }
        assert!(
            max_three < max_two,
            "the sampled refinement must reduce maximum error"
        );
        // Both lanes receive real hardware instructions; +infinity is handled
        // without an infinity*0 step, while a NaN remains unacceptably non-finite.
        unsafe {
            let input = vsetq_lane_f64::<1>(f64::NAN, vdupq_n_f64(f64::INFINITY));
            for output in [refined_inverse::<2>(input), refined_inverse::<3>(input)] {
                assert_eq!(vgetq_lane_f64::<0>(output), 0.0);
                assert!(vgetq_lane_f64::<1>(output).is_nan());
            }
        }
        println!(
            "{}",
            serde_json::json!({
                "proof":"neon_reciprocal_domain", "inputs":values.len(),
                "minimum_squared_distance":MIN_R2, "maximum_squared_distance":f64::MAX,
                "max_relative_inverse_error_two_steps":max_two,
                "max_relative_inverse_error_three_steps":max_three,
                "infinite_squared_distance_inverse_zero":true, "nan_remains_non_finite":true,
            })
        );
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn reciprocal_gravity_preserves_odd_tails_self_force_and_distance_floor() {
        use crate::kernels::{ForceEvaluator, ForceMode};

        assert!(
            available(),
            "Arm64 proof requires actual process NEON support"
        );
        let positions = [
            [0.0; 3],
            [0.0; 3],
            [1.0e-160, 0.0, 0.0],
            [0.999_999e-6, 0.0, 0.0],
            [1.0e-6, 0.0, 0.0],
            [1.000_001e-6, 0.0, 0.0],
            [-3.0, 4.0, -5.0],
            [1.0e40, -2.0e40, 3.0e40],
            [1.0e100, 0.0, 0.0],
            [1.0e150, 0.0, 0.0],
            [1.0e155, 0.0, 0.0],
        ];
        let bodies: Vec<_> = positions
            .iter()
            .enumerate()
            .map(|(i, &p)| Body {
                mu: if i < 2 { 1.0 } else { 0.0 },
                p,
                v: [0.0; 3],
            })
            .collect();
        let mut max_errors = [0.0_f64; 2];
        // Every count checks both empty input and all lane/block tail positions.
        for count in 0..=bodies.len() {
            let input = &bodies[..count];
            let mut reference = vec![[0.0; 3]; count];
            ForceEvaluator::new(ForceMode::Direct, input).evaluate(input, &mut reference);
            for (variant, refinements) in [2, 3].into_iter().enumerate() {
                let mut output = vec![[42.0; 3]; count];
                ForceEvaluator::new(ForceMode::NeonRsqrt(refinements), input)
                    .evaluate(input, &mut output);
                for (actual, expected) in output.iter().zip(&reference) {
                    for component in 0..3 {
                        let scaled_error = ((actual[component] - expected[component])
                            / expected[component].abs().max(1.0))
                        .abs();
                        let limit = if refinements == 2 { 2.0e-8 } else { 5.0e-14 };
                        assert!(
                            actual[component].is_finite() && scaled_error <= limit,
                            "count={count} refinements={refinements}: {actual:?} {expected:?}"
                        );
                        max_errors[variant] = max_errors[variant].max(scaled_error);
                    }
                }
                for acceleration in output.iter().take(2) {
                    assert_eq!(
                        *acceleration, [0.0; 3],
                        "coincident sources have zero self force"
                    );
                }
            }
        }
        println!(
            "{}",
            serde_json::json!({
                "proof":"neon_reciprocal_gravity_boundaries", "counts":bodies.len()+1,
                "max_scaled_acceleration_error_two_steps":max_errors[0],
                "max_scaled_acceleration_error_three_steps":max_errors[1],
                "self_force_zero":true, "distance_floor_and_squared_overflow":true,
            })
        );
    }
}
