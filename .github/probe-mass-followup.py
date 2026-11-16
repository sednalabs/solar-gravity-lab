#!/usr/bin/env python3
from pathlib import Path
import re

def replace(path, old, new, count=1):
    p=Path(path);s=p.read_text();assert s.count(old)==count,(path,s.count(old),old[:120]);p.write_text(s.replace(old,new))

def write(path, text):
    p=Path(path);assert not p.exists(),path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)

kt='clients/android/app/src/'
t=kt+'test/kotlin/com/sednalabs/solarlab/LabProbeMassTest.kt'
replace(t,'''    private val earth = RuntimeBodyState("earth", RuntimeBodyClass.Planet,
        5.972e24, 6_371_000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, sourceMassKg = 5.972e24)''', '''    private val earth = RuntimeBodyState(
        bodyId="earth", bodyClass=RuntimeBodyClass.Planet,
        massKg=5.972e24, sourceMassKg=5.972e24, radiusM=6_371_000.0,
        positionX=0.0, positionY=0.0, positionZ=0.0,
        velocityX=0.0, velocityY=0.0, velocityZ=0.0,
    )''')
# Use the invariant result already computed during preflight, avoiding a second
# O(N^2) reduction for every accepted property edit. No global cache is introduced.
rt='engine/runtime/src/lib.rs'
replace(rt,'        if let WorldCommand::SetBodyProperties {\n            body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps,\n        } = &command {\n            body_properties::validate(', '        let edited_invariants = if let WorldCommand::SetBodyProperties {\n            body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps,\n        } = &command {\n            Some(body_properties::validate(')
replace(rt,'                *source_mass_kg, *radius_m, *position_m, *velocity_mps,\n            )?;\n        }\n        let mut events', '                *source_mass_kg, *radius_m, *position_m, *velocity_mps,\n            )?)\n        } else { None };\n        let mut events')
replace(rt,'                body.velocity_mps = *velocity_mps;\n                branch.world.invariants = compute_world_invariants(&branch.world.bodies);\n                push_trail_sample(&mut branch.world.trail_history_by_body,', '                body.velocity_mps = *velocity_mps;\n                branch.world.invariants = edited_invariants.expect("validated property invariants");\n                push_trail_sample(&mut branch.world.trail_history_by_body,')
props='engine/runtime/src/body_properties.rs'
replace(props,') -> Result<(), RuntimeError> {',') -> Result<PhysicsInvariants, RuntimeError> {')
replace(props,'    Ok(())\n}', '    Ok(inv)\n}')

vert='render/android-vulkan/src/main/shaders/solarlab/density_point.vert'
replace(vert,'layout(location = 0) out vec4 vColor;', 'layout(location = 0) out vec4 vColor;\nlayout(location = 1) flat out float vPointSizePx;')
replace(vert,'        gl_PointSize = 1.0;','        gl_PointSize = 1.0;\n        vPointSizePx = 1.0;')
replace(vert,'    gl_PointSize = clamp(weight, 1.0, max(uScene.viewport.z, 1.0));', '''    // A one-pixel radial haze can fade its only sample away as its centre
    // crosses the pixel grid. Keep a small readable screen-space footprint;
    // devices without largePoints retain a real one-pixel fallback.
    gl_PointSize = clamp(max(weight, 3.0), 1.0, max(uScene.viewport.z, 1.0));
    vPointSizePx = gl_PointSize;''')
frag='render/android-vulkan/src/main/shaders/solarlab/density_point.frag'
replace(frag,'layout(location = 0) in vec4 vColor;', 'layout(location = 0) in vec4 vColor;\nlayout(location = 1) flat in float vPointSizePx;')
replace(frag,'void main() {','''void main() {
    // A 1px point cannot represent a radial mask. Applying the haze to its
    // sole sample made it blink/disappear at fractional pixel coordinates.
    if (vPointSizePx <= 2.0) {
        outColor = vColor;
        return;
    }''')

