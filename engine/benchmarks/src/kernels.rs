//! Experimental force kernels. Layout names do not claim particular ISA instructions.
use crate::model::{add, dot, norm, scale, sub, Body, MIN_R2, V3};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ForceMode {
    Direct,
    Specialised,
    Block(usize),
    NeonBlock(usize),
    NeonSources3,
    NeonRsqrt(u32),
    Pipeline4,
    Tile(usize),
    Soa,
    Symmetric(usize),
    Workers(usize),
    Kahan,
    Neumaier,
    Pairwise,
    BarnesHut,
    Rsqrt(u32),
    Float32,
    FirstPostNewtonian,
    J2,
    Radiation,
}

#[derive(Clone, Copy, Default)]
#[repr(C, align(32))]
pub(crate) struct Source {
    pub(crate) p: V3,
    pub(crate) mu: f64,
}

pub struct ForceEvaluator {
    pub mode: ForceMode,
    pub evaluations: u64,
    sources: Vec<usize>,
    packed: Vec<Source>,
    x: Vec<f64>,
    y: Vec<f64>,
    z: Vec<f64>,
    mu: Vec<f64>,
}

impl ForceEvaluator {
    pub fn new(mode: ForceMode, bodies: &[Body]) -> Self {
        let sources: Vec<_> = bodies
            .iter()
            .enumerate()
            .filter_map(|(i, b)| (b.mu > 0.0).then_some(i))
            .collect();
        let count = sources.len();
        let packed_count = if matches!(
            mode,
            ForceMode::Block(_)
                | ForceMode::NeonBlock(_)
                | ForceMode::NeonSources3
                | ForceMode::NeonRsqrt(_)
                | ForceMode::Pipeline4
                | ForceMode::Tile(_)
                | ForceMode::Specialised
        ) {
            count
        } else {
            0
        };
        let soa_count = if mode == ForceMode::Soa { count } else { 0 };
        Self {
            mode,
            evaluations: 0,
            sources,
            packed: vec![Source::default(); packed_count],
            x: vec![0.0; soa_count],
            y: vec![0.0; soa_count],
            z: vec![0.0; soa_count],
            mu: vec![0.0; soa_count],
        }
    }

