use super::*;
use solarlab_domain::{AttitudeMode, FlightControl};

#[test]
fn preview_uses_native_solver_without_spending_live_fuel_or_time() {
    let mut session = FlightSession::new().unwrap();
    let before = session.world.export_saved_session().unwrap();
    let state = session.state().unwrap();
    let native = session.world.flight_telemetry(&session.selected).unwrap().guidance.unwrap();
    assert_eq!(state["ship"]["navigation"], serde_json::to_value(native).unwrap());
    assert_eq!(state["ship"]["navigation"]["regime"], "nearCircular");
    assert!(state["ship"]["navigation"]["timeToApoapsisSeconds"].is_null());
    let result = session
        .handle(Action::Preview {
            duration: 30.0,
            throttle: 0.4,
            assist: AttitudeMode::Prograde,
        })
        .unwrap();
    assert!(result["preview"]["fuelUsedKg"].as_f64().unwrap() > 0.0);
    assert_eq!(before, session.world.export_saved_session().unwrap());
    assert_eq!(result["preview"]["elapsedSeconds"], 30.0);
    assert_eq!(result["preview"]["referenceRadiusM"], state["ship"]["hostRadius"]);
    assert_eq!(result["preview"]["beforeGuidance"], state["ship"]["navigation"]);
    assert!(result["preview"]["afterGuidance"]["circularisationDeltaVMps"].as_f64().unwrap().is_finite());
}

#[test]
fn cut_rejects_an_older_in_flight_control_and_disconnect_pauses() {
    let mut session = FlightSession::new().unwrap();
    let powered = FlightControl {
        throttle: 1.0,
        ..FlightControl::default()
    };
    session
        .handle(Action::Control {
            sequence: 4,
            control: powered,
        })
        .unwrap();
    session.handle(Action::Cut { sequence: 5 }).unwrap();
    session
        .handle(Action::Control {
            sequence: 4,
            control: powered,
        })
        .unwrap();
    assert_eq!(
        session
            .world
            .spacecraft_by_id(&session.selected)
            .unwrap()
            .control
            .throttle,
        0.0
    );
    session.handle(Action::Resume).unwrap();
    session.last_heartbeat = Instant::now() - Duration::from_secs(2);
    let epoch = session.world.snapshot().epoch_seconds;
    session.tick(0.02);
    assert!(session.world.snapshot().playback.paused);
    assert_eq!(session.world.snapshot().epoch_seconds, epoch);
}

