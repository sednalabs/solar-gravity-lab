//! Pure world-snapshot to renderer-neutral scene projection.
//! Presentation values never change physical body state or simulation history.
use super::*;

#[must_use]
pub fn extract_render_scene(snapshot: &WorldSnapshot) -> RenderScene {
    let extract_started_at = Instant::now();
    let selected_body = snapshot.observer.focus_body_id.as_ref();
    let star_position_m = snapshot
        .bodies
        .iter()
        .find(|body| body.body_class == BodyClass::Star)
        .map(|body| body.position_m);
    let bodies: Vec<SceneBody> = snapshot
        .bodies
        .iter()
        .map(|body| {
            let style = body_style(body.body_class);
            SceneBody {
                body_id: body.body_id.clone(),
                display_name: body.body_id.0.clone(),
                kind: scene_body_kind(body),
                position_m: body.position_m,
                radius_m: body.radius_m,
                albedo: style.albedo,
                emissive_luminance: style.emissive_luminance,
                selected: selected_body
                    .map(|focused| focused == &body.body_id)
                    .unwrap_or(false),
                appearance: scene_celestial_appearance(body, star_position_m),
                spacecraft: snapshot.spacecraft.iter().find(|s|s.body_id==body.body_id).map(|s| solarlab_scene::SceneSpacecraftAppearance {
                    mesh_kind: s.appearance as u32,
                    attitude_xyzw:[s.attitude.x,s.attitude.y,s.attitude.z,s.attitude.w],
                    main_engine_throttle:if s.propellant_mass_kg>0.0{s.control.throttle}else{0.0},
                }),
            }
        })
        .collect();
    let tracers = extract_scene_tracers(snapshot);
    let trails = extract_scene_trails(snapshot);
    let lights = extract_lights(snapshot);
    let diagnostics = render_diagnostics(
        snapshot,
        &bodies,
        &tracers,
        &trails,
        &lights,
        extract_started_at,
    );

    RenderScene {
        observer_mode: snapshot.observer.mode.clone(),
        body_count: 0,
        tracer_count: 0,
        trail_count: 0,
        scene_revision: scene_revision_from_snapshot(
            snapshot,
            &bodies,
            &tracers,
            &trails,
            &lights,
            &diagnostics,
        ),
        epoch_seconds: snapshot.epoch_seconds,
        timeline_semantics: snapshot.timeline_semantics.clone(),
        camera: camera_pose_from_snapshot(snapshot),
        bodies,
        tracers,
        trails,
        packet_metadata: ScenePacketMetadata::default(),
        lights,
        provenance: scene_provenance(snapshot),
        diagnostics,
    }
    .with_derived_counts()
}

fn scene_celestial_appearance(
    body: &BodyState,
    star_position_m: Option<Vector3d>,
) -> SceneCelestialAppearance {
    let facts = canonical_celestial_appearance(&body.body_id.0, body.body_class, body.radius_m);
    let comet_visual = facts.comet.map(|_| SceneCometVisualGuide {
        anti_solar_direction_ws: normalized_or(
            star_position_m.map_or(body.position_m, |star| subtract(body.position_m, star)),
            Vector3d {
                x: 1.0,
                y: 0.0,
                z: 0.0,
            },
        ),
        velocity_direction_ws: normalized_or(
            body.velocity_mps,
            Vector3d {
                x: 0.0,
                y: 0.0,
                z: 1.0,
            },
        ),
    });
    SceneCelestialAppearance {
        facts,
        comet_visual,
    }
}

fn subtract(left: Vector3d, right: Vector3d) -> Vector3d {
    Vector3d {
        x: left.x - right.x,
        y: left.y - right.y,
        z: left.z - right.z,
    }
}

fn normalized_or(value: Vector3d, fallback: Vector3d) -> Vector3d {
    let magnitude = (value.x * value.x + value.y * value.y + value.z * value.z).sqrt();
    if magnitude.is_finite() && magnitude > 0.0 {
        Vector3d {
            x: value.x / magnitude,
            y: value.y / magnitude,
            z: value.z / magnitude,
        }
    } else {
        fallback
    }
}

fn scene_body_kind(body: &BodyState) -> SceneBodyKind {
    match &body.body_class {
        BodyClass::Star => SceneBodyKind::Star,
        BodyClass::Planet => SceneBodyKind::Planet,
        BodyClass::DwarfPlanet => SceneBodyKind::DwarfPlanet,
        BodyClass::Moon => SceneBodyKind::Moon,
        BodyClass::SmallBody => SceneBodyKind::Asteroid,
        BodyClass::Comet => SceneBodyKind::Comet,
        BodyClass::Tracer => SceneBodyKind::Tracer,
        BodyClass::Spacecraft => SceneBodyKind::Spacecraft,
        BodyClass::Custom => SceneBodyKind::Custom,
    }
}

