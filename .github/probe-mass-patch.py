#!/usr/bin/env python3
"""Apply the reviewed mass-authoring slice to the fixed recovery source.
The workbench applies this from a clean detached product commit, never main.
Every replacement is asserted so drift fails before publishing a candidate.
"""
from pathlib import Path
import re

def replace(path, old, new, count=1):
    p=Path(path); s=p.read_text()
    assert s.count(old)==count, (path, s.count(old), old[:140])
    p.write_text(s.replace(old,new))

def write(path, text):
    p=Path(path); assert not p.exists(), path
    p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text)

rt='engine/runtime/src/lib.rs'
replace(rt,'mod lab_step;', 'mod body_properties;\nmod lab_step;')
replace(rt,'    /// Advances the system epoch.\n', '''    /// Atomically edits physical properties and kinematics without replacing identity/history.
    SetBodyProperties {
        body_id: BodyId,
        mass_kg: f64,
        source_mass_kg: f64,
        radius_m: f64,
        position_m: Vector3d,
        velocity_mps: Vector3d,
    },
    /// Advances the system epoch.
''')
replace(rt,'    InvalidPlaybackRate(f64),','    InvalidPlaybackRate(f64),\n    InvalidBodyProperties,')
replace(rt,'        let mut events = Vec::new();\n        let mut header = self.new_command_header(&command, recorded_at_unix_ms);', '''        // Validate before consuming an ordinal or changing history/world state.
        if let WorldCommand::SetBodyProperties {
            body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps,
        } = &command {
            body_properties::validate(
                &self.active_branch().world.bodies, body_id, *mass_kg,
                *source_mass_kg, *radius_m, *position_m, *velocity_mps,
            )?;
        }
        let mut events = Vec::new();
        let mut header = self.new_command_header(&command, recorded_at_unix_ms);''')
replace(rt,'            WorldCommand::AdvanceEpoch { delta_seconds } => {\n                let physics_policy = self.effective_physics_policy();', '''            WorldCommand::SetBodyProperties {
                body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps,
            } => {
                let branch = self.active_branch_mut();
                let body = branch.world.bodies.iter_mut()
                    .find(|body| body.body_id == *body_id)
                    .expect("body-properties preflight established identity");
                body.mass_kg = *mass_kg;
                body.source_mass_kg = *source_mass_kg;
                body.radius_m = *radius_m;
                body.position_m = *position_m;
                body.velocity_mps = *velocity_mps;
                branch.world.invariants = compute_world_invariants(&branch.world.bodies);
                push_trail_sample(&mut branch.world.trail_history_by_body,
                    body_id, branch.world.epoch_seconds, *position_m);
            }
            WorldCommand::AdvanceEpoch { delta_seconds } => {
                let physics_policy = self.effective_physics_policy();''')
replace(rt,'            WorldCommand::SetBodyKinematics { .. } => "body.set_kinematics",', '            WorldCommand::SetBodyKinematics { .. } => "body.set_kinematics",\n            WorldCommand::SetBodyProperties { .. } => "body.set_properties",')
replace(rt,'            WorldCommand::RemoveBody { body_id } => format!("remove {}", body_id.0),', '''            WorldCommand::RemoveBody { body_id } => format!("remove {}", body_id.0),
            WorldCommand::SetBodyProperties { body_id, mass_kg, source_mass_kg, .. } =>
                format!("set properties {} mass={} kg source={} kg", body_id.0, mass_kg, source_mass_kg),''')
