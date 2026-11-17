//! Four independent FP64 targets per vector, accumulated over ordered sources.
//! No approximate reciprocal, reduced precision, FMA, or parallel reduction.
use std::arch::x86_64::{
    _mm256_add_pd, _mm256_andnot_pd, _mm256_castsi256_pd, _mm256_cmpeq_epi64, _mm256_div_pd,
    _mm256_max_pd, _mm256_mul_pd, _mm256_set1_epi64x, _mm256_set1_pd, _mm256_set_epi64x,
    _mm256_set_pd, _mm256_setzero_pd, _mm256_sqrt_pd, _mm256_storeu_pd, _mm256_sub_pd,
};

use super::{
    add, norm_squared, scale, subtract, MassiveBodyState, Vector3d, G_M3_PER_KG_S2, MIN_DISTANCE_M2,
};

#[target_feature(enable = "avx2")]
pub(super) unsafe fn pairwise_into(
    bodies: &[MassiveBodyState],
    sources: &[usize],
    accelerations: &mut [Vector3d],
) {
    let full = bodies.len() / 4 * 4;
    let minimum = _mm256_set1_pd(MIN_DISTANCE_M2);
    let one = _mm256_set1_pd(1.0);
    for start in (0..full).step_by(4) {
        let [a, b, c, d] = [0, 1, 2, 3].map(|lane| bodies[start + lane].position_m);
        let x = _mm256_set_pd(d.x, c.x, b.x, a.x);
        let y = _mm256_set_pd(d.y, c.y, b.y, a.y);
        let z = _mm256_set_pd(d.z, c.z, b.z, a.z);
        let target_ids = _mm256_set_epi64x(
            (start + 3) as i64,
            (start + 2) as i64,
            (start + 1) as i64,
            start as i64,
        );
        let mut ax = _mm256_setzero_pd();
        let mut ay = _mm256_setzero_pd();
        let mut az = _mm256_setzero_pd();
        for &source_index in sources {
            let source = bodies[source_index];
            let dx = _mm256_sub_pd(_mm256_set1_pd(source.position_m.x), x);
            let dy = _mm256_sub_pd(_mm256_set1_pd(source.position_m.y), y);
            let dz = _mm256_sub_pd(_mm256_set1_pd(source.position_m.z), z);
            let squared = _mm256_add_pd(
                _mm256_add_pd(_mm256_mul_pd(dx, dx), _mm256_mul_pd(dy, dy)),
                _mm256_mul_pd(dz, dz),
            );
            let inv = _mm256_div_pd(one, _mm256_sqrt_pd(_mm256_max_pd(squared, minimum)));
            let inv_cubed = _mm256_mul_pd(_mm256_mul_pd(inv, inv), inv);
            let raw_scale =
                _mm256_mul_pd(_mm256_set1_pd(G_M3_PER_KG_S2 * source.mass_kg), inv_cubed);
            // A real AVX2 integer comparison masks self-interaction before
            // multiplication, including when an extreme source scale overflows.
            let self_mask = _mm256_castsi256_pd(_mm256_cmpeq_epi64(
                target_ids,
                _mm256_set1_epi64x(source_index as i64),
            ));
            let factor = _mm256_andnot_pd(self_mask, raw_scale);
            ax = _mm256_add_pd(ax, _mm256_mul_pd(dx, factor));
            ay = _mm256_add_pd(ay, _mm256_mul_pd(dy, factor));
            az = _mm256_add_pd(az, _mm256_mul_pd(dz, factor));
        }
        let mut x_out = [0.0; 4];
        let mut y_out = [0.0; 4];
        let mut z_out = [0.0; 4];
        // SAFETY: storeu writes exactly four f64s to each local array, with no
        // alignment requirement. The target block is within the output slice.
        _mm256_storeu_pd(x_out.as_mut_ptr(), ax);
        _mm256_storeu_pd(y_out.as_mut_ptr(), ay);
        _mm256_storeu_pd(z_out.as_mut_ptr(), az);
        for lane in 0..4 {
            accelerations[start + lane] = Vector3d {
                x: x_out[lane],
                y: y_out[lane],
                z: z_out[lane],
            };
        }
    }
    for i in full..bodies.len() {
        let mut acceleration = Vector3d::default();
        for &j in sources {
            if i == j {
                continue;
            }
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let inv = norm_squared(delta).max(MIN_DISTANCE_M2).sqrt().recip();
            let inv_cubed = inv * inv * inv;
            acceleration = add(
                acceleration,
                scale(delta, G_M3_PER_KG_S2 * bodies[j].mass_kg * inv_cubed),
            );
        }
        accelerations[i] = acceleration;
    }
}
