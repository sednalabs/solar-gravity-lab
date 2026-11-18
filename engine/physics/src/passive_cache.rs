//! Hot input storage for independent passive-target gravity.
//!
//! The world remains authoritative AoS FP64 state. Only the four doubles read
//! by the force loop are copied here, in source order, after every drift.

use super::{MassiveBodyState, G_M3_PER_KG_S2};

/// Two source records fit a 64-byte cache line. Velocities and body metadata
/// never enter the repeated source scan. Alignment is a layout choice, not an
/// assumption about the cache line size of a particular Arm implementation.
#[derive(Clone, Copy, Debug, PartialEq)]
#[repr(C, align(32))]
pub(super) struct PackedSource {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub mu: f64,
}

#[derive(Default)]
pub(super) struct PassiveCache {
    body_count: usize,
    source_indices: Vec<usize>,
    pub sources: Vec<PackedSource>,
    pub targets: Vec<usize>,
}

impl PassiveCache {
    pub fn refresh(&mut self, bodies: &[MassiveBodyState], source_indices: &[usize]) {
        // Source membership is invariant within a KDK segment. Still invalidate
        // explicitly when reused across a changed world, including promotion of
        // a probe to a source. No content hash or stale-position cache is used.
        if self.body_count != bodies.len() || self.source_indices != source_indices {
            self.body_count = bodies.len();
            self.source_indices.clear();
            self.source_indices.extend_from_slice(source_indices);
            self.targets.clear();
            self.targets.extend(
                bodies.iter().enumerate().filter_map(|(index, body)| {
                    (body.mass_kg == 0.0).then_some(index)
                }),
            );
        }
        // Reuse allocation; refresh every source position and mass every sweep.
        // Multiplying G*m once here retains the exact original FP64 expression.
        self.sources.clear();
        self.sources.extend(source_indices.iter().map(|&index| {
            let body = &bodies[index];
            PackedSource {
                x: body.position_m.x,
                y: body.position_m.y,
                z: body.position_m.z,
                mu: G_M3_PER_KG_S2 * body.mass_kg,
            }
        }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source_indices;
    use solarlab_domain::Vector3d;

    #[test]
    fn cache_refresh_preserves_order_reuses_storage_and_tracks_source_changes() {
        let mut bodies: Vec<_> = [0.0, 2.0, 0.0, 3.0, 0.0]
            .into_iter()
            .enumerate()
            .map(|(index, mass_kg)| MassiveBodyState {
                mass_kg,
                position_m: Vector3d { x: index as f64, y: -2.0, z: 3.0 },
                velocity_mps: Vector3d::default(),
            })
            .collect();
        let mut cache = PassiveCache::default();
        cache.refresh(&bodies, &source_indices(&bodies));
        assert_eq!(cache.targets, [0, 2, 4]);
        assert_eq!(cache.sources.iter().map(|source| source.x).collect::<Vec<_>>(), [1.0, 3.0]);
        assert_eq!(std::mem::size_of::<PackedSource>(), 32);
        assert_eq!(std::mem::align_of::<PackedSource>(), 32);
        let source_allocation = cache.sources.as_ptr();
        let target_allocation = cache.targets.as_ptr();
        bodies[1].position_m.x = 17.0;
        bodies[3].mass_kg = 5.0;
        cache.refresh(&bodies, &source_indices(&bodies));
        assert_eq!(cache.sources[0].x, 17.0);
        assert_eq!(cache.sources[1].mu, G_M3_PER_KG_S2 * 5.0);
        assert_eq!(cache.sources.as_ptr(), source_allocation);
        assert_eq!(cache.targets.as_ptr(), target_allocation);
        bodies[1].mass_kg = 0.0;
        bodies[2].mass_kg = 7.0;
        cache.refresh(&bodies, &source_indices(&bodies));
        assert_eq!(cache.targets, [0, 1, 4]);
        assert_eq!(cache.source_indices, [2, 3]);
        bodies.truncate(2);
        cache.refresh(&bodies, &source_indices(&bodies));
        assert_eq!(cache.targets, [0, 1]);
        assert!(cache.sources.is_empty());
    }
}
