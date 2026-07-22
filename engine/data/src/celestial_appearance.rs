use solarlab_domain::{
    AppearanceProvenance, AtmosphereAppearance, BodyClass, BodyOrientation,
    CelestialAppearanceFacts, CelestialMaterialFamily, CometAppearanceInputs,
    RingSystemAppearance, Vector3d,
};

/// Returns the canonical, renderer-neutral appearance facts for a body.
///
/// The named canonical bodies use deliberately curated visual or physical
/// guides. Unknown and user-created bodies receive deterministic class-based
/// defaults, so rendering never needs to invent authoritative world facts.
#[must_use]
pub fn canonical_celestial_appearance(
    body_id: &str,
    body_class: BodyClass,
    radius_m: f64,
) -> CelestialAppearanceFacts {
    let radius_m = radius_m.max(0.0);
    let mut facts = class_default(body_class, radius_m);

    match body_id {
        "sun" => {
            facts.material = CelestialMaterialFamily::StellarPhotosphere;
            facts.provenance = AppearanceProvenance::CuratedPhysicalGuide;
            facts.orientation = orientation_from_axial_tilt_degrees(7.25);
        }
        "mercury" => curated_planet(&mut facts, CelestialMaterialFamily::Rocky, 0.034),
        "venus" => {
            curated_planet(&mut facts, CelestialMaterialFamily::Terrestrial, 177.36);
            facts.atmosphere = Some(atmosphere(radius_m, 250_000.0, 1.6));
        }
        "earth" => {
            curated_planet(&mut facts, CelestialMaterialFamily::Terrestrial, 23.439_281);
            facts.atmosphere = Some(atmosphere(radius_m, 100_000.0, 1.0));
        }
        "moon" => {
            facts.material = CelestialMaterialFamily::Lunar;
            facts.provenance = AppearanceProvenance::CuratedVisualGuide;
            facts.orientation = orientation_from_axial_tilt_degrees(6.68);
        }
        "mars" => {
            curated_planet(&mut facts, CelestialMaterialFamily::Rocky, 25.19);
            facts.atmosphere = Some(atmosphere(radius_m, 80_000.0, 0.08));
        }
        "jupiter" => {
            curated_planet(&mut facts, CelestialMaterialFamily::GasGiant, 3.13);
            facts.atmosphere = Some(atmosphere(radius_m, radius_m * 0.025, 1.15));
        }
        "saturn" => {
            curated_planet(&mut facts, CelestialMaterialFamily::GasGiant, 26.73);
            facts.atmosphere = Some(atmosphere(radius_m, radius_m * 0.025, 1.05));
            facts.ring_system = Some(RingSystemAppearance {
                inner_radius_m: 74_658_000.0,
                outer_radius_m: 140_220_000.0,
                plane_normal_ws: facts.orientation.north_pole_ws,
                optical_depth: 0.68,
            });
        }
        "uranus" => {
            curated_planet(&mut facts, CelestialMaterialFamily::IceGiant, 97.77);
            facts.atmosphere = Some(atmosphere(radius_m, radius_m * 0.025, 0.9));
        }
        "neptune" => {
            curated_planet(&mut facts, CelestialMaterialFamily::IceGiant, 28.32);
            facts.atmosphere = Some(atmosphere(radius_m, radius_m * 0.025, 0.95));
        }
        "pluto" | "haumea" | "makemake" | "eris" | "ceres" => {
            facts.material = CelestialMaterialFamily::Icy;
            facts.provenance = AppearanceProvenance::CuratedVisualGuide;
        }
        _ => {}
    }

    facts
}

fn class_default(body_class: BodyClass, radius_m: f64) -> CelestialAppearanceFacts {
    let material = match body_class {
        BodyClass::Star => CelestialMaterialFamily::StellarPhotosphere,
        BodyClass::Planet => CelestialMaterialFamily::Terrestrial,
        BodyClass::DwarfPlanet => CelestialMaterialFamily::Icy,
        BodyClass::Moon => CelestialMaterialFamily::Lunar,
        BodyClass::SmallBody => CelestialMaterialFamily::Asteroid,
        BodyClass::Tracer | BodyClass::Custom => CelestialMaterialFamily::Neutral,
        BodyClass::Spacecraft => CelestialMaterialFamily::Spacecraft,
        BodyClass::Comet => CelestialMaterialFamily::CometNucleus,
    };
    let comet = (body_class == BodyClass::Comet).then(|| CometAppearanceInputs {
        nucleus_radius_m: radius_m,
        coma_radius_m: (radius_m * 12.0).max(100_000.0),
        dust_tail_length_m: (radius_m * 60_000.0).max(300_000_000.0),
        ion_tail_length_m: (radius_m * 90_000.0).max(500_000_000.0),
    });

    CelestialAppearanceFacts {
        material,
        provenance: AppearanceProvenance::DerivedClassDefault,
        orientation: BodyOrientation {
            north_pole_ws: Vector3d {
                x: 0.0,
                y: 1.0,
                z: 0.0,
            },
            reference_meridian_radians: 0.0,
        },
        ring_system: None,
        atmosphere: None,
        comet,
    }
}

fn curated_planet(
    facts: &mut CelestialAppearanceFacts,
    material: CelestialMaterialFamily,
    axial_tilt_degrees: f64,
) {
    facts.material = material;
    facts.provenance = AppearanceProvenance::CuratedVisualGuide;
    facts.orientation = orientation_from_axial_tilt_degrees(axial_tilt_degrees);
}

fn atmosphere(radius_m: f64, height_m: f64, optical_density: f64) -> AtmosphereAppearance {
    AtmosphereAppearance {
        outer_radius_m: radius_m + height_m,
        optical_density,
    }
}

fn orientation_from_axial_tilt_degrees(axial_tilt_degrees: f64) -> BodyOrientation {
    let radians = axial_tilt_degrees.to_radians();
    BodyOrientation {
        north_pole_ws: Vector3d {
            x: radians.sin(),
            y: radians.cos(),
            z: 0.0,
        },
        reference_meridian_radians: 0.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saturn_has_a_complete_curated_ring_contract() {
        let appearance = canonical_celestial_appearance("saturn", BodyClass::Planet, 58_232_000.0);

        assert_eq!(
            appearance,
            CelestialAppearanceFacts {
                material: CelestialMaterialFamily::GasGiant,
                provenance: AppearanceProvenance::CuratedVisualGuide,
                orientation: orientation_from_axial_tilt_degrees(26.73),
                ring_system: Some(RingSystemAppearance {
                    inner_radius_m: 74_658_000.0,
                    outer_radius_m: 140_220_000.0,
                    plane_normal_ws: orientation_from_axial_tilt_degrees(26.73).north_pole_ws,
                    optical_depth: 0.68,
                }),
                atmosphere: Some(AtmosphereAppearance {
                    outer_radius_m: 58_232_000.0 + 58_232_000.0 * 0.025,
                    optical_density: 1.05,
                }),
                comet: None,
            }
        );
    }

    #[test]
    fn comets_have_scaled_presentation_inputs_without_physics_inputs() {
        let appearance = canonical_celestial_appearance("halley", BodyClass::Comet, 5_500.0);

        assert_eq!(appearance.material, CelestialMaterialFamily::CometNucleus);
        assert_eq!(appearance.provenance, AppearanceProvenance::DerivedClassDefault);
        assert_eq!(
            appearance.comet,
            Some(CometAppearanceInputs {
                nucleus_radius_m: 5_500.0,
                coma_radius_m: 100_000.0,
                dust_tail_length_m: 330_000_000.0,
                ion_tail_length_m: 500_000_000.0,
            })
        );
    }
}
