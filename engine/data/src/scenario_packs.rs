use std::collections::BTreeSet;

use solarlab_domain::{BodyClass, ObserverMode, Vector3d};

use crate::{canonical_startup_seed, CanonicalBodySpec};

pub const DEFAULT_SCENARIO_PACK_ID: &str = "sol-system";

const GRAVITATIONAL_CONSTANT_M3_PER_KG_S2: f64 = 6.67430e-11;
const DEFAULT_SHOWCASE_PLAYBACK_RATE: f64 = 21_600.0;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ScenarioPackDescriptor {
    pub scenario_id: String,
    pub title: String,
    pub description: String,
    pub tags: Vec<String>,
    pub default_focus_body_id: Option<String>,
    pub default_observer_mode: ObserverMode,
    pub start_paused: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ScenarioPackSeed {
    pub scenario_id: String,
    pub title: String,
    pub description: String,
    pub tags: Vec<String>,
    pub default_focus_body_id: Option<String>,
    pub default_observer_mode: ObserverMode,
    pub start_paused: bool,
    pub sim_seconds_per_real_second: f64,
    pub bodies: Vec<CanonicalBodySpec>,
}

#[must_use]
pub fn scenario_pack_catalog() -> Vec<ScenarioPackDescriptor> {
    [
        canonical_descriptor(),
        inner_system_descriptor(),
        earth_moon_descriptor(),
        jupiter_system_descriptor(),
        comet_flyby_descriptor(),
        trail_density_descriptor(),
        s25_tile_swarm_descriptor(),
    ]
    .into_iter()
    .collect()
}

#[must_use]
pub fn scenario_pack_seed(scenario_id: &str) -> Option<ScenarioPackSeed> {
    match scenario_id.trim() {
        DEFAULT_SCENARIO_PACK_ID | "" => Some(canonical_pack_seed()),
        "showcase.inner-system" => Some(inner_system_pack_seed()),
        "showcase.earth-moon" => Some(earth_moon_pack_seed()),
        "showcase.jupiter-system" => Some(jupiter_system_pack_seed()),
        "showcase.comet-flyby" => Some(comet_flyby_pack_seed()),
        "stress.trail-density" => Some(trail_density_pack_seed()),
        "stress.s25-tile-swarm" => Some(s25_tile_swarm_pack_seed()),
        _ => None,
    }
}

fn canonical_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        DEFAULT_SCENARIO_PACK_ID,
        "Canonical solar system",
        "Full canonical startup catalog for broad visual and runtime smoke checks.",
        ["canonical", "wide", "default"],
        Some("earth"),
        ObserverMode::FollowSelected,
        false,
    )
}

fn inner_system_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        "showcase.inner-system",
        "Inner system showcase",
        "Sun through Mars plus near-Earth small bodies for dense close-range controls.",
        ["showcase", "close", "small-bodies"],
        Some("earth"),
        ObserverMode::FollowSelected,
        false,
    )
}

fn earth_moon_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        "showcase.earth-moon",
        "Earth and Moon choreography",
        "Close-scale Earth/Moon framing with marker tracers for camera and trail polish.",
        ["showcase", "close", "moon"],
        Some("moon"),
        ObserverMode::FollowHost,
        true,
    )
}

fn jupiter_system_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        "showcase.jupiter-system",
        "Jupiter moon theatre",
        "Jupiter with four bright Galilean moons for high-drama orbit framing.",
        ["showcase", "moons", "outer-system"],
        Some("jupiter"),
        ObserverMode::FollowSelected,
        false,
    )
}

fn comet_flyby_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        "showcase.comet-flyby",
        "Comet flyby",
        "A cinematic small-body pass with planets retained for scale.",
        ["showcase", "comet", "flyby"],
        Some("halley"),
        ObserverMode::FollowSelected,
        false,
    )
}

fn trail_density_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        "stress.trail-density",
        "Trail density stress",
        "A denser tracer field for checking beauty, legibility, and render pressure.",
        ["stress", "trails", "density"],
        Some("sun"),
        ObserverMode::SystemFrame,
        false,
    )
}

