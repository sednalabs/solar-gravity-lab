//! Versioned data-only construction through the authoritative runtime.
//! Documents are initial conditions, never executable plugins or shadow worlds.
use super::*;
use serde::{Deserialize, Serialize};
use solarlab_domain::science::ScientificSettings;
use solarlab_domain::spacecraft::{add, cross, dot, finite, magnitude, scale, unit};
use solarlab_domain::{
    AttitudeMode, EngineControl, SpacecraftAppearance, SpacecraftAssembly,
    SpacecraftContactGeometry, SpacecraftDockingState, SpacecraftEngine,
};
use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};

pub const AUTHORED_SCENARIO_FORMAT: &str = "solarlab.scenario";
pub const AUTHORED_SPACECRAFT_FORMAT: &str = "solarlab.spacecraft";
pub const AUTHORING_VERSION: u32 = 1;
pub const MAX_AUTHORING_BYTES: usize = 1024 * 1024;
const MAX_DEFINITIONS: usize = 64;
const MAX_INSTANCES: usize = 64;
const MAX_AUTHORED_BODIES: usize = 256;
const STANDARD_GRAVITY_MPS2: f64 = 9.80665;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AuthoringError {
    TooLarge,
    InvalidDocument(String),
    UnsupportedVersion,
    InvalidDefinition { id: String, reason: &'static str },
    InvalidScenario(&'static str),
    UnresolvedReference(String),
    Runtime(String),
}
impl std::fmt::Display for AuthoringError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::TooLarge => f.write_str("Authored document exceeds the 1 MiB limit"),
            Self::InvalidDocument(reason) => write!(f, "Invalid authored JSON: {reason}"),
            Self::UnsupportedVersion => f.write_str("Unsupported authored format or version"),
            Self::InvalidDefinition { id, reason } => write!(f, "Spacecraft '{id}': {reason}"),
            Self::InvalidScenario(reason) => write!(f, "Invalid scenario: {reason}"),
            Self::UnresolvedReference(id) => write!(f, "Unresolved or cyclic body reference: {id}"),
            Self::Runtime(reason) => write!(f, "Cannot construct authored world: {reason}"),
        }
    }
}
impl std::error::Error for AuthoringError {}
impl From<RuntimeError> for AuthoringError {
    fn from(error: RuntimeError) -> Self {
        Self::Runtime(format!("{error:?}"))
    }
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum ScenarioBase {
    #[default]
    Empty,
    EarthMoon,
}

/// Stable identifier resolved against the generated build-time asset catalog.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(transparent)]
pub struct SpacecraftVisualAsset(pub String);
impl SpacecraftVisualAsset {
    fn record(&self) -> Option<&'static solarlab_data::SpacecraftVisualAssetRecord> {
        solarlab_data::spacecraft_visual_asset(&self.0)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftStructure {
    pub dry_mass_kg: f64,
    /// Principal moments in body axes; fixed as fuel is consumed.
    pub inertia_kg_m2: Vector3d,
    /// Ideal reaction wheels, not reaction-control propellant.
    pub max_control_torque_nm: Vector3d,
    /// Encloses the visual asset and authored contact geometry.
    pub radius_m: f64,
}
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct PropellantTankDefinition {
    pub capacity_kg: f64,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AuthoredEngineControl {
    Main,
    PositiveX,
    NegativeX,
    PositiveY,
    NegativeY,
    PositiveZ,
    NegativeZ,
}
impl AuthoredEngineControl {
    fn domain(self) -> EngineControl {
        match self {
            Self::Main => EngineControl::Main,
            Self::PositiveX => EngineControl::PositiveX,
            Self::NegativeX => EngineControl::NegativeX,
            Self::PositiveY => EngineControl::PositiveY,
            Self::NegativeY => EngineControl::NegativeY,
            Self::PositiveZ => EngineControl::PositiveZ,
            Self::NegativeZ => EngineControl::NegativeZ,
        }
    }
}
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct EngineDefinition {
    pub control: AuthoredEngineControl,
    /// Unit direction of the applied force, not the exhaust.
    pub direction_body: Vector3d,
    pub position_body_m: Vector3d,
    pub max_thrust_n: f64,
    pub specific_impulse_seconds: f64,
}
impl EngineDefinition {
    fn compose(&self) -> SpacecraftEngine {
        SpacecraftEngine {
            control: self.control.domain(),
            direction_body: self.direction_body,
            position_body_m: self.position_body_m,
            max_thrust_n: self.max_thrust_n,
            max_mass_flow_kgps: self.max_thrust_n
                / (STANDARD_GRAVITY_MPS2 * self.specific_impulse_seconds),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftDefinition {
    pub id: String,
    pub display_name: String,
    pub visual_asset: SpacecraftVisualAsset,
    pub structure: SpacecraftStructure,
    pub propellant: PropellantTankDefinition,
    #[serde(default)]
    pub engines: Vec<EngineDefinition>,
    /// Omission uses the built-in asset's documented collision approximation.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub contact: Option<SpacecraftContactGeometry>,
}
impl SpacecraftDefinition {
    pub fn validate(&self) -> Result<(), AuthoringError> {
        let fail = |reason| AuthoringError::InvalidDefinition {
            id: self.id.clone(),
            reason,
        };
        let s = &self.structure;
        let asset = self
            .visual_asset
            .record()
            .ok_or_else(|| fail("unregistered visual asset"))?;
        if asset.contact_profile.is_none() && self.contact.is_none() {
            return Err(fail(
                "a custom visual asset requires explicit physical contact geometry",
            ));
        }
        if !valid_text(&self.id, 96) || !valid_text(&self.display_name, 128) {
            return Err(fail("invalid identity or display name"));
        }
        if !s.dry_mass_kg.is_finite()
            || !(0.001..=1.0e12).contains(&s.dry_mass_kg)
            || !self.propellant.capacity_kg.is_finite()
            || !(0.0..=1.0e12).contains(&self.propellant.capacity_kg)
        {
            return Err(fail(
                "dry mass or propellant capacity is outside the supported range",
            ));
        }
        let i = s.inertia_kg_m2;
        if !finite(i)
            || [i.x, i.y, i.z]
                .into_iter()
                .any(|v| !(1.0e-6..=1.0e20).contains(&v))
            || i.x + i.y < i.z
            || i.x + i.z < i.y
            || i.y + i.z < i.x
        {
            return Err(fail(
                "principal inertia must be positive and obey triangle inequalities",
            ));
        }
        let t = s.max_control_torque_nm;
        if !finite(t)
            || [t.x, t.y, t.z]
                .into_iter()
                .any(|v| !(0.0..=1.0e12).contains(&v))
        {
            return Err(fail("invalid reaction-wheel torque"));
        }
        if !s.radius_m.is_finite() || !(asset.framing_radius_m..=1000.0).contains(&s.radius_m) {
            return Err(fail(
                "radius must enclose the selected visual asset and be at most 1000 m",
            ));
        }
        if self
            .contact
            .as_ref()
            .is_some_and(|c| !c.valid() || c.bounding_radius_m() > s.radius_m)
        {
            return Err(fail(
                "contact geometry is invalid or extends outside the framing radius",
            ));
        }
        if self.engines.len() > 64
            || self.engines.iter().any(|e| {
                !finite(e.direction_body)
                    || (magnitude(e.direction_body) - 1.0).abs() > 1.0e-8
                    || !finite(e.position_body_m)
                    || magnitude(e.position_body_m) > s.radius_m
                    || !e.max_thrust_n.is_finite()
                    || !(0.001..=1.0e12).contains(&e.max_thrust_n)
                    || !e.specific_impulse_seconds.is_finite()
                    || !(0.01..=1.0e7).contains(&e.specific_impulse_seconds)
            })
        {
            return Err(fail(
                "invalid engine direction, mounting point, thrust or specific impulse",
            ));
        }
        Ok(())
    }
    fn compose(&self, instance: &SpacecraftInstance) -> Result<SpacecraftState, AuthoringError> {
        self.validate()?;
        let propellant = instance
            .propellant_kg
            .unwrap_or(self.propellant.capacity_kg);
        if !propellant.is_finite() || !(0.0..=self.propellant.capacity_kg).contains(&propellant) {
            return Err(AuthoringError::InvalidDefinition {
                id: self.id.clone(),
                reason: "instance fuel exceeds tank capacity",
            });
        }
        let mut ship = SpacecraftState::reference(
            BodyId(instance.body_id.clone()),
            instance.reference_body_id.clone().map(BodyId),
        );
        ship.authored = Some(SpacecraftAssembly {
            definition_id: self.id.clone(),
            display_name: self.display_name.clone(),
            visual_asset: self.visual_asset.record().map(|asset| {
                solarlab_domain::SpacecraftVisualBinding {
                    asset_id: asset.asset_id.into(),
                    mesh_kind: asset.mesh_kind,
                    geometry_sha256: asset.geometry_sha256.into(),
                }
            }),
            contact: self.contact.clone(),
        });
        ship.appearance = self
            .visual_asset
            .record()
            .and_then(|asset| asset.contact_profile)
            .unwrap_or(SpacecraftAppearance::Asteria);
        ship.dry_mass_kg = self.structure.dry_mass_kg;
        ship.propellant_mass_kg = propellant;
        ship.inertia_kg_m2 = self.structure.inertia_kg_m2;
        ship.max_control_torque_nm = self.structure.max_control_torque_nm;
        ship.engines = self.engines.iter().map(EngineDefinition::compose).collect();
        ship.angular_velocity_body_radps = instance.angular_velocity_body_radps;
        ship.control = FlightControl {
            attitude_mode: AttitudeMode::Manual,
            ..FlightControl::default()
        };
        ship.docking = instance
            .docking_target_body_id
            .clone()
            .map(|id| SpacecraftDockingState::new(BodyId(id)));
        Ok(ship)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum SpacecraftPlacement {
    Inertial {
        position_m: Vector3d,
        velocity_mps: Vector3d,
    },
    /// Offsets use inertial world axes, not a rotating orbital frame.
    RelativeState {
        host_body_id: String,
        position_m: Vector3d,
        velocity_mps: Vector3d,
    },
    /// Approximate two-body insertion. The complete world force law immediately
    /// applies afterwards, including other gravitating bodies and harmonics.
    CircularOrbit {
        host_body_id: String,
        altitude_m: f64,
        radial_direction: Vector3d,
        orbit_normal: Vector3d,
    },
}
impl SpacecraftPlacement {
    fn host(&self) -> Option<&str> {
        match self {
            Self::Inertial { .. } => None,
            Self::RelativeState { host_body_id, .. } | Self::CircularOrbit { host_body_id, .. } => {
                Some(host_body_id)
            }
        }
    }
    fn resolve(
        &self,
        world: &WorldRuntime,
    ) -> Result<(Vector3d, Vector3d, Quaterniond), AuthoringError> {
        let host = self
            .host()
            .map(|id| {
                world
                    .body_by_id(id)
                    .ok_or_else(|| AuthoringError::UnresolvedReference(id.into()))
            })
            .transpose()?;
        let (position, velocity, forward) = match self {
            Self::Inertial {
                position_m,
                velocity_mps,
            } => (*position_m, *velocity_mps, None),
            Self::RelativeState {
                position_m,
                velocity_mps,
                ..
            } => {
                let host = host.ok_or(AuthoringError::InvalidScenario("missing placement host"))?;
                (
                    add(host.position_m, *position_m),
                    add(host.velocity_mps, *velocity_mps),
                    None,
                )
            }
            Self::CircularOrbit {
                altitude_m,
                radial_direction,
                orbit_normal,
                ..
            } => {
                let host = host.ok_or(AuthoringError::InvalidScenario("missing orbital host"))?;
                let radial = unit(*radial_direction).ok_or(AuthoringError::InvalidScenario(
                    "orbital radial direction is zero",
                ))?;
                let normal = unit(*orbit_normal)
                    .ok_or(AuthoringError::InvalidScenario("orbit normal is zero"))?;
                if !altitude_m.is_finite()
                    || *altitude_m <= 0.0
                    || dot(radial, normal).abs() > 1.0e-8
                    || host.source_mass_kg <= 0.0
                {
                    return Err(AuthoringError::InvalidScenario("circular placement needs a massive host, positive altitude and perpendicular axes"));
                }
                let radius = host.radius_m + altitude_m;
                let tangent = cross(normal, radial);
                let velocity = scale(tangent, (6.67430e-11 * host.source_mass_kg / radius).sqrt());
                (
                    add(host.position_m, scale(radial, radius)),
                    add(host.velocity_mps, velocity),
                    Some(tangent),
                )
            }
        };
        if !valid_position(position) || !valid_velocity(velocity) {
            return Err(AuthoringError::InvalidScenario(
                "placement is non-finite or outside the supported inertial range",
            ));
        }
        Ok((
            position,
            velocity,
            forward
                .and_then(Quaterniond::pointing)
                .unwrap_or(Quaterniond::IDENTITY),
        ))
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftInstance {
    pub body_id: String,
    pub definition_id: String,
    #[serde(default)]
    pub reference_body_id: Option<String>,
    pub placement: SpacecraftPlacement,
    /// Omission points along circular-insertion velocity, otherwise identity.
    #[serde(default)]
    pub attitude: Option<Quaterniond>,
    #[serde(default)]
    pub angular_velocity_body_radps: Vector3d,
    #[serde(default)]
    pub propellant_kg: Option<f64>,
    #[serde(default)]
    pub docking_target_body_id: Option<String>,
    /// Construction only; never corrects an in-flight pose.
    #[serde(default)]
    pub align_to_docking_target: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct AuthoredBody {
    pub body_id: String,
    pub body_class: BodyClass,
    pub mass_kg: f64,
    #[serde(default)]
    pub source_mass_kg: Option<f64>,
    pub radius_m: f64,
    pub position_m: Vector3d,
    pub velocity_mps: Vector3d,
}
impl AuthoredBody {
    fn compose(&self) -> Result<BodyState, AuthoringError> {
        let source_mass = self
            .source_mass_kg
            .unwrap_or_else(|| BodyState::default_source_mass_kg(&self.body_class, self.mass_kg));
        if !valid_text(&self.body_id, 96)
            || self.body_class == BodyClass::Spacecraft
            || !self.mass_kg.is_finite()
            || !(0.0..=1.0e35).contains(&self.mass_kg)
            || !source_mass.is_finite()
            || !(0.0..=self.mass_kg).contains(&source_mass)
            || !self.radius_m.is_finite()
            || !(0.001..=1.0e15).contains(&self.radius_m)
            || !valid_position(self.position_m)
            || !valid_velocity(self.velocity_mps)
        {
            return Err(AuthoringError::InvalidScenario(
                "invalid celestial body; spacecraft require a definition and instance",
            ));
        }
        Ok(BodyState {
            body_id: BodyId(self.body_id.clone()),
            body_class: self.body_class,
            mass_kg: self.mass_kg,
            source_mass_kg: source_mass,
            radius_m: self.radius_m,
            position_m: self.position_m,
            velocity_mps: self.velocity_mps,
        })
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ScenarioDefinition {
    pub format: String,
    pub version: u32,
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub base: ScenarioBase,
    #[serde(default)]
    pub scientific_settings: ScientificSettings,
    #[serde(default)]
    pub bodies: Vec<AuthoredBody>,
    #[serde(default)]
    pub spacecraft_definitions: Vec<SpacecraftDefinition>,
    #[serde(default)]
    pub spacecraft: Vec<SpacecraftInstance>,
    #[serde(default)]
    pub focus_body_id: Option<String>,
}
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SpacecraftDocument {
    pub format: String,
    pub version: u32,
    pub spacecraft_definitions: Vec<SpacecraftDefinition>,
    pub spacecraft: Vec<SpacecraftInstance>,
}
fn parse<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> Result<T, AuthoringError> {
    if bytes.len() > MAX_AUTHORING_BYTES {
        return Err(AuthoringError::TooLarge);
    }
    serde_json::from_slice(bytes).map_err(|e| AuthoringError::InvalidDocument(e.to_string()))
}
fn valid_text(value: &str, limit: usize) -> bool {
    !value.trim().is_empty() && value.len() <= limit && !value.chars().any(char::is_control)
}
fn valid_position(value: Vector3d) -> bool {
    finite(value) && magnitude(value) <= 1.0e18
}
fn valid_velocity(value: Vector3d) -> bool {
    finite(value) && magnitude(value) <= 1.0e8
}

impl WorldRuntime {
    /// Construct a fresh paused runtime. Hosts swap it into the live session only
    /// after success; an invalid document cannot erase an existing experiment.
    pub fn import_authored_scenario(
        bytes: &[u8],
        hardware_profile: HardwareProfile,
        time: i64,
    ) -> Result<Self, AuthoringError> {
        let document: ScenarioDefinition = parse(bytes)?;
        if document.format != AUTHORED_SCENARIO_FORMAT || document.version != AUTHORING_VERSION {
            return Err(AuthoringError::UnsupportedVersion);
        }
        if !valid_text(&document.id, 96)
            || !valid_text(&document.title, 128)
            || document.bodies.len() > MAX_AUTHORED_BODIES
            || !document.scientific_settings.valid()
        {
            return Err(AuthoringError::InvalidScenario(
                "invalid identity, body count or scientific settings",
            ));
        }
        let mut world = Self::new(
            ScenarioId(document.id.clone()),
            BranchId("authored-root".into()),
            RuntimeConfig {
                physics: PhysicsPolicy {
                    solver_backend: SolverBackend::ReferenceScalar,
                    integrator: IntegratorKind::LeapfrogKickDriftKick,
                    collision_model: CollisionModel::None,
                    max_substep_seconds: 10.0,
                },
                timeline_semantics: TimelineSemantics::BranchedSandbox,
                live_updates_enabled: false,
            },
            hardware_profile,
            time,
        );
        if document.base == ScenarioBase::EarthMoon {
            for body in solarlab_data::earth_moon_lab_bodies() {
                world.apply_command(
                    WorldCommand::SpawnBody {
                        body: BodyState {
                            source_mass_kg: BodyState::default_source_mass_kg(
                                &body.body_class,
                                body.mass_kg,
                            ),
                            body_id: BodyId(body.body_id),
                            body_class: body.body_class,
                            mass_kg: body.mass_kg,
                            radius_m: body.radius_m,
                            position_m: body.position_m,
                            velocity_mps: body.velocity_mps,
                        },
                    },
                    time,
                )?;
            }
            world
                .active_branch_mut()
                .world
                .initial_conditions_provenance = Some(InitialConditionsProvenance {
                dataset_id: solarlab_data::EARTH_MOON_LAB_DATASET_ID.into(),
                version: "1.0.0".into(),
                digest: solarlab_data::EARTH_MOON_LAB_DIGEST.into(),
                epoch_jd_tdb: solarlab_data::EARTH_MOON_LAB_EPOCH_JD_TDB,
                frame: "ICRF".into(),
                center: "solar-system-barycentre".into(),
                time_scale: "TDB".into(),
            });
        }
        for body in &document.bodies {
            world.apply_command(
                WorldCommand::SpawnBody {
                    body: body.compose()?,
                },
                time,
            )?;
        }
        world.compose_authored_spacecraft(
            &document.spacecraft_definitions,
            &document.spacecraft,
            time,
        )?;
        world.apply_command(
            WorldCommand::SetScientificSettings {
                settings: document.scientific_settings,
            },
            time,
        )?;
        let focus = document
            .focus_body_id
            .or_else(|| document.spacecraft.first().map(|s| s.body_id.clone()));
        if focus
            .as_ref()
            .is_some_and(|id| world.body_by_id(id).is_none())
        {
            return Err(AuthoringError::InvalidScenario("focus body does not exist"));
        }
        world.apply_command(
            WorldCommand::FocusBody {
                body_id: focus.map(BodyId),
            },
            time,
        )?;
        world.apply_command(
            WorldCommand::SetObserverMode {
                mode: ObserverMode::FollowSelected,
            },
            time,
        )?;
        // The persistence validator is the final shared authority on all
        // references and physical state; a successful import is saveable.
        world
            .export_saved_session()
            .map_err(|e| AuthoringError::Runtime(e.to_string()))?;
        Ok(world)
    }

    /// Atomic insertion into a paused world, including history and counters.
    pub fn add_authored_spacecraft(
        &mut self,
        bytes: &[u8],
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, AuthoringError> {
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy.into());
        }
        if !self.active_branch().world.playback.paused {
            return Err(RuntimeError::BodyEditRequiresPause.into());
        }
        let document: SpacecraftDocument = parse(bytes)?;
        if document.format != AUTHORED_SPACECRAFT_FORMAT || document.version != AUTHORING_VERSION {
            return Err(AuthoringError::UnsupportedVersion);
        }
        let mut staged = self.clone();
        let events = staged.compose_authored_spacecraft(
            &document.spacecraft_definitions,
            &document.spacecraft,
            time,
        )?;
        staged
            .export_saved_session()
            .map_err(|e| AuthoringError::Runtime(e.to_string()))?;
        *self = staged;
        Ok(events)
    }

    fn compose_authored_spacecraft(
        &mut self,
        definitions: &[SpacecraftDefinition],
        instances: &[SpacecraftInstance],
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, AuthoringError> {
        if definitions.len() > MAX_DEFINITIONS || instances.len() > MAX_INSTANCES {
            return Err(AuthoringError::InvalidScenario(
                "too many spacecraft definitions or instances",
            ));
        }
        let mut definitions_by_id = HashMap::new();
        for definition in definitions {
            definition.validate()?;
            if definitions_by_id
                .insert(definition.id.as_str(), definition)
                .is_some()
            {
                return Err(AuthoringError::InvalidScenario(
                    "duplicate spacecraft definition",
                ));
            }
        }
        let mut instance_ids = HashSet::new();
        for instance in instances {
            if !valid_text(&instance.body_id, 96)
                || !instance_ids.insert(instance.body_id.as_str())
                || self.body_by_id(&instance.body_id).is_some()
            {
                return Err(AuthoringError::InvalidScenario(
                    "duplicate or invalid spacecraft body identity",
                ));
            }
            if !definitions_by_id.contains_key(instance.definition_id.as_str()) {
                return Err(AuthoringError::UnresolvedReference(
                    instance.definition_id.clone(),
                ));
            }
            if instance.attitude.is_some_and(|q| !q.is_unit())
                || !finite(instance.angular_velocity_body_radps)
                || magnitude(instance.angular_velocity_body_radps) > 100.0
                || (instance.align_to_docking_target
                    && (instance.attitude.is_some() || instance.docking_target_body_id.is_none()))
            {
                return Err(AuthoringError::InvalidScenario(
                    "invalid or contradictory initial attitude",
                ));
            }
        }
        let mut events = Vec::new();
        let mut remaining: Vec<_> = instances.iter().collect();
        while !remaining.is_empty() {
            let mut progress = false;
            let mut index = 0;
            while index < remaining.len() {
                let instance = remaining[index];
                let dependencies = [
                    instance.placement.host(),
                    instance.reference_body_id.as_deref(),
                    instance.docking_target_body_id.as_deref(),
                ];
                if dependencies
                    .into_iter()
                    .flatten()
                    .any(|id| self.body_by_id(id).is_none())
                {
                    index += 1;
                    continue;
                }
                let definition = definitions_by_id[instance.definition_id.as_str()];
                let mut ship = definition.compose(instance)?;
                if ship.reference_body_id.is_none() {
                    ship.reference_body_id = instance.placement.host().map(|id| BodyId(id.into()));
                }
                let (position_m, velocity_mps, default_attitude) =
                    instance.placement.resolve(self)?;
                ship.attitude = instance.attitude.unwrap_or(default_attitude);
                if instance.align_to_docking_target {
                    let target_id = instance
                        .docking_target_body_id
                        .as_deref()
                        .ok_or(AuthoringError::InvalidScenario("missing docking target"))?;
                    let target = self
                        .spacecraft_by_id(target_id)
                        .ok_or_else(|| AuthoringError::UnresolvedReference(target_id.into()))?;
                    ship.attitude = solarlab_physics::docking::desired_spacecraft_mating_attitude(
                        &ship, target,
                    );
                }
                let body = BodyState {
                    body_id: ship.body_id.clone(),
                    body_class: BodyClass::Spacecraft,
                    mass_kg: ship.mass_kg(),
                    source_mass_kg: 0.0,
                    radius_m: definition.structure.radius_m,
                    position_m,
                    velocity_mps,
                };
                events.extend(self.apply_command(
                    WorldCommand::SpawnSpacecraft {
                        body,
                        spacecraft: ship,
                    },
                    time,
                )?);
                remaining.remove(index);
                progress = true;
            }
            if !progress {
                return Err(AuthoringError::UnresolvedReference(
                    remaining[0].body_id.clone(),
                ));
            }
        }
        Ok(events)
    }
}

#[cfg(test)]
#[path = "authoring_tests.rs"]
mod tests;