    // The explicit match keeps each measured implementation and its setup together.
    // FP32 conversions are the declared approximate experiment, not accidental loss.
    #[allow(clippy::too_many_lines, clippy::cast_possible_truncation)]
    pub fn evaluate(&mut self, bodies: &[Body], out: &mut [V3]) {
        assert_eq!(
            bodies.len(),
            out.len(),
            "acceleration output length must match body count"
        );
        self.evaluations += 1;
        out.fill([0.0; 3]);
        match self.mode {
            ForceMode::NeonBlock(width) => {
                self.refresh_packed(bodies);
                assert!(
                    crate::neon::evaluate(bodies, &self.packed, out, width, false),
                    "NEON execution must be capability-gated before timing"
                );
            }
            ForceMode::NeonSources3 => {
                self.refresh_packed(bodies);
                assert!(
                    crate::neon::evaluate(bodies, &self.packed, out, 4, true),
                    "fixed three-source NEON requirements not met"
                );
            }
            ForceMode::NeonRsqrt(refinements) => {
                self.refresh_packed(bodies);
                assert!(
                    crate::neon::evaluate_refined(bodies, &self.packed, out, refinements),
                    "NEON reciprocal execution requires process support and two or three refinements"
                );
            }
            ForceMode::Block(width) => {
                self.refresh_packed(bodies);
                match width {
                    2 => blocked::<2>(bodies, &self.sources, &self.packed, out),
                    4 => blocked::<4>(bodies, &self.sources, &self.packed, out),
                    6 => blocked::<6>(bodies, &self.sources, &self.packed, out),
                    8 => blocked::<8>(bodies, &self.sources, &self.packed, out),
                    16 => blocked::<16>(bodies, &self.sources, &self.packed, out),
                    _ => direct(bodies, &self.sources, out),
                }
            }
            ForceMode::Pipeline4 => {
                self.refresh_packed(bodies);
                pipeline4(bodies, &self.sources, &self.packed, out);
            }
            ForceMode::Tile(size) => {
                self.refresh_packed(bodies);
                source_major_tiles(bodies, &self.sources, &self.packed, out, size);
            }
            ForceMode::Specialised => {
                self.refresh_packed(bodies);
                match self.sources.len() {
                    1 => fixed_sources::<1>(bodies, &self.sources, &self.packed, out),
                    2 => fixed_sources::<2>(bodies, &self.sources, &self.packed, out),
                    3 => fixed_sources::<3>(bodies, &self.sources, &self.packed, out),
                    4 => fixed_sources::<4>(bodies, &self.sources, &self.packed, out),
                    5 => fixed_sources::<5>(bodies, &self.sources, &self.packed, out),
                    6 => fixed_sources::<6>(bodies, &self.sources, &self.packed, out),
                    7 => fixed_sources::<7>(bodies, &self.sources, &self.packed, out),
                    8 => fixed_sources::<8>(bodies, &self.sources, &self.packed, out),
                    _ => direct(bodies, &self.sources, out),
                }
            }
            ForceMode::Soa => {
                for (j, &i) in self.sources.iter().enumerate() {
                    self.x[j] = bodies[i].p[0];
                    self.y[j] = bodies[i].p[1];
                    self.z[j] = bodies[i].p[2];
                    self.mu[j] = bodies[i].mu;
                }
                for (i, body) in bodies.iter().enumerate() {
                    for (j, &source_index) in self.sources.iter().enumerate() {
                        if i == source_index {
                            continue;
                        }
                        let d = [
                            self.x[j] - body.p[0],
                            self.y[j] - body.p[1],
                            self.z[j] - body.p[2],
                        ];
                        let inv = dot(d, d).max(MIN_R2).sqrt().recip();
                        out[i] = add(out[i], scale(d, self.mu[j] * (inv * inv * inv)));
                    }
                }
            }
            ForceMode::Symmetric(size) => symmetric_tiled(bodies, out, size),
            ForceMode::Workers(workers) => parallel_targets(bodies, &self.sources, out, workers),
            ForceMode::Kahan | ForceMode::Neumaier | ForceMode::Pairwise => {
                reduced(bodies, &self.sources, out, self.mode);
            }
            ForceMode::BarnesHut => {
                let tree = Octree::build(bodies, &self.sources);
                for (i, body) in bodies.iter().enumerate() {
                    out[i] = tree.root.acceleration(bodies, i, body.p, 0.3);
                }
            }
            ForceMode::Rsqrt(iterations) => {
                for (i, body) in bodies.iter().enumerate() {
                    for &j in &self.sources {
                        if i == j {
                            continue;
                        }
                        let d = sub(bodies[j].p, body.p);
                        let r2 = dot(d, d).max(MIN_R2);
                        // FP32 sqrt seed, not an unverified ISA reciprocal estimate.
                        let mut inv = f64::from((r2 as f32).sqrt().recip());
                        for _ in 0..iterations {
                            inv *= 1.5 - 0.5 * r2 * inv * inv;
                        }
                        out[i] = add(out[i], scale(d, bodies[j].mu * (inv * inv * inv)));
                    }
                }
            }
            ForceMode::Float32 => {
                for (i, body) in bodies.iter().enumerate() {
                    if body.mu > 0.0 {
                        for &j in &self.sources {
                            if i != j {
                                out[i] = add(
                                    out[i],
                                    contribution(
                                        body.p,
                                        Source {
                                            p: bodies[j].p,
                                            mu: bodies[j].mu,
                                        },
                                    ),
                                );
                            }
                        }
                        continue;
                    }
                    let mut a = [0.0_f32; 3];
                    for &j in &self.sources {
                        if i == j {
                            continue;
                        }
                        let d = [
                            (bodies[j].p[0] - body.p[0]) as f32,
                            (bodies[j].p[1] - body.p[1]) as f32,
                            (bodies[j].p[2] - body.p[2]) as f32,
                        ];
                        let r2 = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).max(MIN_R2 as f32);
                        let inv = r2.sqrt().recip();
                        let magnitude = bodies[j].mu as f32 * inv * inv * inv;
                        for k in 0..3 {
                            a[k] += d[k] * magnitude;
                        }
                    }
                    out[i] = a.map(f64::from);
                }
            }
            ForceMode::FirstPostNewtonian | ForceMode::J2 | ForceMode::Radiation => {
                direct(bodies, &self.sources, out);
                central_correction(bodies, out, self.mode);
            }
            ForceMode::Direct => direct(bodies, &self.sources, out),
        }
    }

    fn refresh_packed(&mut self, bodies: &[Body]) {
        for (j, &i) in self.sources.iter().enumerate() {
            self.packed[j] = Source {
                p: bodies[i].p,
                mu: bodies[i].mu,
            };
        }
    }
}