write('engine/runtime/src/body_properties.rs', '''//! Transactional edits: no partial body mutation, new identity or history reset.
use super::*;

fn finite_vector(v: Vector3d) -> bool {
    v.x.is_finite() && v.y.is_finite() && v.z.is_finite()
}

pub(super) fn validate(
    bodies: &[BodyState], id: &BodyId, mass: f64, source: f64, radius: f64,
    position: Vector3d, velocity: Vector3d,
) -> Result<(), RuntimeError> {
    if !mass.is_finite() || mass < 0.0 || !source.is_finite() || source < 0.0
        || !radius.is_finite() || radius <= 0.0
        || !finite_vector(position) || !finite_vector(velocity) {
        return Err(RuntimeError::InvalidBodyProperties);
    }
    let index = bodies.iter().position(|b| &b.body_id == id)
        .ok_or_else(|| RuntimeError::UnknownBody(id.clone()))?;
    // A finite input can still overflow energy or barycentre. Check the proposed
    // state before touching the live branch; this cost is only paid on edits.
    let mut proposed = bodies.to_vec();
    let body = &mut proposed[index];
    body.mass_kg = mass;
    body.source_mass_kg = source;
    body.radius_m = radius;
    body.position_m = position;
    body.velocity_mps = velocity;
    let inv = compute_world_invariants(&proposed);
    if !inv.total_energy_j.is_finite() || !finite_vector(inv.linear_momentum_kg_mps)
        || !finite_vector(inv.angular_momentum_kg_m2ps) || !finite_vector(inv.barycenter_m) {
        return Err(RuntimeError::InvalidBodyProperties);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn lab() -> WorldRuntime {
        let mut r = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        r.apply_command(WorldCommand::SeedCanonicalSolarSystem, 1).unwrap();
        let earth = r.snapshot().bodies.into_iter().find(|b| b.body_id.0 == "earth").unwrap();
        let mut probe = earth;
        probe.body_id = BodyId("mass-probe".into());
        probe.body_class = BodyClass::Spacecraft;
        probe.mass_kg = 1_000.0;
        probe.source_mass_kg = 0.0;
        probe.radius_m = 2.0;
        probe.position_m.x += 20_000_000.0;
        r.apply_command(WorldCommand::SpawnBody { body: probe }, 2).unwrap();
        r
    }
    fn edit(r: &WorldRuntime, mass: f64, source: f64) -> WorldCommand {
        let b = r.snapshot().bodies.into_iter().find(|b| b.body_id.0 == "mass-probe").unwrap();
        WorldCommand::SetBodyProperties {
            body_id: b.body_id, mass_kg: mass, source_mass_kg: source, radius_m: b.radius_m,
            position_m: b.position_m, velocity_mps: b.velocity_mps,
        }
    }
    #[test]
    fn properties_edit_preserves_identity_and_roundtrips_mass_and_source() {
        let mut r = lab();
        let before = r.snapshot(); let ordinal = r.next_command_ordinal;
        r.apply_command(edit(&r, 1.0e22, 1.0e22), 3).unwrap();
        let after = r.snapshot();
        assert_eq!(after.epoch_seconds, before.epoch_seconds);
        assert_eq!(after.bodies.len(), before.bodies.len());
        assert_eq!(r.next_command_ordinal, ordinal + 1);
        let b = after.bodies.iter().find(|b| b.body_id.0 == "mass-probe").unwrap();
        assert_eq!(b.mass_kg, 1.0e22); assert_eq!(b.source_mass_kg, 1.0e22);
        assert_eq!(b.body_class, BodyClass::Spacecraft);
        let saved = r.export_saved_session().unwrap();
        let restored = WorldRuntime::import_saved_session(&saved, r.hardware_profile.clone()).unwrap();
        assert_eq!(restored.snapshot().bodies, after.bodies);
    }
    #[test]
    fn invalid_mass_edit_leaves_world_history_and_ordinal_unchanged() {
        let mut r = lab();
        for (mass, source) in [(f64::NAN,0.0),(-1.0,0.0),(1.0,f64::INFINITY),(1.0,-1.0),(f64::MAX,f64::MAX)] {
            let before = r.snapshot(); let ordinal = r.next_command_ordinal;
            assert_eq!(r.apply_command(edit(&r,mass,source),3), Err(RuntimeError::InvalidBodyProperties));
            assert_eq!(r.snapshot(),before); assert_eq!(r.next_command_ordinal,ordinal);
        }
        let mut missing = edit(&r, 5.0, 0.0);
        if let WorldCommand::SetBodyProperties { body_id, .. } = &mut missing { *body_id = BodyId("missing".into()); }
        let before = r.snapshot(); let ordinal = r.next_command_ordinal;
        assert!(matches!(r.apply_command(missing,3),Err(RuntimeError::UnknownBody(_))));
        assert_eq!(r.snapshot(),before); assert_eq!(r.next_command_ordinal,ordinal);
    }
    #[test]
    fn passive_mass_does_not_perturb_but_source_mass_changes_earth_motion() {
        let mut baseline=lab(); let mut passive=lab(); let mut active=lab();
        passive.apply_command(edit(&passive,1.0e22,0.0),3).unwrap();
        active.apply_command(edit(&active,1.0e22,1.0e22),3).unwrap();
        for r in [&mut baseline,&mut passive,&mut active] {
            r.apply_command(WorldCommand::AdvanceEpoch {delta_seconds: 60.0},4).unwrap();
        }
        let earth = |r: &WorldRuntime| r.snapshot().bodies.into_iter().find(|b| b.body_id.0 == "earth").unwrap();
        assert_eq!(earth(&baseline),earth(&passive));
        assert_ne!(earth(&baseline).velocity_mps,earth(&active).velocity_mps);
    }
}
''')