#[derive(Clone, Copy)]
struct BodyRenderStyle {
    albedo: ColorRgba,
    emissive_luminance: f64,
    tracer_color: ColorRgba,
    light_illuminance_lux: f64,
}

fn body_style(body_class: BodyClass) -> BodyRenderStyle {
    match body_class {
        BodyClass::Star => BodyRenderStyle {
            albedo: ColorRgba {
                r: 1.0,
                g: 0.93,
                b: 0.78,
                a: 1.0,
            },
            emissive_luminance: 1_500_000.0,
            tracer_color: ColorRgba {
                r: 1.0,
                g: 0.95,
                b: 0.8,
                a: 0.85,
            },
            light_illuminance_lux: 120_000.0,
        },
        BodyClass::Planet => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.28,
                g: 0.42,
                b: 0.78,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.58,
                g: 0.74,
                b: 1.0,
                a: 0.6,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::DwarfPlanet => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.56,
                g: 0.54,
                b: 0.64,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.75,
                g: 0.75,
                b: 0.88,
                a: 0.55,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Moon => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.67,
                g: 0.67,
                b: 0.7,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.9,
                g: 0.9,
                b: 0.96,
                a: 0.55,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::SmallBody => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.51,
                g: 0.43,
                b: 0.34,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.78,
                g: 0.66,
                b: 0.54,
                a: 0.55,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Comet => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.68,
                g: 0.74,
                b: 0.78,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.72,
                g: 0.88,
                b: 0.94,
                a: 0.62,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Tracer => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.95,
                g: 0.95,
                b: 0.98,
                a: 1.0,
            },
            emissive_luminance: 5_000.0,
            tracer_color: ColorRgba {
                r: 1.0,
                g: 1.0,
                b: 1.0,
                a: 0.9,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Spacecraft => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.82,
                g: 0.84,
                b: 0.9,
                a: 1.0,
            },
            emissive_luminance: 400.0,
            tracer_color: ColorRgba {
                r: 0.72,
                g: 0.88,
                b: 1.0,
                a: 0.8,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Custom => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.88,
                g: 0.88,
                b: 0.9,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.9,
                g: 0.9,
                b: 1.0,
                a: 0.6,
            },
            light_illuminance_lux: 0.0,
        },
    }
}

fn extract_scene_tracers(snapshot: &WorldSnapshot) -> Vec<SceneTracer> {
    snapshot
        .bodies
        .iter()
        .filter(|body| body.body_class == BodyClass::Tracer)
        .map(|body| {
            let style = body_style(body.body_class.clone());
            SceneTracer {
                tracer_id: format!("tracer:{}", body.body_id.0),
                source_body_id: body.body_id.clone(),
                position_m: body.position_m,
                color: style.tracer_color,
                size_px: ((body.radius_m.abs().sqrt() / 20.0).clamp(1.25, 8.0)) as f32,
            }
        })
        .collect()
}

fn extract_scene_trails(snapshot: &WorldSnapshot) -> Vec<SceneTrail> {
    let focused_body_id = snapshot.observer.focus_body_id.as_ref();
    let body_index = snapshot
        .bodies
        .iter()
        .map(|body| (body.body_id.clone(), body))
        .collect::<HashMap<_, _>>();

    snapshot
        .orbit_archives
        .iter()
        .filter_map(|archive| {
            let samples_m =
                trail_frame::earth_relative_samples(snapshot, archive).unwrap_or_else(|| {
                    archive
                        .samples
                        .iter()
                        .map(|sample| sample.position_m)
                        .collect()
                });
            if samples_m.is_empty() {
                return None;
            }

            let body_class = body_index
                .get(&archive.source_body_id)
                .map(|body| body.body_class.clone())
                .unwrap_or(BodyClass::Custom);
            let style = body_style(body_class.clone());
            Some(SceneTrail {
                trail_id: archive.archive_id.clone(),
                source_body_id: archive.source_body_id.clone(),
                family: scene_trail_family(archive.family),
                samples_m,
                color: style.tracer_color,
                max_samples: archive.max_samples,
                head_highlighted: body_class == BodyClass::Tracer
                    || focused_body_id == Some(&archive.source_body_id),
            })
        })
        .collect()
}

fn scene_trail_family(family: OrbitArchiveFamily) -> SceneTrailFamily {
    match family {
        OrbitArchiveFamily::Trajectory => SceneTrailFamily::Trajectory,
        OrbitArchiveFamily::HistoricalOrbit => SceneTrailFamily::HistoricalOrbit,
        OrbitArchiveFamily::Prediction => SceneTrailFamily::Prediction,
    }
}

