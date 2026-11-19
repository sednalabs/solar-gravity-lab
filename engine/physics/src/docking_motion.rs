//! Checked physical motion and finite feature-resolution bounds for local contacts.
use super::*;

pub(super) struct MotionEnvelope {
    pub origin: Vector3d,
    pub bulk_velocity: Vector3d,
    linear_speed_mps: Vec<f64>,
    point_speed_mps: Vec<f64>,
    physical_point_speed_mps: Vec<f64>,
    pub attachment_allowance_m: f64,
}

pub(super) fn island_order(pairs: &[(usize, usize)], ships: &[SpacecraftBinding]) -> Vec<usize> {
    let mut order: Vec<usize> = pairs.iter().flat_map(|&(a, b)| [a, b]).collect();
    order.sort_by(|&a, &b| ships[a].state.body_id.0.cmp(&ships[b].state.body_id.0));
    order.dedup();
    order
}

impl MotionEnvelope {
    /// Physical energy includes spin: an impact can transfer rotation into a
    /// different body's translation. Pair-relative speed alone misses this,
    /// particularly for unequal masses. Solver corrections have a separate,
    /// explicit allowance which is checked after every solve.
    pub fn new(
        bodies: &[MassiveBodyState],
        ships: &[SpacecraftBinding],
        pairs: &[(usize, usize)],
        order: &[usize],
        geometry: &ContactGeometryCache,
    ) -> Result<Self, ()> {
        let reference = bodies[ships[*order.first().ok_or(())?].body_index];
        let mut total_mass = 0.0;
        let mut weighted_position = Vector3d::default();
        let mut weighted_velocity = Vector3d::default();
        for &index in order {
            let mass = ships[index].state.mass_kg();
            let body = bodies[ships[index].body_index];
            total_mass += mass;
            weighted_position = add(
                weighted_position,
                scale(sub(body.position_m, reference.position_m), mass),
            );
            weighted_velocity = add(
                weighted_velocity,
                scale(sub(body.velocity_mps, reference.velocity_mps), mass),
            );
        }
        let origin = add(
            reference.position_m,
            scale(weighted_position, 1.0 / total_mass),
        );
        let bulk_velocity = add(
            reference.velocity_mps,
            scale(weighted_velocity, 1.0 / total_mass),
        );
        if !total_mass.is_finite() || total_mass <= 0.0 || !finite(origin) || !finite(bulk_velocity)
        {
            return Err(());
        }
        let mut twice_energy = 0.0;
        for &index in order {
            let ship = &ships[index].state;
            let speed = magnitude(sub(
                bodies[ships[index].body_index].velocity_mps,
                bulk_velocity,
            ));
            let w = ship.angular_velocity_body_radps;
            let i = ship.inertia_kg_m2;
            twice_energy += ship.mass_kg() * speed * speed
                + i.x * w.x * w.x
                + i.y * w.y * w.y
                + i.z * w.z * w.z;
        }
        if !twice_energy.is_finite() || twice_energy < 0.0 {
            return Err(());
        }
        let mut attachment_allowance_m = 0.0;
        for &(index, target_index) in pairs {
            let ship = &ships[index].state;
            let target = &ships[target_index].state;
            let Some(attachment) = ship.docking.as_ref().and_then(|d| d.attachment) else {
                continue;
            };
            let residual = magnitude(sub(
                sub(
                    bodies[ships[index].body_index].position_m,
                    bodies[ships[target_index].body_index].position_m,
                ),
                target.attitude.rotate(attachment.position_target_m),
            ));
            let angle = magnitude(rotation_vector(
                ship.attitude
                    .conjugate()
                    .multiply(target.attitude)
                    .multiply(attachment.attitude_target),
            ));
            // Match the persisted rest-pose envelope. Malformed joints must
            // not inject arbitrary correction into a reusable local world.
            if !residual.is_finite()
                || residual > 0.020_000_001
                || !angle.is_finite()
                || angle > 0.002_000_001
            {
                return Err(());
            }
            attachment_allowance_m += residual
                + angle
                    * (magnitude(attachment.position_target_m)
                        + geometry.0[index].radius_m
                        + geometry.0[target_index].radius_m);
        }
        let correction_speed = CONTACT_CORRECTIVE_SPEED_MPS * order.len() as f64;
        let mut linear_speed_mps = vec![0.0; ships.len()];
        let mut point_speed_mps = vec![0.0; ships.len()];
        let mut physical_point_speed_mps = vec![0.0; ships.len()];
        for &index in order {
            let ship = &ships[index].state;
            let inertia = ship
                .inertia_kg_m2
                .x
                .min(ship.inertia_kg_m2.y)
                .min(ship.inertia_kg_m2.z);
            let linear = (twice_energy / ship.mass_kg()).sqrt();
            let angular = (twice_energy / inertia).sqrt();
            linear_speed_mps[index] = linear + correction_speed;
            physical_point_speed_mps[index] = linear + angular * geometry.0[index].radius_m;
            point_speed_mps[index] = physical_point_speed_mps[index] + 2.0 * correction_speed;
            if !linear_speed_mps[index].is_finite() || !point_speed_mps[index].is_finite() {
                return Err(());
            }
        }
        Ok(Self {
            origin,
            bulk_velocity,
            linear_speed_mps,
            point_speed_mps,
            physical_point_speed_mps,
            attachment_allowance_m,
        })
    }

