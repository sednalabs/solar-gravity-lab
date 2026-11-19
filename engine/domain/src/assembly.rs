//! Immutable authored spacecraft composition, persisted alongside flight state.
//! Geometry is body-relative SI data. It has no independent pose or velocity.
use crate::spacecraft::{finite, magnitude};
use crate::{Quaterniond, Vector3d};
use serde::{Deserialize, Serialize};

pub const MAX_CONTACT_PARTS: usize = 32;
pub const MAX_CONTACT_RADIUS_M: f64 = 50.0;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftAssembly {
    pub definition_id: String,
    pub display_name: String,
    /// Build-time catalog identity and payload digest, checked by the runtime.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub visual_asset: Option<SpacecraftVisualBinding>,
    /// None retains the selected built-in model's contact geometry.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact: Option<SpacecraftContactGeometry>,
}

impl SpacecraftAssembly {
    #[must_use]
    pub fn valid(&self) -> bool {
        valid_text(&self.definition_id, 96)
            && valid_text(&self.display_name, 128)
            && self
                .visual_asset
                .as_ref()
                .is_none_or(SpacecraftVisualBinding::valid)
            && self
                .contact
                .as_ref()
                .is_none_or(SpacecraftContactGeometry::valid)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftVisualBinding {
    pub asset_id: String,
    pub mesh_kind: u32,
    pub geometry_sha256: String,
}
impl SpacecraftVisualBinding {
    #[must_use]
    pub fn valid(&self) -> bool {
        valid_text(&self.asset_id, 96)
            && (1..=65535).contains(&self.mesh_kind)
            && self.geometry_sha256.len() == 64
            && self
                .geometry_sha256
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    }
}

fn valid_text(value: &str, limit: usize) -> bool {
    !value.trim().is_empty() && value.len() <= limit && !value.chars().any(char::is_control)
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftContactGeometry {
    pub hull: Vec<ContactPart>,
    /// One active mating port per vehicle. The port's +Z points outward and +Y
    /// defines roll alignment. Its physical annular flange extends behind z=0.
    pub port: DockingPortGeometry,
}

impl SpacecraftContactGeometry {
    #[must_use]
    pub fn valid(&self) -> bool {
        !self.hull.is_empty()
            && self.hull.len() <= MAX_CONTACT_PARTS
            && self.hull.iter().all(ContactPart::valid)
            && self.port.valid()
            && self.bounding_radius_m().is_finite()
            && self.bounding_radius_m() <= MAX_CONTACT_RADIUS_M
    }

    /// Conservative body-centred sphere for rotation and swept broad phase.
    #[must_use]
    pub fn bounding_radius_m(&self) -> f64 {
        self.hull
            .iter()
            .fold(self.port.bounding_radius_m(), |r, p| {
                r.max(magnitude(p.position_body_m) + p.shape.bounding_radius_m())
            })
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ContactPart {
    pub position_body_m: Vector3d,
    #[serde(default)]
    pub attitude_body: Quaterniond,
    pub shape: ContactShape,
}

impl ContactPart {
    #[must_use]
    pub fn valid(&self) -> bool {
        finite(self.position_body_m)
            && magnitude(self.position_body_m) <= MAX_CONTACT_RADIUS_M
            && self.attitude_body.is_unit()
            && self.shape.valid()
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum ContactShape {
    Box { half_extents_m: Vector3d },
    Sphere { radius_m: f64 },
}

impl ContactShape {
    #[must_use]
    pub fn valid(&self) -> bool {
        let dimension = |n: f64| n.is_finite() && (0.001..=MAX_CONTACT_RADIUS_M).contains(&n);
        match self {
            Self::Box { half_extents_m: v } => dimension(v.x) && dimension(v.y) && dimension(v.z),
            Self::Sphere { radius_m } => dimension(*radius_m),
        }
    }

    #[must_use]
    pub fn bounding_radius_m(&self) -> f64 {
        match self {
            Self::Box { half_extents_m } => magnitude(*half_extents_m),
            Self::Sphere { radius_m } => *radius_m,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct DockingPortGeometry {
    pub position_body_m: Vector3d,
    #[serde(default)]
    pub attitude_body: Quaterniond,
    pub inner_radius_m: f64,
    pub outer_radius_m: f64,
    pub depth_m: f64,
}

impl DockingPortGeometry {
    #[must_use]
    pub fn valid(&self) -> bool {
        finite(self.position_body_m)
            && magnitude(self.position_body_m) <= MAX_CONTACT_RADIUS_M
            && self.attitude_body.is_unit()
            && self.inner_radius_m.is_finite()
            && self.outer_radius_m.is_finite()
            && self.inner_radius_m >= 0.01
            && self.outer_radius_m > self.inner_radius_m + 0.001
            && self.outer_radius_m <= 10.0
            && self.depth_m.is_finite()
            && (0.005..=5.0).contains(&self.depth_m)
    }

    #[must_use]
    pub fn bounding_radius_m(&self) -> f64 {
        // The polygonal annulus uses tangential boxes. The 1.01 factor bounds
        // their corners (24 segments) rather than claiming an exact cylinder.
        magnitude(self.position_body_m) + (1.01 * self.outer_radius_m).hypot(self.depth_m)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn geometry_rejects_nonphysical_or_unbounded_parts() {
        let mut part = ContactPart {
            position_body_m: Vector3d::default(),
            attitude_body: Quaterniond::IDENTITY,
            shape: ContactShape::Sphere { radius_m: 1.0 },
        };
        assert!(part.valid());
        part.shape = ContactShape::Sphere { radius_m: f64::NAN };
        assert!(!part.valid());
        part.shape = ContactShape::Box {
            half_extents_m: Vector3d {
                x: 1.0,
                y: -1.0,
                z: 1.0,
            },
        };
        assert!(!part.valid());
        part.shape = ContactShape::Sphere { radius_m: 1.0 };
        part.attitude_body.w = 0.0;
        assert!(!part.valid());
    }
}