fn extract_lights(snapshot: &WorldSnapshot) -> Vec<LightSource> {
    snapshot
        .bodies
        .iter()
        .filter_map(|body| {
            let style = body_style(body.body_class.clone());
            if style.light_illuminance_lux <= 0.0 {
                return None;
            }
            let magnitude = vec_magnitude(body.position_m);
            let direction = if magnitude > 0.0 {
                Vector3d {
                    x: -body.position_m.x / magnitude,
                    y: -body.position_m.y / magnitude,
                    z: -body.position_m.z / magnitude,
                }
            } else {
                Vector3d {
                    x: 0.0,
                    y: -1.0,
                    z: 0.0,
                }
            };
            Some(LightSource {
                light_id: format!("light:{}", body.body_id.0),
                direction_ws: direction,
                illuminance_lux: style.light_illuminance_lux,
                color: style.albedo,
            })
        })
        .collect()
}

fn render_diagnostics(
    snapshot: &WorldSnapshot,
    bodies: &[SceneBody],
    tracers: &[SceneTracer],
    trails: &[SceneTrail],
    lights: &[LightSource],
    extract_started_at: Instant,
) -> RenderDiagnostics {
    let trail_samples = trails
        .iter()
        .map(|trail| trail.samples_m.len())
        .sum::<usize>();
    let upload_bytes_estimate = bodies.len() * size_of::<SceneBody>()
        + tracers.len() * size_of::<SceneTracer>()
        + lights.len() * size_of::<LightSource>()
        + trail_samples * size_of::<Vector3d>();

    RenderDiagnostics {
        frame_number: ((snapshot.epoch_seconds * 60.0).round().max(0.0)) as u64,
        cpu_extract_ms: extract_started_at.elapsed().as_secs_f32() * 1_000.0,
        gpu_upload_ms: (upload_bytes_estimate as f32) / 25_000.0,
        dropped_frames: 0,
    }
}

fn camera_pose_from_snapshot(snapshot: &WorldSnapshot) -> CameraPose {
    let focused_body = snapshot
        .observer
        .focus_body_id
        .as_ref()
        .and_then(|focused_id| {
            snapshot
                .bodies
                .iter()
                .find(|body| &body.body_id == focused_id)
        });
    let target_m = focused_body
        .map(|body| body.position_m)
        .or_else(|| snapshot.bodies.first().map(|body| body.position_m))
        .unwrap_or_default();

    let camera_distance = focused_body
        .map(|body| (body.radius_m * 6.0).max(1.0))
        .unwrap_or(1_000.0);

    CameraPose {
        position_m: Vector3d {
            x: target_m.x,
            y: target_m.y,
            z: target_m.z + camera_distance,
        },
        target_m,
        up: Vector3d {
            x: 0.0,
            y: 1.0,
            z: 0.0,
        },
        vertical_fov_degrees: 60.0,
        exposure: 1.0,
    }
}

pub(super) fn format_manifest_digest(digest: &Digest) -> String {
    format!("{}:{}", digest.algorithm, digest.hex_value())
}

pub(super) fn mounted_manifest_telemetry_from_state(
    mounted_manifest: &MountedManifestState,
) -> MountedManifestTelemetry {
    MountedManifestTelemetry {
        manifest_id: mounted_manifest.manifest_id.clone(),
        manifest_version: semver_to_string(&mounted_manifest.manifest_version),
        channel: mounted_manifest.channel.clone(),
        has_manifest_digest: mounted_manifest.manifest_digest.is_some(),
        mounted_packages: mounted_manifest.mounted_packages.len(),
    }
}

pub(super) fn runtime_trail_history_counts(
    trail_history_by_body: &HashMap<BodyId, Vec<TrailSample>>,
) -> Vec<RuntimeTrailHistoryCount> {
    let mut counts: Vec<RuntimeTrailHistoryCount> = trail_history_by_body
        .iter()
        .map(|(body_id, trail)| RuntimeTrailHistoryCount {
            body_id: body_id.clone(),
            sample_count: trail.len(),
        })
        .collect();
    counts.sort_by(|left, right| left.body_id.0.cmp(&right.body_id.0));
    counts
}

pub(super) fn scene_provenance(snapshot: &WorldSnapshot) -> Option<SceneProvenanceRef> {
    let Some(mounted_manifest) = snapshot.mounted_manifest.as_ref() else {
        return snapshot.initial_conditions_provenance.as_ref().map(|seed| SceneProvenanceRef {
            source: seed.dataset_id.clone(),
            version: seed.version.clone(),
            manifest_id: seed.dataset_id.clone(),
            manifest_digest: Some(seed.digest.clone()),
            package_digest: None,
        });
    };
    Some(SceneProvenanceRef {
        source: mounted_manifest.channel.clone(),
        version: semver_to_string(&mounted_manifest.manifest_version),
        manifest_id: mounted_manifest.manifest_id.clone(),
        manifest_digest: mounted_manifest
            .manifest_digest
            .as_ref()
            .map(format_manifest_digest),
        package_digest: package_provenance_digest(&mounted_manifest.mounted_packages),
    })
}