fn contribution(target: V3, source: Source) -> V3 {
    let d = sub(source.p, target);
    let inv = dot(d, d).max(MIN_R2).sqrt().recip();
    scale(d, source.mu * (inv * inv * inv))
}

fn direct(bodies: &[Body], sources: &[usize], out: &mut [V3]) {
    for (i, body) in bodies.iter().enumerate() {
        for &j in sources {
            if i != j {
                out[i] = add(
                    out[i],
                    contribution(
                        body.p,
                        Source {
                            p: bodies[j].p,
                            mu: bodies[j].mu,
                        },
                    ),
                );
            }
        }
    }
}

#[inline]
pub(crate) fn target_acceleration(bodies: &[Body], sources: &[usize], target: usize) -> V3 {
    let mut sum = [0.0; 3];
    for &source in sources {
        if target != source {
            sum = add(
                sum,
                contribution(
                    bodies[target].p,
                    Source {
                        p: bodies[source].p,
                        mu: bodies[source].mu,
                    },
                ),
            );
        }
    }
    sum
}

fn fixed_sources<const N: usize>(
    bodies: &[Body],
    indices: &[usize],
    packed: &[Source],
    out: &mut [V3],
) {
    let mut hot = [Source::default(); N];
    hot.copy_from_slice(packed);
    for (i, body) in bodies.iter().enumerate() {
        for j in 0..N {
            if i != indices[j] {
                out[i] = add(out[i], contribution(body.p, hot[j]));
            }
        }
    }
}

fn blocked<const N: usize>(bodies: &[Body], indices: &[usize], packed: &[Source], out: &mut [V3]) {
    for start in (0..bodies.len()).step_by(N) {
        let len = N.min(bodies.len() - start);
        let mut p = [[0.0; 3]; N];
        let mut sums = [[0.0; 3]; N];
        for lane in 0..len {
            p[lane] = bodies[start + lane].p;
        }
        for (j, &source) in packed.iter().enumerate() {
            for lane in 0..len {
                if start + lane != indices[j] {
                    sums[lane] = add(sums[lane], contribution(p[lane], source));
                }
            }
        }
        out[start..start + len].copy_from_slice(&sums[..len]);
    }
}

fn pipeline4(bodies: &[Body], indices: &[usize], packed: &[Source], out: &mut [V3]) {
    for start in (0..bodies.len()).step_by(4) {
        let len = 4.min(bodies.len() - start);
        let mut sums = [[0.0; 3]; 4];
        for (j, &source) in packed.iter().enumerate() {
            let mut delta = [[0.0; 3]; 4];
            let mut inv = [0.0; 4];
            for lane in 0..len {
                delta[lane] = sub(source.p, bodies[start + lane].p);
            }
            for lane in 0..len {
                inv[lane] = dot(delta[lane], delta[lane]).max(MIN_R2).sqrt().recip();
            }
            for lane in 0..len {
                if start + lane != indices[j] {
                    sums[lane] = add(
                        sums[lane],
                        scale(delta[lane], source.mu * (inv[lane] * inv[lane] * inv[lane])),
                    );
                }
            }
        }
        out[start..start + len].copy_from_slice(&sums[..len]);
    }
}