    pub fn center_budget(&self, index: usize, dt: f64) -> f64 {
        self.linear_speed_mps[index] * dt
            + self.attachment_allowance_m
            + CONTACT_NUMERICAL_TOLERANCE_M
    }

    pub fn point_budget(&self, index: usize, dt: f64) -> f64 {
        self.point_speed_mps[index] * dt
            + self.attachment_allowance_m
            + CONTACT_NUMERICAL_TOLERANCE_M
    }

    /// Bound free drift until any complete rotating bounding spheres may touch.
    /// This avoids resolving metre-scale empty travel at millimetre time steps.
    /// Contacts, joint corrections and spin-to-translation transfer all remain
    /// covered by the same checked envelope used to form the island.
    pub fn free_drift_seconds(
        &self,
        bodies: &[MassiveBodyState],
        ships: &[SpacecraftBinding],
        order: &[usize],
        geometry: &ContactGeometryCache,
    ) -> f64 {
        let mut safe = f64::INFINITY;
        for (slot, &a) in order.iter().enumerate() {
            for &b in &order[slot + 1..] {
                let sphere_gap = magnitude(sub(
                    bodies[ships[a].body_index].position_m,
                    bodies[ships[b].body_index].position_m,
                )) - geometry.0[a].radius_m
                    - geometry.0[b].radius_m;
                let (clearance, closing_bound) = if sphere_gap > 0.0 {
                    (
                        sphere_gap,
                        self.linear_speed_mps[a] + self.linear_speed_mps[b],
                    )
                } else {
                    // Large navigation-shaped spheres overlap well before a
                    // solar array or collar can touch. Query the same physical
                    // shapes used by Rapier to avoid thousands of tiny steps
                    // through empty space inside those spheres.
                    let left_pose = Pose::from_parts(
                        vector(bodies[ships[a].body_index].position_m),
                        rotation(ships[a].state.attitude),
                    );
                    let right_pose = Pose::from_parts(
                        vector(bodies[ships[b].body_index].position_m),
                        rotation(ships[b].state.attitude),
                    );
                    let mut clearance = f64::INFINITY;
                    for left in [&geometry.0[a].hull, &geometry.0[a].port] {
                        for right in [&geometry.0[b].hull, &geometry.0[b].port] {
                            let distance = rapier3d_f64::parry::query::distance(
                                &left_pose,
                                left.as_ref(),
                                &right_pose,
                                right.as_ref(),
                            );
                            // An unsupported/nonfinite distance cannot justify
                            // a free jump; use full feature resolution instead.
                            clearance = clearance.min(match distance {
                                Ok(value)
                                    if value.distance.is_finite() && value.distance >= 0.0 =>
                                {
                                    value.distance
                                }
                                _ => 0.0,
                            });
                        }
                    }
                    (clearance, self.point_speed_mps[a] + self.point_speed_mps[b])
                };
                let gap = clearance
                    - 2.0 * self.attachment_allowance_m
                    - 2.0 * CONTACT_NUMERICAL_TOLERANCE_M;
                if gap <= 0.0 {
                    return 0.0;
                }
                safe = safe.min(gap / closing_bound);
            }
        }
        safe
    }

    pub fn resolved_step_seconds(&self, order: &[usize], min_feature_m: f64) -> f64 {
        let speed = order
            .iter()
            .map(|&i| self.physical_point_speed_mps[i])
            .fold(0.0, f64::max);
        CONTACT_FEATURE_MOTION_FRACTION * min_feature_m / speed
    }

    pub fn can_touch(
        &self,
        bodies: &[MassiveBodyState],
        ships: &[SpacecraftBinding],
        geometry: &ContactGeometryCache,
        a: usize,
        b: usize,
        dt: f64,
    ) -> bool {
        let reach = geometry.0[a].radius_m
            + geometry.0[b].radius_m
            + self.center_budget(a, dt)
            + self.center_budget(b, dt);
        magnitude(sub(
            bodies[ships[a].body_index].position_m,
            bodies[ships[b].body_index].position_m,
        )) <= reach
    }
}