ffi='engine/ffi/src/lib.rs'
replace(ffi,'    SeedCanonicalSolarSystem = 11,','    SeedCanonicalSolarSystem = 11,\n    SetBodyProperties = 12,')
replace(ffi,'        SlCommandKind::SetBodyKinematics => {', '''        SlCommandKind::SetBodyProperties => Ok(WorldCommand::SetBodyProperties {
            body_id: BodyId(decode_identifier(&command.body_id, command.body_id_len)?),
            mass_kg: command.body_mass_kg,
            source_mass_kg: command.body_source_mass_kg,
            radius_m: command.body_radius_m,
            position_m: decode_vector3d(command.body_position),
            velocity_mps: decode_vector3d(command.body_velocity),
        }),
        SlCommandKind::SetBodyKinematics => {''')
replace(ffi,'        | RuntimeError::InvalidPlaybackRate(_)','        | RuntimeError::InvalidPlaybackRate(_)\n        | RuntimeError::InvalidBodyProperties')
replace(ffi,'            11 => Ok(SlCommandKind::SeedCanonicalSolarSystem),','            11 => Ok(SlCommandKind::SeedCanonicalSolarSystem),\n            12 => Ok(SlCommandKind::SetBodyProperties),')
h='engine/ffi/include/solarlab_v2.h'
s=Path(h).read_text(); m=re.search(r'^(\s*)(\w*SEED_CANONICAL_SOLAR_SYSTEM)\s*=\s*11,?$',s,re.M)
assert m, 'header enum naming'
prefix=m[2].removesuffix('SEED_CANONICAL_SOLAR_SYSTEM')
s=s[:m.end()]+f'\n{m[1]}{prefix}SET_BODY_PROPERTIES = 12,'+s[m.end():]
# Preserve a comma even if the former last enumerator omitted it.
s=s.replace(m[0]+'\n',m[0].rstrip(',')+',\n',1)
Path(h).write_text(s)

kt='clients/android/app/src/main/kotlin/com/sednalabs/solarlab/'
br=kt+'runtime/RuntimeBridge.kt'
replace(br,'    data class SetBodyKinematics(', '''    data class SetBodyProperties(
        val bodyId: String,
        val massKg: Double,
        val sourceMassKg: Double,
        val radiusM: Double,
        val positionX: Double, val positionY: Double, val positionZ: Double,
        val velocityX: Double, val velocityY: Double, val velocityZ: Double,
    ) : RuntimeCommand { override val label: String = "body.set_properties" }

    data class SetBodyKinematics(''')
replace(br,'    is RuntimeCommand.SetBodyKinematics -> NativeRuntimeCommandPayload(', '''    is RuntimeCommand.SetBodyProperties -> NativeRuntimeCommandPayload(
        kind = 12, // Additive SlCommandKind::SetBodyProperties; existing ABI fields.
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
        bodyMassKg = massKg, bodySourceMassKg = sourceMassKg, bodyRadiusM = radiusM,
        bodyPositionX = positionX, bodyPositionY = positionY, bodyPositionZ = positionZ,
        bodyVelocityX = velocityX, bodyVelocityY = velocityY, bodyVelocityZ = velocityZ,
    )

    is RuntimeCommand.SetBodyKinematics -> NativeRuntimeCommandPayload(''')

ed=kt+'LabExperimentDialogs.kt'
replace(ed,'''        val mass = draft.massKg.toDoubleOrNull()
        val radius = draft.radiusM.toDoubleOrNull()
        if (mass == null || !mass.isFinite() || mass <= 0.0 || radius == null || !radius.isFinite() || radius <= 0.0) {
            return "Mass and radius must be finite numbers greater than zero."
        }
    }
    return null''', '''    }
    val mass = draft.massKg.toDoubleOrNull()
    val radius = draft.radiusM.toDoubleOrNull()
    if (mass == null || !mass.isFinite() || mass < 0.0 || radius == null || !radius.isFinite() || radius <= 0.0) {
        return "Mass must be finite and non-negative; radius must be finite and positive."
    }
    if (draft.sourceGravity && mass == 0.0) return "An attracting probe needs a positive mass."
    return null''')
