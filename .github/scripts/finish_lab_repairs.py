"""One-shot, checked source recovery for PR237. No fuzzy or whole-file overwrite."""
from pathlib import Path
import subprocess

BASE = '649bf213aaa0ac2f44071219781c19f246cc4f08'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD^'], text=True).strip() == BASE
pending = {}
def replace(path, before, after):
    text = pending.get(path, Path(path).read_text())
    if text.count(before) != 1:
        raise RuntimeError(f'{path}: expected exactly one original fragment; refusing recovery')
    pending[path] = text.replace(before, after, 1)

p = 'clients/android/app/src/main/kotlin/com/sednalabs/solarlab/LabExperimentDialogs.kt'
replace(p, '''    val p = draft.positionKm.map { it.toDouble() * 1_000.0 }
    val v = draft.velocityKmPerSecond.map { it.toDouble() * 1_000.0 }
    // Untouched fields retain their exact f64 bits instead of taking a display-unit round trip.
    val x = if (draft.positionKm[0] == initial.positionKm[0]) session.body.positionX else p[0] + (reference?.positionX ?: 0.0)
    val y = if (draft.positionKm[1] == initial.positionKm[1]) session.body.positionY else p[1] + (reference?.positionY ?: 0.0)
    val z = if (draft.positionKm[2] == initial.positionKm[2]) session.body.positionZ else p[2] + (reference?.positionZ ?: 0.0)
    val vx = if (draft.velocityKmPerSecond[0] == initial.velocityKmPerSecond[0]) session.body.velocityX else v[0] + (reference?.velocityX ?: 0.0)
    val vy = if (draft.velocityKmPerSecond[1] == initial.velocityKmPerSecond[1]) session.body.velocityY else v[1] + (reference?.velocityY ?: 0.0)
    val vz = if (draft.velocityKmPerSecond[2] == initial.velocityKmPerSecond[2]) session.body.velocityZ else v[2] + (reference?.velocityZ ?: 0.0)''', '''    // Equivalent spellings keep native bits; only real numeric edits convert display units.
    val x = labEditedScalar(draft.positionKm[0], initial.positionKm[0], session.body.positionX, 1_000.0, reference?.positionX ?: 0.0)
    val y = labEditedScalar(draft.positionKm[1], initial.positionKm[1], session.body.positionY, 1_000.0, reference?.positionY ?: 0.0)
    val z = labEditedScalar(draft.positionKm[2], initial.positionKm[2], session.body.positionZ, 1_000.0, reference?.positionZ ?: 0.0)
    val vx = labEditedScalar(draft.velocityKmPerSecond[0], initial.velocityKmPerSecond[0], session.body.velocityX, 1_000.0, reference?.velocityX ?: 0.0)
    val vy = labEditedScalar(draft.velocityKmPerSecond[1], initial.velocityKmPerSecond[1], session.body.velocityY, 1_000.0, reference?.velocityY ?: 0.0)
    val vz = labEditedScalar(draft.velocityKmPerSecond[2], initial.velocityKmPerSecond[2], session.body.velocityZ, 1_000.0, reference?.velocityZ ?: 0.0)''')
replace(p, '''        val source = if (draft.massKg == initial.massKg && draft.sourceGravity == initial.sourceGravity)
            session.body.sourceMassKg else if (draft.sourceGravity) mass else 0.0''', '''        val source = labEditedSourceMass(draft.massKg, initial.massKg,
            draft.sourceGravity, initial.sourceGravity, session.body.sourceMassKg)''')

p = 'clients/android/app/src/androidTest/kotlin/com/sednalabs/solarlab/StageFirstSearchFocusInstrumentationTest.kt'
replace(p, 'import androidx.test.ext.junit.runners.AndroidJUnit4', 'import androidx.test.ext.junit.runners.AndroidJUnit4\nimport androidx.test.espresso.IdlingPolicies\nimport java.util.concurrent.TimeUnit\nimport android.util.Log\nimport org.junit.Before')
replace(p, '    @Test\n    fun searchFocus_updatesVisibleSelectionCard()', '''    @Before fun boundIdleDiagnostics() {
        IdlingPolicies.setMasterPolicyTimeout(45, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(30, TimeUnit.SECONDS)
    }

    @Test
    fun searchFocus_updatesVisibleSelectionCard()''')
replace(p, '''        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("halley")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performClick().performTextInput("comet")''', '''        // Filter before asking for Halley: an unfiltered, scroll-clipped
        // catalogue need not expose an offscreen result in its semantics tree.
        // The filtered inclusion/exclusion assertions below remain mandatory.
        Log.i("SolarLabSearchProof", "Search field ready; entering comet filter")
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performClick().performTextInput("comet")
        Log.i("SolarLabSearchProof", "Comet filter entered")''')

for path, text in pending.items():
    Path(path).write_text(text)
    print('RECOVERED', path)
subprocess.run(['git', 'diff', '--check'], check=True)
