use std::f64::consts::PI;

use solarlab_domain::{BodyClass, Vector3d};

pub const CANONICAL_STARTUP_CURATED_SMALL_BODY_COUNT: usize = 14;
pub const CANONICAL_STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT: usize = 240;
pub const CANONICAL_STARTUP_SYNTHETIC_OORT_CLOUD_COUNT: usize = 96;

const STARTUP_ASTRONOMICAL_UNIT_M: f64 = 1.495_978_707e11;
const STARTUP_SEED_JULIAN_DATE_TDB: f64 = 2_451_545.0;
const STARTUP_DAY_SECONDS: f64 = 86_400.0;
const STARTUP_GRAVITATIONAL_CONSTANT_M3_PER_KG_S2: f64 = 6.67430e-11;
const STARTUP_MOON_MASS_KG: f64 = 7.342e22;
const STARTUP_MOON_RADIUS_M: f64 = 1.7374e6;
const STARTUP_SYNTHETIC_ASTEROID_BELT_SEED: u64 = 42;
const STARTUP_SYNTHETIC_OORT_CLOUD_SEED: u64 = 43;

#[derive(Clone, Debug, PartialEq)]
pub struct CanonicalBodySpec {
    pub body_id: String,
    pub body_class: BodyClass,
    pub mass_kg: f64,
    pub radius_m: f64,
    pub position_m: Vector3d,
    pub velocity_mps: Vector3d,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CanonicalStartupSeed {
    pub bodies: Vec<CanonicalBodySpec>,
    pub curated_small_body_count: usize,
    pub synthetic_asteroid_belt_count: usize,
    pub synthetic_oort_cloud_count: usize,
}

#[must_use]
pub fn canonical_startup_seed() -> CanonicalStartupSeed {
    let sun = body_state(
        "sun",
        BodyClass::Star,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.988_47e30,
        6.9634e8,
    );
    let mercury = body_state(
        "mercury",
        BodyClass::Planet,
        -1.946_172_635_585_372e10,
        -5.992_796_777_348_039e10,
        -2.999_277_267_983_142e10,
        3.699_499_185_727_919e4,
        -8_529.675_283_382_268,
        -8_393.121_143_467_224,
        3.3011e23,
        2.4397e6,
    );
    let venus = body_state(
        "venus",
        BodyClass::Planet,
        -1.074_564_940_521_906e11,
        -6.922_528_774_882_654e9,
        3.686_187_045_620_657e9,
        1_381.906_029_263_447,
        -32_017.818_431_682_73,
        -14_491.835_473_268_0,
        4.8675e24,
        6.0518e6,
    );
    let earth = body_state(
        "earth",
        BodyClass::Planet,
        -2.649_903_367_743_05e10,
        1.327_574_173_383_451e11,
        5.755_671_847_054_072e10,
        -2.979_426_007_043_741e4,
        -5_018.052_308_799_903,
        -2_175.393_802_830_554,
        5.97237e24,
        6.3710e6,
    );
    let moon = moon_startup_body(&earth);
    let mars = body_state(
        "mars",
        BodyClass::Planet,
        2.080_481_406_418_42e11,
        2.096_191_735_388_105e8,
        -5.529_162_313_155_326e9,
        1_162.672_403_766_088,
        23_918.409_699_116_61,
        10_939.171_916_766_48,
        6.4171e23,
        3.3895e6,
    );
    let jupiter = body_state(
        "jupiter",
        BodyClass::Planet,
        5.985_676_246_570_645e11,
        4.093_863_059_841_62e11,
        1.608_943_268_775_687e11,
        -7_909.860_292_172_008,
        10_183.574_082_354_88,
        4_557.755_393_988_428,
        1.8982e27,
        6.9911e7,
    );
    let saturn = body_state(
        "saturn",
        BodyClass::Planet,
        9.583_853_589_157_217e11,
        9.237_154_712_422_728e11,
        3.403_008_584_583_76e11,
        -7_431.212_958_764_64,
        6_110.152_327_010_504,
        2_842.799_239_481_524,
        5.6834e26,
        5.8232e7,
    );
    let uranus = body_state(
        "uranus",
        BodyClass::Planet,
        2.158_974_819_528_798e12,
        -1.870_911_063_386_387e12,
        -8.499_688_608_118_601e11,
        4_637.272_105_685_132,
        4_262.811_704_355_634,
        1_801.372_818_270_055,
        8.6810e25,
        2.5362e7,
    );
    let neptune = body_state(
        "neptune",
        BodyClass::Planet,
        2.515_046_471_487_719e12,
        -3.437_774_106_197_624e12,
        -1.469_713_518_152_847e12,
        4_465.275_177_950_522,
        2_888.286_551_585_958,
        1_071.024_500_381_687,
        1.02413e26,
        2.4622e7,
    );
    let pluto = body_state(
        "pluto",
        BodyClass::DwarfPlanet,
        -1.477_330_922_306_794e12,
        -4.185_578_139_004_337e12,
        -8.607_382_312_063_003e11,
        5_259.850_276_851_352,
        -1_939.761_452_556_408,
        -2_204.049_388_416_424,
        1.303e22,
        1.1883e6,
    );
    let haumea = spawn_orbiting_body_around_primary(
        "haumea",
        BodyClass::DwarfPlanet,
        &sun,
        4.006e21,
        7.16e5,
        orbital_elements(43.13, 0.191, 28.19, 122.0, 240.0, 80.0),
    );
    let makemake = spawn_orbiting_body_around_primary(
        "makemake",
        BodyClass::DwarfPlanet,
        &sun,
        3.1e21,
        7.15e5,
        orbital_elements(45.79, 0.159, 28.96, 79.6, 294.0, 170.0),
    );
    let eris = spawn_orbiting_body_around_primary(
        "eris",
        BodyClass::DwarfPlanet,
        &sun,
        1.6466e22,
        1.163e6,
        orbital_elements(67.78, 0.44, 44.04, 35.95, 151.4, 260.0),
    );
    let ceres = body_state(
        "ceres",
        BodyClass::DwarfPlanet,
        -3.559_423_585_024_965e11,
        8.163_123_942_918_420e10,
        1.108_857_536_222_865e11,
        -6_205.936_548_273_125,
        -17_046.568_817_332_89,
        -6_760.549_102_192_67,
        9.3835e20,
        4.731e5,
    );

    let curated_small_bodies = curated_small_body_seed(&sun);
    let synthetic_asteroid_belt = synthetic_asteroid_belt_seed(
        &sun,
        CANONICAL_STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT,
        STARTUP_SYNTHETIC_ASTEROID_BELT_SEED,
    );
    let synthetic_oort_cloud = synthetic_oort_cloud_seed(
        &sun,
        CANONICAL_STARTUP_SYNTHETIC_OORT_CLOUD_COUNT,
        STARTUP_SYNTHETIC_OORT_CLOUD_SEED,
    );

    let mut bodies = Vec::with_capacity(
        15 + CANONICAL_STARTUP_CURATED_SMALL_BODY_COUNT
            + CANONICAL_STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT
            + CANONICAL_STARTUP_SYNTHETIC_OORT_CLOUD_COUNT,
    );
    bodies.extend([
        sun, mercury, venus, earth, moon, mars, jupiter, saturn, uranus, neptune, pluto, haumea,
        makemake, eris, ceres,
    ]);
    bodies.extend(curated_small_bodies);
    bodies.extend(synthetic_asteroid_belt);
    bodies.extend(synthetic_oort_cloud);

    CanonicalStartupSeed {
        bodies,
        curated_small_body_count: CANONICAL_STARTUP_CURATED_SMALL_BODY_COUNT,
        synthetic_asteroid_belt_count: CANONICAL_STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT,
        synthetic_oort_cloud_count: CANONICAL_STARTUP_SYNTHETIC_OORT_CLOUD_COUNT,
    }
}

fn moon_startup_body(earth: &CanonicalBodySpec) -> CanonicalBodySpec {
    let moon_state = state_vector_around_primary_at_epoch(
        earth.mass_kg,
        STARTUP_MOON_MASS_KG,
        OrbitAtEpoch {
            epoch_jd_tdb: STARTUP_SEED_JULIAN_DATE_TDB,
            semi_major_axis_m: 3.844e8,
            eccentricity: 0.0549,
            inclination_rad: 5.145_f64.to_radians(),
            longitude_of_ascending_node_rad: 125.08_f64.to_radians(),
            argument_of_periapsis_rad: 318.15_f64.to_radians(),
            mean_anomaly_at_epoch_rad: 135.27_f64.to_radians(),
        },
        STARTUP_SEED_JULIAN_DATE_TDB,
        STARTUP_GRAVITATIONAL_CONSTANT_M3_PER_KG_S2,
    );

    body_state(
        "moon",
        BodyClass::Moon,
        earth.position_m.x + moon_state.position_m.x,
        earth.position_m.y + moon_state.position_m.y,
        earth.position_m.z + moon_state.position_m.z,
        earth.velocity_mps.x + moon_state.velocity_mps.x,
        earth.velocity_mps.y + moon_state.velocity_mps.y,
        earth.velocity_mps.z + moon_state.velocity_mps.z,
        STARTUP_MOON_MASS_KG,
        STARTUP_MOON_RADIUS_M,
    )
}

fn curated_small_body_seed(primary: &CanonicalBodySpec) -> Vec<CanonicalBodySpec> {
    vec![
        spawn_orbiting_body_around_primary(
            "vesta",
            BodyClass::SmallBody,
            primary,
            2.59076e20,
            2.626e5,
            orbital_elements(2.361, 0.089, 7.14, 103.8, 150.9, 40.0),
        ),
        spawn_orbiting_body_around_primary(
            "pallas",
            BodyClass::SmallBody,
            primary,
            2.14e20,
            2.56e5,
            orbital_elements(2.773, 0.231, 34.84, 173.1, 310.2, 220.0),
        ),
        spawn_orbiting_body_around_primary(
            "hygiea",
            BodyClass::SmallBody,
            primary,
            8.32e19,
            2.17e5,
            orbital_elements(3.141, 0.117, 3.83, 283.2, 313.4, 120.0),
        ),
        spawn_orbiting_body_around_primary(
            "psyche",
            BodyClass::SmallBody,
            primary,
            2.3e19,
            1.13e5,
            orbital_elements(2.923, 0.140, 3.10, 150.0, 228.0, 280.0),
        ),
        spawn_orbiting_body_around_primary(
            "eros",
            BodyClass::SmallBody,
            primary,
            6.687e15,
            8_420.0,
            orbital_elements(1.458, 0.223, 10.83, 304.4, 178.7, 60.0),
        ),
        spawn_orbiting_body_around_primary(
            "bennu",
            BodyClass::SmallBody,
            primary,
            7.329e10,
            245.0,
            orbital_elements(1.1264, 0.2037, 6.03, 2.06, 66.22, 300.0),
        ),
        spawn_orbiting_body_around_primary(
            "ryugu",
            BodyClass::SmallBody,
            primary,
            4.5e11,
            448.0,
            orbital_elements(1.1896, 0.1902, 5.88, 251.45, 211.61, 170.0),
        ),
        spawn_orbiting_body_around_primary(
            "itokawa",
            BodyClass::SmallBody,
            primary,
            3.51e10,
            165.0,
            orbital_elements(1.324, 0.280, 1.62, 69.1, 162.8, 25.0),
        ),
        spawn_orbiting_body_around_primary(
            "apophis",
            BodyClass::SmallBody,
            primary,
            6.1e10,
            185.0,
            orbital_elements(0.9224, 0.1912, 3.34, 204.4, 126.4, 320.0),
        ),
        spawn_orbiting_body_around_primary(
            "didymos",
            BodyClass::SmallBody,
            primary,
            5.24e11,
            390.0,
            orbital_elements(1.644, 0.384, 3.41, 73.2, 319.6, 80.0),
        ),
        spawn_orbiting_body_around_primary(
            "halley",
            BodyClass::SmallBody,
            primary,
            2.2e14,
            5_500.0,
            orbital_elements(17.834, 0.967, 162.26, 58.42, 111.33, 38.0),
        ),
        spawn_orbiting_body_around_primary(
            "encke",
            BodyClass::SmallBody,
            primary,
            3.5e13,
            2_400.0,
            orbital_elements(2.215, 0.850, 11.78, 334.6, 186.5, 140.0),
        ),
        spawn_orbiting_body_around_primary(
            "churyumov-gerasimenko",
            BodyClass::SmallBody,
            primary,
            9.98e12,
            2_000.0,
            orbital_elements(3.463, 0.641, 7.04, 50.17, 12.78, 90.0),
        ),
        spawn_orbiting_body_around_primary(
            "wild-2",
            BodyClass::SmallBody,
            primary,
            2.3e13,
            2_000.0,
            orbital_elements(3.447, 0.538, 3.24, 136.1, 41.0, 260.0),
        ),
    ]
}

fn synthetic_asteroid_belt_seed(
    primary: &CanonicalBodySpec,
    count: usize,
    seed: u64,
) -> Vec<CanonicalBodySpec> {
    let mut rng = DeterministicRng::new(seed);
    let mut out = Vec::with_capacity(count);
    for index in 0..count {
        out.push(spawn_orbiting_body_around_primary(
            &format!("belt-{index}"),
            BodyClass::Tracer,
            primary,
            0.0,
            rng.next_range_f64(500.0, 50_000.0),
            orbital_elements(
                rng.next_range_f64(2.1, 3.3),
                rng.next_range_f64(0.0, 0.18),
                rng.next_range_f64(0.0, 18.0),
                rng.next_range_f64(0.0, 360.0),
                rng.next_range_f64(0.0, 360.0),
                rng.next_range_f64(0.0, 360.0),
            ),
        ));
    }
    out
}

fn synthetic_oort_cloud_seed(
    primary: &CanonicalBodySpec,
    count: usize,
    seed: u64,
) -> Vec<CanonicalBodySpec> {
    let mut rng = DeterministicRng::new(seed);
    let mut out = Vec::with_capacity(count);
    for index in 0..count {
        let log_semi_major_axis_au = rng.next_range_f64(3.3, 5.0);
        let semi_major_axis_au = 10.0_f64.powf(log_semi_major_axis_au);
        let inclination_deg = rng.next_range_f64(-1.0, 1.0).acos().to_degrees();
        out.push(spawn_orbiting_body_around_primary(
            &format!("oort-{index}"),
            BodyClass::Tracer,
            primary,
            0.0,
            rng.next_range_f64(1_000.0, 20_000.0),
            orbital_elements(
                semi_major_axis_au,
                rng.next_range_f64(0.85, 0.999),
                inclination_deg,
                rng.next_range_f64(0.0, 360.0),
                rng.next_range_f64(0.0, 360.0),
                rng.next_range_f64(0.0, 360.0),
            ),
        ));
    }
    out
}

fn spawn_orbiting_body_around_primary(
    body_id: &str,
    body_class: BodyClass,
    primary: &CanonicalBodySpec,
    mass_kg: f64,
    radius_m: f64,
    elements: OrbitalElements,
) -> CanonicalBodySpec {
    let state = state_vector_around_primary(
        primary.mass_kg,
        mass_kg,
        elements,
        STARTUP_GRAVITATIONAL_CONSTANT_M3_PER_KG_S2,
    );

    body_state(
        body_id,
        body_class,
        primary.position_m.x + state.position_m.x,
        primary.position_m.y + state.position_m.y,
        primary.position_m.z + state.position_m.z,
        primary.velocity_mps.x + state.velocity_mps.x,
        primary.velocity_mps.y + state.velocity_mps.y,
        primary.velocity_mps.z + state.velocity_mps.z,
        mass_kg,
        radius_m,
    )
}

fn body_state(
    body_id: &str,
    body_class: BodyClass,
    position_x: f64,
    position_y: f64,
    position_z: f64,
    velocity_x: f64,
    velocity_y: f64,
    velocity_z: f64,
    mass_kg: f64,
    radius_m: f64,
) -> CanonicalBodySpec {
    CanonicalBodySpec {
        body_id: body_id.to_owned(),
        body_class,
        mass_kg,
        radius_m,
        position_m: Vector3d {
            x: position_x,
            y: position_y,
            z: position_z,
        },
        velocity_mps: Vector3d {
            x: velocity_x,
            y: velocity_y,
            z: velocity_z,
        },
    }
}

fn orbital_elements(
    semi_major_axis_au: f64,
    eccentricity: f64,
    inclination_deg: f64,
    ascending_node_deg: f64,
    periapsis_deg: f64,
    true_anomaly_deg: f64,
) -> OrbitalElements {
    OrbitalElements {
        semi_major_axis_m: semi_major_axis_au * STARTUP_ASTRONOMICAL_UNIT_M,
        eccentricity,
        inclination_rad: inclination_deg.to_radians(),
        longitude_of_ascending_node_rad: ascending_node_deg.to_radians(),
        argument_of_periapsis_rad: periapsis_deg.to_radians(),
        true_anomaly_rad: true_anomaly_deg.to_radians(),
    }
}

fn state_vector_around_primary_at_epoch(
    primary_mass_kg: f64,
    body_mass_kg: f64,
    orbit: OrbitAtEpoch,
    target_julian_date_tdb: f64,
    gravitational_constant: f64,
) -> StateVector {
    let mu = gravitational_constant * (primary_mass_kg + body_mass_kg);
    let mean_motion_rad_per_second =
        (mu / (orbit.semi_major_axis_m * orbit.semi_major_axis_m * orbit.semi_major_axis_m)).sqrt();
    let delta_seconds = (target_julian_date_tdb - orbit.epoch_jd_tdb) * STARTUP_DAY_SECONDS;
    let mean_anomaly = normalize_radians(
        orbit.mean_anomaly_at_epoch_rad + mean_motion_rad_per_second * delta_seconds,
    );
    let eccentric_anomaly = solve_kepler_equation(mean_anomaly, orbit.eccentricity);
    let true_anomaly = 2.0
        * ((1.0 + orbit.eccentricity).sqrt() * (eccentric_anomaly / 2.0).sin())
            .atan2((1.0 - orbit.eccentricity).sqrt() * (eccentric_anomaly / 2.0).cos());

    state_vector_around_primary(
        primary_mass_kg,
        body_mass_kg,
        OrbitalElements {
            semi_major_axis_m: orbit.semi_major_axis_m,
            eccentricity: orbit.eccentricity,
            inclination_rad: orbit.inclination_rad,
            longitude_of_ascending_node_rad: orbit.longitude_of_ascending_node_rad,
            argument_of_periapsis_rad: orbit.argument_of_periapsis_rad,
            true_anomaly_rad: normalize_radians(true_anomaly),
        },
        gravitational_constant,
    )
}

fn state_vector_around_primary(
    primary_mass_kg: f64,
    body_mass_kg: f64,
    elements: OrbitalElements,
    gravitational_constant: f64,
) -> StateVector {
    let mu = gravitational_constant * (primary_mass_kg + body_mass_kg);
    let p = elements.semi_major_axis_m * (1.0 - elements.eccentricity * elements.eccentricity);
    let cos_nu = elements.true_anomaly_rad.cos();
    let sin_nu = elements.true_anomaly_rad.sin();
    let radius = p / (1.0 + elements.eccentricity * cos_nu);
    let speed_factor = (mu / p).sqrt();
    let rotation = Rotation::from(elements);

    StateVector {
        position_m: Vector3d {
            x: rotation.transform_x(radius * cos_nu, radius * sin_nu),
            y: rotation.transform_y(radius * cos_nu, radius * sin_nu),
            z: rotation.transform_z(radius * cos_nu, radius * sin_nu),
        },
        velocity_mps: Vector3d {
            x: rotation.transform_x(
                -speed_factor * sin_nu,
                speed_factor * (elements.eccentricity + cos_nu),
            ),
            y: rotation.transform_y(
                -speed_factor * sin_nu,
                speed_factor * (elements.eccentricity + cos_nu),
            ),
            z: rotation.transform_z(
                -speed_factor * sin_nu,
                speed_factor * (elements.eccentricity + cos_nu),
            ),
        },
    }
}

fn solve_kepler_equation(mean_anomaly_rad: f64, eccentricity: f64) -> f64 {
    let mut eccentric_anomaly = if eccentricity < 0.8 {
        mean_anomaly_rad
    } else {
        PI
    };
    for _ in 0..24 {
        let function_value =
            eccentric_anomaly - eccentricity * eccentric_anomaly.sin() - mean_anomaly_rad;
        let derivative = 1.0 - eccentricity * eccentric_anomaly.cos();
        let delta = function_value / derivative;
        eccentric_anomaly -= delta;
        if delta.abs() <= 1e-14 {
            return eccentric_anomaly;
        }
    }
    eccentric_anomaly
}

fn normalize_radians(angle: f64) -> f64 {
    let wrapped = (angle + PI).rem_euclid(2.0 * PI);
    wrapped - PI
}

#[derive(Clone, Copy)]
struct OrbitalElements {
    semi_major_axis_m: f64,
    eccentricity: f64,
    inclination_rad: f64,
    longitude_of_ascending_node_rad: f64,
    argument_of_periapsis_rad: f64,
    true_anomaly_rad: f64,
}

#[derive(Clone, Copy)]
struct OrbitAtEpoch {
    epoch_jd_tdb: f64,
    semi_major_axis_m: f64,
    eccentricity: f64,
    inclination_rad: f64,
    longitude_of_ascending_node_rad: f64,
    argument_of_periapsis_rad: f64,
    mean_anomaly_at_epoch_rad: f64,
}

#[derive(Clone, Copy)]
struct StateVector {
    position_m: Vector3d,
    velocity_mps: Vector3d,
}

#[derive(Clone, Copy)]
struct Rotation {
    r11: f64,
    r12: f64,
    r21: f64,
    r22: f64,
    r31: f64,
    r32: f64,
}

impl Rotation {
    fn from(elements: OrbitalElements) -> Self {
        let cos_omega = elements.longitude_of_ascending_node_rad.cos();
        let sin_omega = elements.longitude_of_ascending_node_rad.sin();
        let cos_i = elements.inclination_rad.cos();
        let sin_i = elements.inclination_rad.sin();
        let cos_w = elements.argument_of_periapsis_rad.cos();
        let sin_w = elements.argument_of_periapsis_rad.sin();

        Self {
            r11: cos_omega * cos_w - sin_omega * sin_w * cos_i,
            r12: -cos_omega * sin_w - sin_omega * cos_w * cos_i,
            r21: sin_omega * cos_w + cos_omega * sin_w * cos_i,
            r22: -sin_omega * sin_w + cos_omega * cos_w * cos_i,
            r31: sin_w * sin_i,
            r32: cos_w * sin_i,
        }
    }