replace(ed,'        RuntimeCommand.SetBodyKinematics(session.body.bodyId, x, y, z, vx, vy, vz)', '''        // Kinematics-only edits preserve an explicitly authored source/physical
        // mass ratio, rather than silently normalising existing saved worlds.
        val mass = draft.massKg.toDouble()
        val source = if (draft.massKg == initial.massKg && draft.sourceGravity == initial.sourceGravity)
            session.body.sourceMassKg else if (draft.sourceGravity) mass else 0.0
        RuntimeCommand.SetBodyProperties(session.body.bodyId, mass, source,
            draft.radiusM.toDouble(), x, y, z, vx, vy, vz)''')
replace(ed,'Text("Edit position & velocity")','Text("Edit mass & motion")')
replace(ed,'                if (session.creating) {\n                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {', '                run {\n                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {')
replace(ed,'Text("Exert gravity", style = MaterialTheme.typography.bodyMedium)','Text("Attracts other bodies", style = MaterialTheme.typography.bodyMedium)')
replace(ed,'Checkbox(checked = draft.sourceGravity, onCheckedChange = { draft = draft.copy(sourceGravity = it) }, enabled = !committing)', 'Checkbox(checked = draft.sourceGravity, onCheckedChange = { draft = draft.copy(sourceGravity = it) }, enabled = !committing,\n                            modifier = Modifier.testTag("lab.edit.gravity"))')
replace(ed,'Text("Off: a non-perturbing test particle.", style = MaterialTheme.typography.bodySmall,', 'Text(if (draft.sourceGravity) "On: this mass changes other bodies’ motion." else "Off: mass is recorded, but other bodies are not perturbed.", style = MaterialTheme.typography.bodySmall,')

sp=kt+'LabSpatialExperiment.kt'
replace(sp,'    val altitudeLocked: Boolean = false,\n) {', '    val altitudeLocked: Boolean = false,\n    val massKgText: String = "1000",\n    val sourceGravity: Boolean = false,\n) {')
replace(sp,'    val altitudeM: Double? get()', '''    val massKg: Double? get() = massKgText.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    val sourceMassKg: Double get() = if (sourceGravity) massKg ?: 0.0 else 0.0
    val altitudeM: Double? get()''')
replace(sp,'sqrt(6.67430e-11 * reference.sourceMassKg / it)','sqrt(6.67430e-11 * (reference.sourceMassKg + sourceMassKg) / it)')
replace(sp,'    val validationError: String? get() = when {','''    val validationError: String? get() = when {
        massKg == null -> "Enter a finite, non-negative mass in kilograms. Scientific notation is supported."
        sourceGravity && massKg == 0.0 -> "An attracting probe needs a positive mass."
        motion == LabPlacementMotion.CIRCULAR && circularSpeedMps == null -> "Mass is too large for a finite orbit speed."''')
# This exact candidate statement occurs once; the separate legacy seed stays unchanged.
replace(sp,'            massKg = 1_000.0, sourceMassKg = 0.0, radiusM = 2.0,','            massKg = requireNotNull(massKg), sourceMassKg = sourceMassKg, radiusM = 2.0,')