fn s25_tile_swarm_descriptor() -> ScenarioPackDescriptor {
    descriptor(
        "stress.s25-tile-swarm",
        "S25 tile swarm",
        "A Galaxy S25 Ultra stress pack with enough deterministic tracers to exercise the Arm64 parallel tiled scheduler.",
        ["stress", "s25", "arm64", "tiles"],
        Some("sun"),
        ObserverMode::SystemFrame,
        false,
    )
}

fn canonical_pack_seed() -> ScenarioPackSeed {
    let bodies = canonical_startup_seed().bodies;
    seed_from_descriptor(
        canonical_descriptor(),
        DEFAULT_SHOWCASE_PLAYBACK_RATE,
        bodies,
    )
}

fn inner_system_pack_seed() -> ScenarioPackSeed {
    let seed = canonical_startup_seed();
    let keep_ids = [
        "sun", "mercury", "venus", "earth", "moon", "mars", "eros", "bennu", "ryugu", "itokawa",
        "apophis",
    ];
    seed_from_descriptor(
        inner_system_descriptor(),
        7_200.0,
        select_bodies_by_id(&seed.bodies, &keep_ids),
    )
}

fn earth_moon_pack_seed() -> ScenarioPackSeed {
    let seed = canonical_startup_seed();
    let mut bodies = select_bodies_by_id(&seed.bodies, &["sun", "earth", "moon"]);
    let earth = body_by_id(&bodies, "earth");
    bodies.extend(marker_ring_around_primary(
        &earth,
        "lunar-marker",
        18,
        3.844e8,
    ));
    seed_from_descriptor(earth_moon_descriptor(), 3_600.0, bodies)
}

fn jupiter_system_pack_seed() -> ScenarioPackSeed {
    let seed = canonical_startup_seed();
    let mut bodies = select_bodies_by_id(&seed.bodies, &["sun", "jupiter", "saturn"]);
    let jupiter = body_by_id(&bodies, "jupiter");
    bodies.extend([
        orbiting_body(
            &jupiter,
            "io",
            BodyClass::Moon,
            8.9319e22,
            1.8216e6,
            4.217e8,
            12.0,
            0.04,
        ),
        orbiting_body(
            &jupiter,
            "europa",
            BodyClass::Moon,
            4.7998e22,
            1.5608e6,
            6.711e8,
            94.0,
            0.47,
        ),
        orbiting_body(
            &jupiter,
            "ganymede",
            BodyClass::Moon,
            1.4819e23,
            2.6341e6,
            1.0704e9,
            188.0,
            0.20,
        ),
        orbiting_body(
            &jupiter,
            "callisto",
            BodyClass::Moon,
            1.0759e23,
            2.4103e6,
            1.8827e9,
            302.0,
            0.28,
        ),
    ]);
    bodies.extend(marker_ring_around_primary(
        &jupiter,
        "jovian-dust",
        24,
        2.5e9,
    ));
    seed_from_descriptor(jupiter_system_descriptor(), 14_400.0, bodies)
}

fn comet_flyby_pack_seed() -> ScenarioPackSeed {
    let seed = canonical_startup_seed();
    let keep_ids = [
        "sun",
        "earth",
        "moon",
        "mars",
        "jupiter",
        "halley",
        "encke",
        "churyumov-gerasimenko",
        "wild-2",
    ];
    let mut bodies = select_bodies_by_id(&seed.bodies, &keep_ids);
    let earth = body_by_id(&bodies, "earth");
    bodies.push(relative_body(
        &earth,
        "flyby-probe",
        BodyClass::Spacecraft,
        1_200.0,
        18.0,
        Vector3d {
            x: -1.2e9,
            y: 2.8e8,
            z: 1.1e8,
        },
        Vector3d {
            x: 5_200.0,
            y: 9_800.0,
            z: 900.0,
        },
    ));
    seed_from_descriptor(comet_flyby_descriptor(), 43_200.0, bodies)
}

fn trail_density_pack_seed() -> ScenarioPackSeed {
    let seed = canonical_startup_seed();
    let mut bodies = select_bodies_by_id(
        &seed.bodies,
        &[
            "sun", "mercury", "venus", "earth", "moon", "mars", "jupiter", "saturn", "uranus",
            "neptune", "ceres", "vesta", "pallas", "hygiea", "psyche",
        ],
    );
    bodies.extend(
        seed.bodies
            .iter()
            .filter(|body| body.body_id.starts_with("belt-"))
            .take(160)
            .cloned(),
    );
    bodies.extend(
        seed.bodies
            .iter()
            .filter(|body| body.body_id.starts_with("oort-"))
            .take(32)
            .cloned(),
    );
    seed_from_descriptor(trail_density_descriptor(), 86_400.0, bodies)
}

