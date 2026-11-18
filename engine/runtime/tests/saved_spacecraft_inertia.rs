use solarlab_hardware::HardwareProfile;
use solarlab_runtime::WorldRuntime;

#[test]
fn saved_authored_craft_must_retain_physical_inertia_validation() {
    let world = WorldRuntime::import_authored_scenario(
        include_bytes!("../../data/authoring/orbital-workshop.scenario.json"),
        HardwareProfile::offline_reference(),
        0,
    )
    .unwrap();
    let saved = world.export_saved_session().unwrap();
    let mut document: serde_json::Value = serde_json::from_slice(&saved).unwrap();
    for moments in [
        serde_json::json!({"x": 1e12, "y": 1.0, "z": 1.0}),
        serde_json::json!({"x": 1.0, "y": 1e12, "z": 1.0}),
        serde_json::json!({"x": 1.0, "y": 1.0, "z": 1e12}),
    ] {
        document["branches"][0]["world"]["spacecraft"][0]["inertia_kg_m2"] = moments;
        assert!(WorldRuntime::import_saved_session(
            &serde_json::to_vec(&document).unwrap(),
            HardwareProfile::offline_reference(),
        )
        .is_err());
    }
    assert_eq!(world.export_saved_session().unwrap(), saved);
    assert!(
        WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference()).is_ok()
    );
}