sheet=kt+'LabPlacementSheet.kt'
replace(sheet,'    val headingDegreesText: String = "90",\n)', '    val headingDegreesText: String = "90",\n    val massKgText: String = "1000",\n    val sourceGravity: Boolean = false,\n)')
replace(sheet,'    val onHeadingChange: (String) -> Unit = {},\n)', '    val onHeadingChange: (String) -> Unit = {},\n    val onMassChange: (String) -> Unit = {},\n    val onSourceGravityChange: (Boolean) -> Unit = {},\n)')
replace(sheet,'    const val HEADING = "lab.placement.heading"','    const val HEADING = "lab.placement.heading"\n    const val MASS = "lab.placement.mass"\n    const val GRAVITY = "lab.placement.gravity"\n    const val SECTION_BODY = "lab.placement.section.body"')
replace(sheet,'private enum class PlacementSection { POSITION, MOTION }','private enum class PlacementSection { POSITION, MOTION, BODY }')
replace(sheet,'    val motionScroll = rememberScrollState()','    val motionScroll = rememberScrollState()\n    val bodyScroll = rememberScrollState()')
replace(sheet,'                }\n                HorizontalDivider(color = LabLine.copy(alpha = 0.6f))\n                Column(Modifier.weight(1f).fillMaxWidth()', '''                    PlacementSectionTab("Mass", "${state.massKgText} kg",
                        section == PlacementSection.BODY, !state.committing, LabPlacementTags.SECTION_BODY,
                        Modifier.weight(1f)) { showSection(PlacementSection.BODY) }
                }
                HorizontalDivider(color = LabLine.copy(alpha = 0.6f))
                Column(Modifier.weight(1f).fillMaxWidth()''')
replace(sheet,'.verticalScroll(if (section == PlacementSection.POSITION) positionScroll else motionScroll)', '.verticalScroll(when (section) { PlacementSection.POSITION -> positionScroll; PlacementSection.MOTION -> motionScroll; PlacementSection.BODY -> bodyScroll })')
replace(sheet,'                    if (section == PlacementSection.POSITION) {','''                    if (section == PlacementSection.BODY) {
                        PlacementNumberField(state.massKgText, "Probe mass · kg", LabPlacementTags.MASS,
                            !state.committing, Modifier.fillMaxWidth(), actions.onMassChange)
                        FlowRow(Modifier.fillMaxWidth()) {
                            listOf("1,000 kg" to "1000", "1 million kg" to "1e6", "Moon mass" to "7.342e22").forEach { (label, value) ->
                                PlacementChoice(label, state.massKgText == value, !state.committing,
                                    "lab.placement.mass.$value") { actions.onMassChange(value) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.sourceGravity, onCheckedChange = actions.onSourceGravityChange,
                                enabled = !state.committing,
                                modifier = Modifier.size(48.dp).testTag(LabPlacementTags.GRAVITY)
                                    .semantics { contentDescription = "Probe attracts other bodies" })
                            Text("Attracts other bodies", color = LabInk)
                        }
                        Text(if (state.sourceGravity) "This mass changes the motion of Earth, Moon and other probes. The preview recalculates those forces."
                            else "Feels gravity without perturbing other bodies. Mass is still saved. Turn attraction on for massive-body experiments.",
                            color = LabMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        Text("Kilograms; scientific notation such as 1e20 is accepted. Radius stays 2 m until edited; mass does not imply realistic density.",
                            color = LabMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    } else if (section == PlacementSection.POSITION) {''')
host=kt+'StageFirstRuntimeExperience.kt'
replace(host,'                        headingDegreesText = draft.headingDegreesText,','                        headingDegreesText = draft.headingDegreesText,\n                        massKgText = draft.massKgText,\n                        sourceGravity = draft.sourceGravity,')
replace(host,'                        onHeadingChange = { labInteraction.changeDraft(draft.copy(headingDegreesText = it)) },','                        onHeadingChange = { labInteraction.changeDraft(draft.copy(headingDegreesText = it)) },\n                        onMassChange = { labInteraction.changeDraft(draft.copy(massKgText = it)) },\n                        onSourceGravityChange = { labInteraction.changeDraft(draft.copy(sourceGravity = it)) },')
# Keep the named-source and full state through host changes, rather than resetting mass.
# Existing changePlacementHost uses copy; tests below exercise immutable draft behaviour.

