package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class LabEditorPreservationTest {
    @Test fun equivalentDisplayInputsPreserveNativePropertiesInTheActualCommand() {
        val earth = RuntimeBodyState(
            bodyId = "earth", bodyClass = RuntimeBodyClass.Planet,
            massKg = 5.972e24, sourceMassKg = 5.972e24, radiusM = 6371000.0,
            positionX = 1.2e11, positionY = 0.0, positionZ = 0.0,
            velocityX = 0.0, velocityY = 29784.0, velocityZ = 0.0,
        )
        val probe = earth.copy(
            bodyId = "edited-probe", bodyClass = RuntimeBodyClass.Spacecraft,
            massKg = 1000.0, sourceMassKg = 500.0, radiusM = 2.0,
            positionX = 1.234567890123456e11, velocityY = 29784.123456789,
        )
        val session = LabBodyEditorSession(probe, earth, false, setOf("earth", "edited-probe"))
        val initial = session.initialDraft()
        fun notation(value: String) = BigDecimal(value).toPlainString() + "e0"
        val reformatted = initial.copy(
            massKg = "1e3",
            positionKm = initial.positionKm.map(::notation),
            velocityKmPerSecond = initial.velocityKmPerSecond.map(::notation),
        )
        val command = labDraftCommand(reformatted, session) as RuntimeCommand.SetBodyProperties
        assertEquals(1000.0, command.massKg, 0.0)
        assertEquals(500.0, command.sourceMassKg, 0.0)
        assertEquals(probe.positionX.toBits(), command.positionX.toBits())
        assertEquals(probe.positionY.toBits(), command.positionY.toBits())
        assertEquals(probe.positionZ.toBits(), command.positionZ.toBits())
        assertEquals(probe.velocityX.toBits(), command.velocityX.toBits())
        assertEquals(probe.velocityY.toBits(), command.velocityY.toBits())
        assertEquals(probe.velocityZ.toBits(), command.velocityZ.toBits())
        val passive = labDraftCommand(initial.copy(sourceGravity = false), session) as RuntimeCommand.SetBodyProperties
        assertEquals(0.0, passive.sourceMassKg, 0.0)
    }
}