fn source_major_tiles(
    bodies: &[Body],
    indices: &[usize],
    packed: &[Source],
    out: &mut [V3],
    size: usize,
) {
    for start in (0..bodies.len()).step_by(size) {
        let end = (start + size).min(bodies.len());
        for (j, &source) in packed.iter().enumerate() {
            for i in start..end {
                if i != indices[j] {
                    out[i] = add(out[i], contribution(bodies[i].p, source));
                }
            }
        }
    }
}

fn symmetric_tiled(bodies: &[Body], out: &mut [V3], size: usize) {
    for row in (0..bodies.len()).step_by(size) {
        let row_end = (row + size).min(bodies.len());
        for col in (row..bodies.len()).step_by(size) {
            let col_end = (col + size).min(bodies.len());
            for i in row..row_end {
                for j in col.max(i + 1)..col_end {
                    if bodies[i].mu == 0.0 && bodies[j].mu == 0.0 {
                        continue;
                    }
                    let d = sub(bodies[j].p, bodies[i].p);
                    let inv = dot(d, d).max(MIN_R2).sqrt().recip();
                    let inv3 = inv * inv * inv;
                    out[i] = add(out[i], scale(d, bodies[j].mu * inv3));
                    out[j] = sub(out[j], scale(d, bodies[i].mu * inv3));
                }
            }
        }
    }
}

fn parallel_targets(bodies: &[Body], sources: &[usize], out: &mut [V3], workers: usize) {
    let worker_count = workers.min(bodies.len()).max(1);
    if worker_count == 1 {
        direct(bodies, sources, out);
        return;
    }
    let size = bodies.len().div_ceil(worker_count);
    // Disjoint slices; no scheduler-dependent reductions. Creation/join cost is
    // deliberately charged. This is not advertised as a persistent worker pool.
    std::thread::scope(|scope| {
        for (chunk_index, chunk) in out.chunks_mut(size).enumerate() {
            let first = chunk_index * size;
            scope.spawn(move || {
                for (offset, sum) in chunk.iter_mut().enumerate() {
                    let i = first + offset;
                    for &j in sources {
                        if i != j {
                            *sum = add(
                                *sum,
                                contribution(
                                    bodies[i].p,
                                    Source {
                                        p: bodies[j].p,
                                        mu: bodies[j].mu,
                                    },
                                ),
                            );
                        }
                    }
                }
            });
        }
    });
}

fn reduced(bodies: &[Body], sources: &[usize], out: &mut [V3], mode: ForceMode) {
    let mut terms = Vec::with_capacity(sources.len());
    for (i, body) in bodies.iter().enumerate() {
        let mut sum = [0.0_f64; 3];
        let mut correction = [0.0; 3];
        terms.clear();
        for &j in sources {
            if i == j {
                continue;
            }
            let term = contribution(
                body.p,
                Source {
                    p: bodies[j].p,
                    mu: bodies[j].mu,
                },
            );
            match mode {
                ForceMode::Pairwise => terms.push(term),
                ForceMode::Kahan => {
                    for axis in 0..3 {
                        let corrected = term[axis] - correction[axis];
                        let next = sum[axis] + corrected;
                        correction[axis] = (next - sum[axis]) - corrected;
                        sum[axis] = next;
                    }
                }
                _ => {
                    for axis in 0..3 {
                        let next = sum[axis] + term[axis];
                        correction[axis] += if sum[axis].abs() >= term[axis].abs() {
                            (sum[axis] - next) + term[axis]
                        } else {
                            (term[axis] - next) + sum[axis]
                        };
                        sum[axis] = next;
                    }
                }
            }
        }
        out[i] = match mode {
            ForceMode::Pairwise => pairwise_sum(&terms),
            ForceMode::Kahan => sum,
            _ => add(sum, correction),
        };
    }
}

pub fn pairwise_sum(values: &[V3]) -> V3 {
    match values.len() {
        0 => [0.0; 3],
        1 => values[0],
        n => add(
            pairwise_sum(&values[..n / 2]),
            pairwise_sum(&values[n / 2..]),
        ),
    }
}