write('clients/android/app/src/test/kotlin/com/sednalabs/solarlab/LabProbeMassTest.kt', '''package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import org.junit.Assert.*
import org.junit.Test

class LabProbeMassTest {
    private val earth = RuntimeBodyState("earth", RuntimeBodyClass.Planet,
        5.972e24, 6_371_000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, sourceMassKg = 5.972e24)
    private fun draft() = LabProbeDraft(earth, earthMoonAuthoringPlane(earth, null), "mass-probe", 0.0)
    @Test fun physicalMassAndSourceGravityAreIndependent() {
        val small = requireNotNull(draft().candidate())
        val massive = requireNotNull(draft().copy(massKgText = "1e22").candidate())
        assertEquals(1e22, massive.massKg, 0.0)
        assertEquals(0.0, massive.sourceMassKg, 0.0)
        assertEquals(small.velocityX, massive.velocityX, 0.0)
        assertEquals(small.velocityY, massive.velocityY, 0.0)
        val active = requireNotNull(draft().copy(massKgText="1e22", sourceGravity=true).candidate())
        assertEquals(active.massKg, active.sourceMassKg, 0.0)
        assertTrue(labLength(active.labVelocity()) > labLength(massive.labVelocity()))
    }
    @Test fun invalidAndZeroAttractingMassNeverCreateCandidates() {
        for (mass in listOf("", "-1", "NaN", "Infinity", "1e309")) {
            assertNotNull(draft().copy(massKgText=mass).validationError)
            assertNull(draft().copy(massKgText=mass).candidate())
        }
        assertNotNull(draft().copy(massKgText="0").candidate())
        assertNull(draft().copy(massKgText="0",sourceGravity=true).candidate())
    }
    @Test fun existingProbeEditsIncludePropertiesAndKeepUneditedSourceRatio() {
        val body = requireNotNull(draft().copy(massKgText="1000").candidate()).copy(sourceMassKg=500.0)
        val session = LabBodyEditorSession(body, earth, false, setOf("earth", "mass-probe"))
        val original = session.initialDraft()
        val unchanged = labDraftCommand(original,session) as RuntimeCommand.SetBodyProperties
        assertEquals(500.0,unchanged.sourceMassKg,0.0)
        assertEquals(body.positionX,unchanged.positionX,0.0)
        val edited = labDraftCommand(original.copy(massKg="1e20"),session) as RuntimeCommand.SetBodyProperties
        assertEquals(1e20,edited.massKg,0.0); assertEquals(1e20,edited.sourceMassKg,0.0)
        val passive = labDraftCommand(original.copy(sourceGravity=false),session) as RuntimeCommand.SetBodyProperties
        assertEquals(0.0,passive.sourceMassKg,0.0)
        assertNull(labDraftCommand(original.copy(massKg="NaN"),session))
    }
}
''')
# Update existing tests which intentionally asserted the former editor command type.
p=Path('clients/android/app/src/test/kotlin/com/sednalabs/solarlab/LabBodyAuthoringTest.kt')
p.write_text(p.read_text().replace('as RuntimeCommand.SetBodyKinematics','as RuntimeCommand.SetBodyProperties').replace('is RuntimeCommand.SetBodyKinematics','is RuntimeCommand.SetBodyProperties'))

write('docs/probe-mass-authoring.md', '''# Probe mass and atomic body editing

The placement panel now has Position, Motion and Mass sections. New probes default
to 1,000 kg and do not exert gravity. Set kilograms (scientific notation accepted)
and explicitly enable **Attracts other bodies** for gravitational backreaction.
The same controls are available for existing probes in **Edit mass & motion**.

Physical mass and gravitational source mass remain distinct authoritative f64
fields. With attraction off, source mass is zero; the mass does not change the
probe's free-fall acceleration. With attraction on, an explicitly edited mass is
also its source mass. A kinematics-only edit preserves a previously saved unequal
physical/source-mass ratio. Neither class nor identity/history is replaced.

Circular starting speed includes the new probe's source mass in the relative
two-body estimate. It is not a stable-orbit guarantee in the full multi-body
model. Large masses may substantially alter Earth and Moon. Radius remains 2 m
at placement and is independently editable; presets do not assert realistic
material density, relativity, or a collision model.

`SetBodyProperties` is additive ABI command 12, using existing command fields.
Validation rejects nonfinite/negative masses, nonpositive radius, invalid vectors
and overflowing proposed invariants before history or ordinals change. One
accepted command updates mass/source/radius/position/velocity atomically and
refreshes source-aware solver selection. Saves retain both masses unchanged.

Regression coverage includes invalid-edit atomicity, saved-state round trips,
passive-mass invariance, real Earth backreaction, placement values and bit-exact
unchanged editor fields. Native acceptance and exact-source CI are separate from
these source-level changes; no phone-performance claim or new release is implied.

Delivery: Ops w18967 under w18944; feature PR237. Use remote commits and Ops
checkpoints, not conversation state, as the recovery record.
''')
print('Applied probe-mass authoring patch. Product files only; exact-head qualification remains required.')