    fn transform_x(&self, x: f64, y: f64) -> f64 {
        self.r11 * x + self.r12 * y
    }

    fn transform_y(&self, x: f64, y: f64) -> f64 {
        self.r21 * x + self.r22 * y
    }

    fn transform_z(&self, x: f64, y: f64) -> f64 {
        self.r31 * x + self.r32 * y
    }
}

#[derive(Clone, Copy)]
struct DeterministicRng {
    state: u64,
}

impl DeterministicRng {
    fn new(seed: u64) -> Self {
        let state = if seed == 0 {
            0x9E37_79B9_7F4A_7C15
        } else {
            seed
        };
        Self { state }
    }

    fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        x
    }

    fn next_unit_f64(&mut self) -> f64 {
        const SCALE: f64 = 1.0 / ((1u64 << 53) as f64);
        ((self.next_u64() >> 11) as f64) * SCALE
    }

    fn next_range_f64(&mut self, min: f64, max: f64) -> f64 {
        min + (max - min) * self.next_unit_f64()
    }
}

#[cfg(test)]
mod tests {
    use super::canonical_startup_seed;

    #[test]
    fn startup_seed_contains_expected_counts_and_ids() {
        let seed = canonical_startup_seed();
        assert_eq!(seed.curated_small_body_count, 14);
        assert_eq!(seed.synthetic_asteroid_belt_count, 240);
        assert_eq!(seed.synthetic_oort_cloud_count, 96);
        assert_eq!(seed.bodies.len(), 365);
        assert!(seed.bodies.iter().any(|body| body.body_id == "sun"));
        assert!(seed.bodies.iter().any(|body| body.body_id == "moon"));
        assert!(seed.bodies.iter().any(|body| body.body_id == "halley"));
        assert!(seed.bodies.iter().any(|body| body.body_id == "belt-239"));
        assert!(seed.bodies.iter().any(|body| body.body_id == "oort-95"));
    }

