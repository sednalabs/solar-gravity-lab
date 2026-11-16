package com.sednalabs.solarlab

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
