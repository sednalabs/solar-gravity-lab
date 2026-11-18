//! Small fixture reader shared by the executable comparison and its regression.
use solarlab_domain::science::{ScientificSettings, ZonalHarmonic};
use solarlab_domain::{BodyId, Vector3d};
use solarlab_physics::MassiveBodyState;

pub struct ReferenceCase {
    pub name: String,
    pub settings: ScientificSettings,
    pub body_ids: Vec<BodyId>,
    pub samples: Vec<(f64, Vec<MassiveBodyState>)>,
}

pub fn cases() -> Vec<ReferenceCase> {
    let mut result: Vec<ReferenceCase> = Vec::new();
    let text = include_str!("../tests/fixtures/scientific-trajectories.csv");
    let mut models = Vec::new();
    for line in text.lines() {
        let columns: Vec<_> = line.split(',').collect();
        if columns[0] == "# model" {
            models.push((
                columns[1].to_string(),
                ZonalHarmonic {
                    body_id: BodyId(format!("reference-{}", columns[2])),
                    j2: columns[3].parse().unwrap(),
                    reference_radius_m: columns[4].parse().unwrap(),
                    axis_ws: Vector3d {
                        x: columns[5].parse().unwrap(),
                        y: columns[6].parse().unwrap(),
                        z: columns[7].parse().unwrap(),
                    },
                },
            ));
            continue;
        }
        if line.starts_with('#') || columns[0] == "case" {
            continue;
        }
        let name = columns[0];
        let index = match result.iter().position(|case| case.name == name) {
            Some(index) => index,
            None => {
                result.push(ReferenceCase {
                    name: name.into(),
                    settings: ScientificSettings::default(),
                    body_ids: Vec::new(),
                    samples: Vec::new(),
                });
                result.len() - 1
            }
        };
        let case = &mut result[index];
        let time: f64 = columns[1].parse().unwrap();
        if case.samples.last().is_none_or(|sample| sample.0 != time) {
            case.samples.push((time, Vec::new()));
        }
        let body_index: usize = columns[2].parse().unwrap();
        let sample = &mut case.samples.last_mut().unwrap().1;
        assert_eq!(body_index, sample.len(), "fixture body ordering");
        sample.push(MassiveBodyState {
            mass_kg: columns[3].parse().unwrap(),
            position_m: Vector3d {
                x: columns[4].parse().unwrap(),
                y: columns[5].parse().unwrap(),
                z: columns[6].parse().unwrap(),
            },
            velocity_mps: Vector3d {
                x: columns[7].parse().unwrap(),
                y: columns[8].parse().unwrap(),
                z: columns[9].parse().unwrap(),
            },
        });
        if time == 0.0 {
            case.body_ids
                .push(BodyId(format!("reference-{body_index}")));
        }
    }
    for (name, model) in models {
        result
            .iter_mut()
            .find(|case| case.name == name)
            .unwrap()
            .settings
            .harmonics
            .push(model);
    }
    result
}