    #[test]
    fn startup_seed_earth_moon_kinematics_are_physically_plausible() {
        let seed = canonical_startup_seed();
        let earth = seed
            .bodies
            .iter()
            .find(|body| body.body_id == "earth")
            .expect("earth should exist");
        let moon = seed
            .bodies
            .iter()
            .find(|body| body.body_id == "moon")
            .expect("moon should exist");

        let dx = earth.position_m.x - moon.position_m.x;
        let dy = earth.position_m.y - moon.position_m.y;
        let dz = earth.position_m.z - moon.position_m.z;
        let distance_m = (dx * dx + dy * dy + dz * dz).sqrt();
        assert!(
            (3.0e8..4.5e8).contains(&distance_m),
            "earth-moon distance out of expected range: {distance_m}"
        );

        let dvx = earth.velocity_mps.x - moon.velocity_mps.x;
        let dvy = earth.velocity_mps.y - moon.velocity_mps.y;
        let dvz = earth.velocity_mps.z - moon.velocity_mps.z;
        let relative_speed_mps = (dvx * dvx + dvy * dvy + dvz * dvz).sqrt();
        assert!(
            (500.0..1_500.0).contains(&relative_speed_mps),
            "earth-moon relative speed out of expected range: {relative_speed_mps}"
        );
    }
}
