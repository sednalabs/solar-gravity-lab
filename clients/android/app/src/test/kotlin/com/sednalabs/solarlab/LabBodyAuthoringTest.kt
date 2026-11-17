package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabBodyAuthoringTest {
    private val earth = RuntimeBodyState(
        bodyId = "earth", bodyClass = RuntimeBodyClass.Planet,
        massKg = 5.972e24, sourceMassKg = 5.972e24, radiusM = 6_371_000.0,
        positionX = 1.481238741298e11, positionY = -2.738412781273e10, positionZ = 978.319812,
        velocityX = 7_639.381238791, velocityY = 29_421.97219832, velocityZ = -0.5123812,
    )
    private val moon = RuntimeBodyState(
        bodyId = "moon", bodyClass = RuntimeBodyClass.Moon,
        massKg = 7.342e22, sourceMassKg = 7.342e22, radiusM = 1_737_400.0,
        positionX = earth.positionX + 371_298_771.234567,
        positionY = earth.positionY + 81_234_591.912345,
        positionZ = earth.positionZ - 27_123_576.91238,
        velocityX = earth.velocityX - 321.123456789,
        velocityY = earth.velocityY + 987.987654321,
        velocityZ = earth.velocityZ + 17.123456789,
    )

    @Test
    fun untouchedEditorFieldsPreserveAuthoritativeDoubleBits() {
        val session = LabBodyEditorSession(moon, earth, false, setOf("earth", "moon"))
        val command = labDraftCommand(session.initialDraft(), session) as RuntimeCommand.SetBodyProperties
        assertEquals(moon.positionX.toBits(), command.positionX.toBits())
        assertEquals(moon.positionY.toBits(), command.positionY.toBits())
        assertEquals(moon.positionZ.toBits(), command.positionZ.toBits())
        assertEquals(moon.velocityX.toBits(), command.velocityX.toBits())
        assertEquals(moon.velocityY.toBits(), command.velocityY.toBits())
        assertEquals(moon.velocityZ.toBits(), command.velocityZ.toBits())
    }

    @Test
    fun editingOneEarthRelativeComponentPreservesTheOtherFive() {
        val session = LabBodyEditorSession(moon, earth, false, setOf("earth", "moon"))
        val draft = session.initialDraft().copy(velocityKmPerSecond = listOf("0.125", "0.987987654321", "0.017123456789"))
        // Only replace X in the real initial list so the other values follow their exact-state path.
        val adjusted = draft.copy(velocityKmPerSecond = session.initialDraft().velocityKmPerSecond.toMutableList().also { it[0] = "0.125" })
        val command = labDraftCommand(adjusted, session) as RuntimeCommand.SetBodyProperties
        assertEquals(earth.velocityX + 125.0, command.velocityX, 0.0)
        assertEquals(moon.velocityY.toBits(), command.velocityY.toBits())
        assertEquals(moon.velocityZ.toBits(), command.velocityZ.toBits())
        assertEquals(moon.positionX.toBits(), command.positionX.toBits())
    }

    @Test
    fun probeInheritsEarthMotionAndStartsAsNonPerturbing() {
        val seed = labProbeSeed(earth, setOf("earth", "moon", "probe-1"))
        val session = LabBodyEditorSession(seed, earth, true, setOf("earth", "moon", "probe-1"))
        val command = labDraftCommand(session.initialDraft(), session) as RuntimeCommand.SpawnBody
        assertEquals("probe-2", command.bodyId)
        assertEquals(earth.positionX + earth.radiusM + 800_000.0, command.positionX, 0.0)
        assertEquals(earth.velocityX, command.velocityX, 0.0)
        assertTrue(command.velocityY > earth.velocityY)
        assertEquals(0.0, command.sourceMassKg, 0.0)
        val gravitating = labDraftCommand(session.initialDraft().copy(sourceGravity = true), session) as RuntimeCommand.SpawnBody
        assertEquals(gravitating.massKg, gravitating.sourceMassKg, 0.0)
    }

    @Test
    fun invalidVectorsAndDuplicateNamesCannotProduceACommand() {
        val seed = labProbeSeed(earth, setOf("earth", "moon"))
        val session = LabBodyEditorSession(seed, earth, true, setOf("earth", "moon"))
        val draft = session.initialDraft()
        listOf("NaN", "Infinity", "1e308", "", "oops").forEach { invalid ->
            val invalidDraft = draft.copy(positionKm = listOf(invalid, "0", "0"))
            assertNotNull(labDraftValidation(invalidDraft, session))
            assertNull(labDraftCommand(invalidDraft, session))
        }
        assertNull(labDraftCommand(draft.copy(name = "EARTH"), session))
        assertNull(labDraftCommand(draft.copy(massKg = "-1"), session))
    }
}