fn semver_to_string(version: &SemVer) -> String {
    let mut rendered = format!("{}.{}.{}", version.major, version.minor, version.patch);
    if let Some(prerelease) = &version.prerelease {
        rendered.push('-');
        rendered.push_str(prerelease);
    }
    if let Some(build_metadata) = &version.build_metadata {
        rendered.push('+');
        rendered.push_str(build_metadata);
    }
    rendered
}

fn scene_revision_from_snapshot(
    snapshot: &WorldSnapshot,
    bodies: &[SceneBody],
    tracers: &[SceneTracer],
    trails: &[SceneTrail],
    lights: &[LightSource],
    diagnostics: &RenderDiagnostics,
) -> String {
    let selected_body_id = snapshot.observer.focus_body_id.as_ref();
    let mut revision = format!(
        "scenario={}|branch={}|epoch={:.6}|observer={:?}",
        snapshot.scenario_id.0,
        snapshot.branch_id.0,
        snapshot.epoch_seconds,
        snapshot.observer.mode
    );
    if let Some(seed) = &snapshot.initial_conditions_provenance {
        use std::fmt::Write as _;
        let _ = write!(&mut revision, "|seed={}|version={}|digest={}", seed.dataset_id, seed.version, seed.digest);
    }
    for (body_state, body_scene) in snapshot.bodies.iter().zip(bodies.iter()) {
        let selected = selected_body_id == Some(&body_state.body_id);
        use std::fmt::Write as _;
        let _ = write!(
            &mut revision,
            "|{}|class={:?}|selected={selected}|r={:.6}|p=({:.6},{:.6},{:.6})|v=({:.6},{:.6},{:.6})|e={:.3}|appearance={:?}",
            body_state.body_id.0,
            body_state.body_class,
            body_state.radius_m,
            body_state.position_m.x,
            body_state.position_m.y,
            body_state.position_m.z,
            body_state.velocity_mps.x,
            body_state.velocity_mps.y,
            body_state.velocity_mps.z,
            body_scene.emissive_luminance,
            body_scene.appearance
        );
        let _ = write!(&mut revision,"|spacecraft={:?}",body_scene.spacecraft);
    }
    if let Some(mounted_manifest) = &snapshot.mounted_manifest {
        use std::fmt::Write as _;
        let _ = write!(
            &mut revision,
            "|manifest={}|channel={}|version={}|manifest_digest={}",
            mounted_manifest.manifest_id,
            mounted_manifest.channel,
            semver_to_string(&mounted_manifest.manifest_version),
            mounted_manifest
                .manifest_digest
                .as_ref()
                .map_or_else(|| "none".to_owned(), format_manifest_digest)
        );
        for package in &mounted_manifest.mounted_packages {
            let _ = write!(
                &mut revision,
                "|package={}|digest={}:{}",
                package.package_id,
                package.digest.algorithm,
                package.digest.hex_value()
            );
        }
    } else {
        revision.push_str("|manifest=none");
    }

    use std::fmt::Write as _;
    let _ = write!(
        &mut revision,
        "|families:bodies={}|tracers={}|trails={}|lights={}",
        bodies.len(),
        tracers.len(),
        trails.len(),
        lights.len()
    );
    for tracer in tracers {
        let _ = write!(
            &mut revision,
            "|tracer:{}@({:.6},{:.6},{:.6})",
            tracer.source_body_id.0, tracer.position_m.x, tracer.position_m.y, tracer.position_m.z
        );
    }
    for trail in trails {
        let sample_count = trail.samples_m.len();
        let last_sample = trail.samples_m.last().copied().unwrap_or_default();
        let _ = write!(
            &mut revision,
            "|trail:{:?}:{}:{}@({:.6},{:.6},{:.6})",
            trail.family,
            trail.source_body_id.0,
            sample_count,
            last_sample.x,
            last_sample.y,
            last_sample.z
        );
    }
    for light in lights {
        let _ = write!(
            &mut revision,
            "|light:{}:lux={:.3}:dir=({:.6},{:.6},{:.6})",
            light.light_id,
            light.illuminance_lux,
            light.direction_ws.x,
            light.direction_ws.y,
            light.direction_ws.z
        );
    }
    let _ = write!(
        &mut revision,
        "|diag:frame={}:cpu={:.3}:gpu={:.3}:drop={}",
        diagnostics.frame_number,
        diagnostics.cpu_extract_ms,
        diagnostics.gpu_upload_ms,
        diagnostics.dropped_frames
    );

    revision
}