fn central_correction(bodies: &[Body], out: &mut [V3], mode: ForceMode) {
    // Restricted fixed primary + passive probes, not a general relativistic
    // N-body or oblate-body solver. Synthetic constants are reported in JSON.
    let primary = bodies[0];
    for i in 1..bodies.len() {
        let r = sub(bodies[i].p, primary.p);
        let v = sub(bodies[i].v, primary.v);
        let r2 = dot(r, r).max(MIN_R2);
        let distance = r2.sqrt();
        let inv3 = (distance * r2).recip();
        let correction = match mode {
            ForceMode::FirstPostNewtonian => scale(
                add(
                    scale(r, 4.0 * primary.mu / distance - dot(v, v)),
                    scale(v, 4.0 * dot(r, v)),
                ),
                primary.mu * inv3 / 10_000.0,
            ),
            ForceMode::J2 => {
                let z2_ratio = r[2] * r[2] / r2;
                let factor = 1.5 * 0.001 * primary.mu * 0.04 * inv3 / r2;
                [
                    factor * r[0] * (5.0 * z2_ratio - 1.0),
                    factor * r[1] * (5.0 * z2_ratio - 1.0),
                    factor * r[2] * (5.0 * z2_ratio - 3.0),
                ]
            }
            ForceMode::Radiation => scale(r, 0.001 * primary.mu * inv3),
            _ => [0.0; 3],
        };
        out[i] = add(out[i], correction);
    }
}

struct Node {
    centre: V3,
    half: f64,
    mu: f64,
    centre_of_mass: V3,
    members: Vec<usize>,
    children: Vec<Node>,
}
struct Octree {
    root: Node,
}

impl Octree {
    fn build(bodies: &[Body], sources: &[usize]) -> Self {
        let mut lo = [f64::INFINITY; 3];
        let mut hi = [f64::NEG_INFINITY; 3];
        for &i in sources {
            for axis in 0..3 {
                lo[axis] = lo[axis].min(bodies[i].p[axis]);
                hi[axis] = hi[axis].max(bodies[i].p[axis]);
            }
        }
        let centre = scale(add(lo, hi), 0.5);
        let half = (0..3)
            .map(|k| 0.5 * (hi[k] - lo[k]))
            .fold(1.0e-6_f64, f64::max)
            * (1.0 + 1.0e-12);
        Self {
            root: Node::build(bodies, sources.to_vec(), centre, half, 0),
        }
    }
}

impl Node {
    fn build(bodies: &[Body], members: Vec<usize>, centre: V3, half: f64, depth: usize) -> Self {
        let mu: f64 = members.iter().map(|&i| bodies[i].mu).sum();
        let mut moment = [0.0; 3];
        for &i in &members {
            moment = add(moment, scale(bodies[i].p, bodies[i].mu));
        }
        let centre_of_mass = if mu > 0.0 {
            scale(moment, mu.recip())
        } else {
            centre
        };
        let mut node = Self {
            centre,
            half,
            mu,
            centre_of_mass,
            members,
            children: Vec::new(),
        };
        if node.members.len() <= 4 || depth >= 24 {
            return node;
        }
        let mut groups: [Vec<usize>; 8] = std::array::from_fn(|_| Vec::new());
        for &i in &node.members {
            let bucket = usize::from(bodies[i].p[0] >= centre[0])
                | (usize::from(bodies[i].p[1] >= centre[1]) << 1)
                | (usize::from(bodies[i].p[2] >= centre[2]) << 2);
            groups[bucket].push(i);
        }
        for (bucket, group) in groups.into_iter().enumerate() {
            if group.is_empty() {
                continue;
            }
            let child_centre = std::array::from_fn(|axis| {
                centre[axis]
                    + if bucket & (1 << axis) == 0 {
                        -half * 0.5
                    } else {
                        half * 0.5
                    }
            });
            node.children.push(Self::build(
                bodies,
                group,
                child_centre,
                half * 0.5,
                depth + 1,
            ));
        }
        node.members.clear();
        node
    }