native=kt+'androidTest/kotlin/com/sednalabs/solarlab/LabProbeMassInstrumentationTest.kt'
write(native,'''package com.sednalabs.solarlab

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native/JNI proof, not a fake-facade or constructor-only mass test. */
@RunWith(AndroidJUnit4::class)
class LabProbeMassInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val facade get() = composeRule.activity.runtimeFacadeForTesting

    @Test fun massDraftSurvivesRecreationThenEditsAndReopensTheNativeBody() {
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon"); facade.applyCommand(RuntimeCommand.PausePlayback) }
        composeRule.waitUntil(20_000) {
            facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" &&
                runCatching { composeRule.onNodeWithTag(LabTags.ADD_PROBE).assertIsEnabled() }.isSuccess
        }
        val count = requireNotNull(facade.uiState.value.snapshot).bodyCount
        composeRule.onNodeWithTag(LabTags.ADD_PROBE).performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(LabPlacementTags.SECTION_BODY).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(LabPlacementTags.SECTION_BODY).performClick()
        composeRule.onNodeWithTag(LabPlacementTags.MASS).performTextReplacement("-1")
        closeSoftKeyboard()
        composeRule.onNodeWithTag(LabPlacementTags.CREATE).assertIsNotEnabled()
        composeRule.onNodeWithTag(LabPlacementTags.MASS).performTextReplacement("1e22")
        closeSoftKeyboard()
        composeRule.onNodeWithTag(LabPlacementTags.GRAVITY).performScrollTo().performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(20_000) {
            runCatching { composeRule.onNodeWithTag(LabPlacementTags.MASS).assertTextContains("1e22") }.isSuccess
        }
        capture("01-mass-draft.png")
        composeRule.waitUntil(30_000) { runCatching { composeRule.onNodeWithTag(LabPlacementTags.CREATE).assertIsEnabled() }.isSuccess }
        composeRule.onNodeWithTag(LabPlacementTags.CREATE).performClick()
        composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.bodyCount == count + 1 && facade.lastProbeCommit.value != null }
        val id = requireNotNull(facade.lastProbeCommit.value).bodyId
        fun body() = runBlocking { requireNotNull(facade.inspectBody(id)) }
        assertEquals(1e22, body().massKg, 0.0)
        assertEquals(1e22, body().sourceMassKg, 0.0)
        val originalPosition = listOf(body().positionX, body().positionY, body().positionZ)
        composeRule.onNodeWithTag(LabTags.INSPECT).performClick()
        composeRule.waitUntil(20_000) { runCatching { composeRule.onNodeWithTag(LabTags.EDIT).assertIsEnabled() }.isSuccess }
        composeRule.onNodeWithTag(LabTags.EDIT).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_MASS_FIELD).performScrollTo().performTextReplacement("2e22")
        closeSoftKeyboard()
        capture("02-edit-existing-mass.png")
        composeRule.onNodeWithTag(LabTags.EDIT_COMMIT).performClick()
        composeRule.waitUntil(20_000) { body().massKg == 2e22 }
        assertEquals(2e22, body().sourceMassKg, 0.0)
        assertEquals(originalPosition,listOf(body().positionX,body().positionY,body().positionZ))
        val savedName = "Mass proof ${System.currentTimeMillis()}"
        runBlocking { facade.saveExperiment(savedName) }
        composeRule.waitUntil(20_000) { facade.savedExperiments.value.any { it.name == savedName } }
        val saved = facade.savedExperiments.value.single { it.name == savedName }
        val b = body()
        runBlocking { facade.applyCommand(RuntimeCommand.SetBodyProperties(id,5000.0,0.0,b.radiusM,
            b.positionX,b.positionY,b.positionZ,b.velocityX,b.velocityY,b.velocityZ)) }
        composeRule.waitUntil(20_000) { body().massKg == 5000.0 }
        assertEquals(0.0,body().sourceMassKg,0.0)
        runBlocking { facade.loadExperiment(saved.id) }
        composeRule.waitUntil(20_000) { facade.experimentStatus.value.openedId == saved.id }
        assertEquals(2e22,body().massKg,0.0); assertEquals(2e22,body().sourceMassKg,0.0)
        assertEquals(count+1,facade.uiState.value.snapshot!!.bodyCount)
        capture("03-reopened-mass.png")
    }

    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(i.uiAutomation.takeScreenshot())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS + "/solarlab-validation/lab-evidence/probe-mass/")
        }
        val resolver=i.targetContext.contentResolver
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        try { requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }
}
''')
smoke='.github/scripts/run_validation_android_shell_smoke.sh'
s=Path(smoke).read_text()
pattern=r'^(\s*)("?)com\.sednalabs\.solarlab\.EarthMoonLabJourneyInstrumentationTest("?)$'
matches=list(re.finditer(pattern,s,re.M));assert len(matches)==1,(smoke,len(matches))
s=re.sub(pattern,lambda m:m[0]+'\n'+m[1]+m[2]+'com.sednalabs.solarlab.LabProbeMassInstrumentationTest'+m[3],s,flags=re.M)
Path(smoke).write_text(s)

p=Path('docs/probe-mass-authoring.md');p.write_text(p.read_text()+'''

Additional refinement: accepted body edits reuse their already-validated invariant
result, avoiding a second all-pairs reduction. No approximate result is cached.
Native qualification adds an actual Mass-panel/create/edit/save/reopen journey,
invalid UI input and draft recreation. Distant density points get a readable
minimum screen footprint and an explicit 1px hardware fallback, instead of fading
away their only raster sample. Native projected-pixel assertions remain unchanged.
''')
print('Follow-up product patch applied; native and JVM qualification still required.')