fn s25_tile_swarm_pack_seed() -> ScenarioPackSeed {
    let mut bodies = canonical_startup_seed().bodies;
    let sun = body_by_id(&bodies, "sun");
    let earth = body_by_id(&bodies, "earth");
    let jupiter = body_by_id(&bodies, "jupiter");

    bodies.extend(marker_ring_around_primary(
        &sun,
        "s25-inner-tile",
        192,
        4.5e11,
    ));
    bodies.extend(marker_ring_around_primary(
        &jupiter,
        "s25-jovian-tile",
        96,
        4.0e9,
    ));
    bodies.extend(marker_ring_around_primary(
        &earth,
        "s25-local-tile",
        96,
        1.2e9,
    ));

    seed_from_descriptor(s25_tile_swarm_descriptor(), 172_800.0, bodies)
}

fn descriptor<const N: usize>(
    scenario_id: &str,
    title: &str,
    description: &str,
    tags: [&str; N],
    default_focus_body_id: Option<&str>,
    default_observer_mode: ObserverMode,
    start_paused: bool,
) -> ScenarioPackDescriptor {
    ScenarioPackDescriptor {
        scenario_id: scenario_id.to_owned(),
        title: title.to_owned(),
        description: description.to_owned(),
        tags: tags.into_iter().map(str::to_owned).collect(),
        default_focus_body_id: default_focus_body_id.map(str::to_owned),
        default_observer_mode,
        start_paused,
    }
}

fn seed_from_descriptor(
    descriptor: ScenarioPackDescriptor,
    sim_seconds_per_real_second: f64,
    bodies: Vec<CanonicalBodySpec>,
) -> ScenarioPackSeed {
    ScenarioPackSeed {
        scenario_id: descriptor.scenario_id,
        title: descriptor.title,
        description: descriptor.description,
        tags: descriptor.tags,
        default_focus_body_id: descriptor.default_focus_body_id,
        default_observer_mode: descriptor.default_observer_mode,
        start_paused: descriptor.start_paused,
        sim_seconds_per_real_second,
        bodies,
    }
}

fn select_bodies_by_id(bodies: &[CanonicalBodySpec], keep_ids: &[&str]) -> Vec<CanonicalBodySpec> {
    let keep: BTreeSet<&str> = keep_ids.iter().copied().collect();
    bodies
        .iter()
        .filter(|body| keep.contains(body.body_id.as_str()))
        .cloned()
        .collect()
}

fn body_by_id(bodies: &[CanonicalBodySpec], body_id: &str) -> CanonicalBodySpec {
    bodies
        .iter()
        .find(|body| body.body_id == body_id)
        .cloned()
        .expect("scenario pack body id should exist in its seed")
}

fn marker_ring_around_primary(
    primary: &CanonicalBodySpec,
    id_prefix: &str,
    count: usize,
    radius_m: f64,
) -> Vec<CanonicalBodySpec> {
    (0..count)
        .map(|index| {
            orbiting_body(
                primary,
                &format!("{}-{index:02}", id_prefix),
                BodyClass::Tracer,
                0.0,
                2_500.0,
                radius_m * (0.82 + 0.02 * (index % 7) as f64),
                (index as f64) * (360.0 / count as f64),
                -9.0 + (index % 5) as f64 * 4.5,
            )
        })
        .collect()
}

fn orbiting_body(
    primary: &CanonicalBodySpec,
    body_id: &str,
    body_class: BodyClass,
    mass_kg: f64,
    radius_m: f64,
    orbital_radius_m: f64,
    phase_degrees: f64,
    inclination_degrees: f64,
) -> CanonicalBodySpec {
    let phase = phase_degrees.to_radians();
    let inclination = inclination_degrees.to_radians();
    let speed_mps =
        (GRAVITATIONAL_CONSTANT_M3_PER_KG_S2 * primary.mass_kg / orbital_radius_m).sqrt();
    let offset = Vector3d {
        x: orbital_radius_m * phase.cos(),
        y: orbital_radius_m * phase.sin() * inclination.cos(),
        z: orbital_radius_m * phase.sin() * inclination.sin(),
    };
    let velocity = Vector3d {
        x: -speed_mps * phase.sin(),
        y: speed_mps * phase.cos() * inclination.cos(),
        z: speed_mps * phase.cos() * inclination.sin(),
    };
    relative_body(
        primary, body_id, body_class, mass_kg, radius_m, offset, velocity,
    )
}