    fn acceleration(&self, bodies: &[Body], index: usize, p: V3, theta: f64) -> V3 {
        if self.children.is_empty() {
            let mut sum = [0.0; 3];
            for &j in &self.members {
                if index != j {
                    sum = add(
                        sum,
                        contribution(
                            p,
                            Source {
                                p: bodies[j].p,
                                mu: bodies[j].mu,
                            },
                        ),
                    );
                }
            }
            return sum;
        }
        let d = norm(sub(self.centre_of_mass, p));
        let contains_target = (0..3).all(|axis| (p[axis] - self.centre[axis]).abs() <= self.half);
        if !contains_target && 2.0 * self.half < theta * d {
            return contribution(
                p,
                Source {
                    p: self.centre_of_mass,
                    mu: self.mu,
                },
            );
        }
        let mut sum = [0.0; 3];
        for child in &self.children {
            sum = add(sum, child.acceleration(bodies, index, p, theta));
        }
        sum
    }
}

pub fn acceleration_and_jerk(bodies: &[Body], target: usize) -> (V3, V3) {
    let mut a = [0.0; 3];
    let mut jerk = [0.0; 3];
    for (j, source) in bodies.iter().enumerate() {
        if j == target || source.mu == 0.0 {
            continue;
        }
        let d = sub(source.p, bodies[target].p);
        let v = sub(source.v, bodies[target].v);
        let r2 = dot(d, d).max(MIN_R2);
        let inv3 = (r2 * r2.sqrt()).recip();
        a = add(a, scale(d, source.mu * inv3));
        jerk = add(
            jerk,
            scale(sub(v, scale(d, 3.0 * dot(d, v) / r2)), source.mu * inv3),
        );
    }
    (a, jerk)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::fixture;

    #[test]
    fn mismatched_output_shape_is_rejected_before_clearing_or_evaluation() {
        let f = fixture("kepler").unwrap();
        for size in [f.bodies.len() - 1, f.bodies.len() + 1] {
            let mut evaluator = ForceEvaluator::new(ForceMode::Direct, &f.bodies);
            let mut output = vec![[42.0; 3]; size];
            let failed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                evaluator.evaluate(&f.bodies, &mut output)
            }));
            assert!(failed.is_err());
            assert_eq!(output, vec![[42.0; 3]; size]);
            assert_eq!(evaluator.evaluations, 0);
        }
    }

    #[test]
    fn compensated_forces_recover_known_small_residual_between_large_sources() {
        let bodies = vec![
            Body {
                mu: 0.0,
                p: [0.0; 3],
                v: [0.0; 3],
            },
            Body {
                mu: 1.0e16,
                p: [1.0, 0.0, 0.0],
                v: [0.0; 3],
            },
            Body {
                mu: 1.0,
                p: [1.0, 0.0, 0.0],
                v: [0.0; 3],
            },
            Body {
                mu: 1.0,
                p: [1.0, 0.0, 0.0],
                v: [0.0; 3],
            },
            Body {
                mu: 1.0e16,
                p: [-1.0, 0.0, 0.0],
                v: [0.0; 3],
            },
        ];
        // All source-target distances are exactly one: analytic acceleration
        // is 10^16 + 1 + 1 - 10^16 = 2, independently of an FP64 oracle.
        for mode in [ForceMode::Kahan, ForceMode::Neumaier] {
            let mut a = vec![[0.0; 3]; bodies.len()];
            ForceEvaluator::new(mode, &bodies).evaluate(&bodies, &mut a);
            assert_eq!(a[0], [2.0, 0.0, 0.0]);
        }
        let mut naive = vec![[0.0; 3]; bodies.len()];
        ForceEvaluator::new(ForceMode::Direct, &bodies).evaluate(&bodies, &mut naive);
        assert_eq!(naive[0], [0.0; 3]);
    }

    #[test]
    fn layouts_cover_odd_tails_and_preserve_source_and_passive_forces() {
        for id in ["canonical15", "sparse3", "close-encounter"] {
            let f = fixture(id).unwrap();
            let mut reference = vec![[0.0; 3]; f.bodies.len()];
            ForceEvaluator::new(ForceMode::Direct, &f.bodies).evaluate(&f.bodies, &mut reference);
            let mut modes = vec![
                ForceMode::Specialised,
                ForceMode::Block(2),
                ForceMode::Block(4),
                ForceMode::Block(6),
                ForceMode::Block(8),
                ForceMode::Block(16),
                ForceMode::Pipeline4,
                ForceMode::Tile(16),
                ForceMode::Tile(32),
                ForceMode::Tile(64),
                ForceMode::Soa,
                ForceMode::Symmetric(16),
                ForceMode::Symmetric(32),
                ForceMode::Symmetric(64),
            ];
            if crate::neon::available() {
                modes.extend([
                    ForceMode::NeonBlock(2),
                    ForceMode::NeonBlock(4),
                    ForceMode::NeonBlock(6),
                    ForceMode::NeonBlock(8),
                    ForceMode::NeonBlock(16),
                ]);
            }
            for mode in modes {
                let mut actual = vec![[0.0; 3]; f.bodies.len()];
                ForceEvaluator::new(mode, &f.bodies).evaluate(&f.bodies, &mut actual);
                for (a, b) in actual.iter().zip(&reference) {
                    assert!(
                        norm(sub(*a, *b)) <= 2.0e-14 * norm(*b).max(1.0),
                        "{id} {mode:?} {a:?} {b:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn workers_preserve_every_acceleration_bit() {
        let f = fixture("dense192").unwrap();
        let mut reference = vec![[0.0; 3]; f.bodies.len()];
        ForceEvaluator::new(ForceMode::Direct, &f.bodies).evaluate(&f.bodies, &mut reference);
        for workers in [1, 2, 4, 8] {
            let mut actual = vec![[0.0; 3]; f.bodies.len()];
            ForceEvaluator::new(ForceMode::Workers(workers), &f.bodies)
                .evaluate(&f.bodies, &mut actual);
            assert_eq!(reference, actual);
        }
    }

    #[test]
    fn symmetric_pairs_conserve_mass_weighted_acceleration() {
        let f = fixture("dense256").unwrap();
        let mut a = vec![[0.0; 3]; f.bodies.len()];
        ForceEvaluator::new(ForceMode::Symmetric(32), &f.bodies).evaluate(&f.bodies, &mut a);
        let derivative = a
            .iter()
            .zip(&f.bodies)
            .fold([0.0; 3], |sum, (a, b)| add(sum, scale(*a, b.mu)));
        assert!(norm(derivative) < 1.0e-16);
    }

    #[test]
    fn tree_has_no_self_force_and_correct_single_source_field() {
        let f = fixture("kepler").unwrap();
        let mut a = vec![[0.0; 3]; f.bodies.len()];
        ForceEvaluator::new(ForceMode::BarnesHut, &f.bodies).evaluate(&f.bodies, &mut a);
        assert_eq!(a[0], [0.0; 3]);
        assert_eq!(a[1], [-1.0, 0.0, 0.0]);
    }

    #[test]
    fn central_corrections_match_circular_equatorial_formulae() {
        let f = fixture("kepler").unwrap();
        for (mode, x) in [
            (ForceMode::FirstPostNewtonian, -1.0 + 3.0 / 10_000.0),
            (ForceMode::Radiation, -0.999),
            (ForceMode::J2, -1.0 - 1.5 * 0.001 * 0.04),
        ] {
            let mut a = vec![[0.0; 3]; f.bodies.len()];
            ForceEvaluator::new(mode, &f.bodies).evaluate(&f.bodies, &mut a);
            assert!((a[1][0] - x).abs() < 1.0e-15);
            assert_eq!(a[1][1], 0.0);
        }
    }

    #[test]
    fn fp32_forecast_keeps_massive_source_forces_exact_fp64() {
        let f = fixture("sparse3").unwrap();
        let mut direct = vec![[0.0; 3]; f.bodies.len()];
        let mut mixed = direct.clone();
        ForceEvaluator::new(ForceMode::Direct, &f.bodies).evaluate(&f.bodies, &mut direct);
        ForceEvaluator::new(ForceMode::Float32, &f.bodies).evaluate(&f.bodies, &mut mixed);
        for (i, b) in f.bodies.iter().enumerate() {
            if b.mu > 0.0 {
                assert_eq!(direct[i], mixed[i]);
            }
        }
    }
}