#[test]
fn data_authored_vehicles_are_flyable_without_desktop_special_cases() {
    let mut session = FlightSession::new().unwrap();
    session.handle(Action::Workshop).unwrap();
    let snapshot = session.state().unwrap();
    assert_eq!(snapshot["crafts"].as_array().unwrap().len(), 2);
    assert!(snapshot["ship"]["name"].as_str().unwrap().contains("Sable"));
    assert_eq!(snapshot["ship"]["id"], "workshop.sable");
    let ids: Vec<_> = snapshot["crafts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["id"].as_str().unwrap().to_owned())
        .collect();
    let generation = session.generation;
    session
        .handle(Action::Select {
            body_id: ids[0].clone(),
        })
        .unwrap();
    assert_eq!(session.state().unwrap()["ship"]["maxThrust"], 0.8);
    session
        .handle(Action::Select {
            body_id: ids[1].clone(),
        })
        .unwrap();
    assert!(session.generation > generation);
    assert_eq!(session.selected, ids[1]);
    assert!(
        session.state().unwrap()["ship"]["maxThrust"]
            .as_f64()
            .unwrap()
            > 0.8
    );
    session
        .handle(Action::Select {
            body_id: ids[0].clone(),
        })
        .unwrap();
    let saved = session.world.export_saved_session().unwrap();
    session.import(&saved).unwrap();
    assert_eq!(
        session.selected, ids[0],
        "Portable saves retain the chosen spacecraft"
    );
    let previous = session.world.export_saved_session().unwrap();
    assert!(session.import(br#"{"format":"unknown"}"#).is_err());
    assert_eq!(previous, session.world.export_saved_session().unwrap());
}

#[test]
fn a_third_catalog_visual_keeps_its_native_identity() {
    let mut session = FlightSession::new().unwrap();
    session
        .import(include_bytes!(
            "../../../engine/data/authoring/pathfinder.scenario.json"
        ))
        .unwrap();
    let state = session.state().unwrap();
    assert_eq!(state["ship"]["name"], "Pathfinder surveyor");
    assert_eq!(state["ship"]["visual"]["assetId"], "example:pathfinder");
    assert_eq!(state["ship"]["visual"]["meshKind"], 3);
    assert_eq!(state["crafts"][0]["visual"], state["ship"]["visual"]);
}

#[test]
fn checkpoint_restore_recovers_selection_when_a_later_vehicle_disappears() {
    let mut session = FlightSession::new().unwrap();
    session.handle(Action::Checkpoint).unwrap();
    session
        .import(include_bytes!(
            "../../../engine/data/authoring/iris.spacecraft.json"
        ))
        .unwrap();
    let id = session
        .world
        .snapshot()
        .spacecraft
        .iter()
        .find(|s| s.body_id.0 != "flight.odyssey")
        .unwrap()
        .body_id
        .0
        .clone();
    session.handle(Action::Select { body_id: id }).unwrap();
    session.handle(Action::Reset).unwrap();
    assert_eq!(session.selected, "flight.odyssey");
    assert!(session.state().unwrap()["paused"].as_bool().unwrap());
}

#[test]
fn watchdog_also_clears_staged_thrust_and_invalidates_the_old_generation() {
    let mut session = FlightSession::new().unwrap();
    session
        .handle(Action::Control {
            sequence: 1,
            control: FlightControl {
                throttle: 0.4,
                ..FlightControl::default()
            },
        })
        .unwrap();
    let generation = session.generation;
    session.last_heartbeat = Instant::now() - Duration::from_secs(2);
    session.tick(0.02);
    assert!(session.world.snapshot().playback.paused);
    assert_eq!(
        session
            .world
            .spacecraft_by_id(&session.selected)
            .unwrap()
            .control
            .throttle,
        0.0
    );
    assert!(session.generation > generation);
}

#[test]
fn stale_heartbeat_resynchronizes_without_renewing_contact_or_rearming_input() {
    let mut session = FlightSession::new().unwrap();
    let generation = session.generation;
    let powered = FlightControl {
        throttle: 0.4,
        rotation_body: solarlab_domain::Vector3d {
            x: 1.0,
            y: 0.0,
            z: 0.0,
        },
        ..FlightControl::default()
    };
    session
        .handle(Action::Control {
            sequence: 1,
            control: powered,
        })
        .unwrap();
    session.handle(Action::Resume).unwrap();
    session.last_heartbeat = Instant::now() - Duration::from_secs(2);
    session.tick(0.02);
    let expired = session.last_heartbeat;
    let saved = session.world.export_saved_session().unwrap();
    let response = CommandRequest {
        generation,
        command: Action::Heartbeat,
    }
    .apply(&mut session)
    .unwrap();
    assert_eq!(response["heartbeatAccepted"], false);
    assert_eq!(response["generation"], session.generation);
    assert_eq!(session.last_heartbeat, expired);
    assert_eq!(session.world.export_saved_session().unwrap(), saved);
    assert!(response["paused"].as_bool().unwrap());
    assert_eq!(response["ship"]["control"]["throttle"], 0.0);
    assert_eq!(response["ship"]["control"]["rotation_body"]["x"], 0.0);

    assert!(CommandRequest {
        generation,
        command: Action::Control {
            sequence: 2,
            control: powered,
        },
    }
    .apply(&mut session)
    .is_err());
    assert_eq!(session.world.export_saved_session().unwrap(), saved);

    let response = CommandRequest {
        generation: session.generation,
        command: Action::Heartbeat,
    }
    .apply(&mut session)
    .unwrap();
    assert_eq!(response["heartbeatAccepted"], true);
    assert!(session.last_heartbeat > expired);
    assert_eq!(session.world.export_saved_session().unwrap(), saved);
    assert!(response["paused"].as_bool().unwrap());
}

#[test]
fn bridge_requests_require_local_origin_host_and_session_token() {
    let mut request = Request {
        method: "POST".into(),
        path: "/api/command".into(),
        body: Vec::new(),
        headers: BTreeMap::from([
            ("host".into(), "127.0.0.1:8787".into()),
            ("origin".into(), "http://127.0.0.1:8787".into()),
            ("x-sgl-token".into(), "test-session".into()),
            ("content-type".into(), "application/json".into()),
        ]),
    };
    assert!(request.allowed("127.0.0.1:8787", "test-session"));
    request.headers.remove("x-sgl-token");
    assert!(!request.allowed("127.0.0.1:8787", "test-session"));
    request
        .headers
        .insert("x-sgl-token".into(), "test-session".into());
    request
        .headers
        .insert("origin".into(), "https://example.com".into());
    assert!(!request.allowed("127.0.0.1:8787", "test-session"));
    request
        .headers
        .insert("origin".into(), "http://127.0.0.1:8787".into());
    request
        .headers
        .insert("host".into(), "example.com:8787".into());
    assert!(!request.allowed("127.0.0.1:8787", "test-session"));
}