fn relative_body(
    primary: &CanonicalBodySpec,
    body_id: &str,
    body_class: BodyClass,
    mass_kg: f64,
    radius_m: f64,
    offset_m: Vector3d,
    velocity_mps: Vector3d,
) -> CanonicalBodySpec {
    CanonicalBodySpec {
        body_id: body_id.to_owned(),
        body_class,
        mass_kg,
        radius_m,
        position_m: Vector3d {
            x: primary.position_m.x + offset_m.x,
            y: primary.position_m.y + offset_m.y,
            z: primary.position_m.z + offset_m.z,
        },
        velocity_mps: Vector3d {
            x: primary.velocity_mps.x + velocity_mps.x,
            y: primary.velocity_mps.y + velocity_mps.y,
            z: primary.velocity_mps.z + velocity_mps.z,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scenario_pack_catalog_ids_are_unique() {
        let catalog = scenario_pack_catalog();
        let ids: BTreeSet<_> = catalog
            .iter()
            .map(|pack| pack.scenario_id.as_str())
            .collect();

        assert_eq!(ids.len(), catalog.len());
        assert!(ids.contains(DEFAULT_SCENARIO_PACK_ID));
    }

    #[test]
    fn scenario_pack_seeds_are_deterministic_and_focusable() {
        for descriptor in scenario_pack_catalog() {
            let first = scenario_pack_seed(&descriptor.scenario_id)
                .expect("catalog scenario should have a seed");
            let second = scenario_pack_seed(&descriptor.scenario_id)
                .expect("catalog scenario should have a seed");

            assert_eq!(first, second);
            assert!(!first.bodies.is_empty());
            if let Some(focus_body_id) = &first.default_focus_body_id {
                assert!(
                    first
                        .bodies
                        .iter()
                        .any(|body| body.body_id == *focus_body_id),
                    "focus body {focus_body_id} missing from {}",
                    first.scenario_id
                );
            }
        }
    }

    #[test]
    fn showcase_packs_are_smaller_than_full_canonical_pack() {
        let canonical =
            scenario_pack_seed(DEFAULT_SCENARIO_PACK_ID).expect("default scenario should exist");
        let inner =
            scenario_pack_seed("showcase.inner-system").expect("inner scenario should exist");
        let jupiter =
            scenario_pack_seed("showcase.jupiter-system").expect("jupiter scenario should exist");

        assert!(inner.bodies.len() < canonical.bodies.len());
        assert!(jupiter.bodies.iter().any(|body| body.body_id == "io"));
        assert!(jupiter.bodies.iter().any(|body| body.body_id == "callisto"));
    }

    #[test]
    fn s25_tile_swarm_pack_exercises_parallel_tile_scheduler_shape() {
        const ACTIVE_ARM64_TILE_SIZE: usize = 32;

        let seed =
            scenario_pack_seed("stress.s25-tile-swarm").expect("s25 tile swarm should exist");

        assert_eq!(seed.bodies.len(), 749);
        assert_eq!(seed.bodies.len().div_ceil(ACTIVE_ARM64_TILE_SIZE), 24);
        assert_eq!(seed.default_observer_mode, ObserverMode::SystemFrame);
        assert_eq!(seed.default_focus_body_id.as_deref(), Some("sun"));
        assert!(seed
            .bodies
            .iter()
            .any(|body| body.body_id == "s25-inner-tile-191"));
        assert!(seed
            .bodies
            .iter()
            .any(|body| body.body_id == "s25-jovian-tile-95"));
        assert!(seed
            .bodies
            .iter()
            .any(|body| body.body_id == "s25-local-tile-95"));
    }

    #[test]
    fn unknown_scenario_pack_does_not_fall_back_to_default() {
        assert!(scenario_pack_seed("missing-pack").is_none());
        assert!(scenario_pack_seed("").is_some());
    }
}
