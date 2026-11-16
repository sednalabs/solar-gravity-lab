package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class LabSpatialExperimentTest {
    private val earth = RuntimeBodyState("earth", RuntimeBodyClass.Planet,
        5.972e24, 5.972e24, 6_371_000.0, 1.49e11, -2.4e10, 3.1e9, 2_000.0, 29_000.0, -700.0)
    private val moon = earth.copy(bodyId = "moon", bodyClass = RuntimeBodyClass.Moon,
        positionX = earth.positionX + 340_000_000.0, positionY = earth.positionY + 120_000_000.0,
        positionZ = earth.positionZ + 60_000_000.0, velocityX = earth.velocityX - 400.0,
        velocityY = earth.velocityY + 900.0, velocityZ = earth.velocityZ + 300.0)
    private val plane = earthMoonAuthoringPlane(earth, moon)

    @Test fun placementUsesDeclaredTiltedPlaneAndInheritsHostVelocity() {
        val draft = LabProbeDraft(earth, plane, "probe-1", 0.0, altitudeKmText = "1500", azimuthRadians = 2.0)
        val body = requireNotNull(draft.candidate())
        val position = body.labPosition() - earth.labPosition()
        val velocity = body.labVelocity() - earth.labVelocity()
        assertEquals(earth.radiusM + 1_500_000.0, labLength(position), 0.0001)
        assertEquals(0.0, labDot(position, plane.normal), 0.0001)
        assertEquals(0.0, labDot(velocity, plane.normal), 1.0e-8)
        assertEquals(0.0, labDot(position, velocity) / labLength(position), 1.0e-8)
        assertTrue(kotlin.math.abs(plane.normal.x) + kotlin.math.abs(plane.normal.y) > 0.01)
        assertEquals(0.0, body.sourceMassKg, 0.0)
    }

    @Test fun arbitraryTouchChangesPositionAndAltitudeLockPreservesTheChosenRadius() {
        val draft = LabProbeDraft(earth, plane, "probe-1", 0.0, altitudeKmText = "1500")
        val point = earth.labPosition() + plane.axisV * (earth.radiusM + 3_000_000.0)
        val free = draft.atWorldPoint(point)
        assertEquals(3_000_000.0, free.altitudeM!!, 0.001)
        assertEquals(PI / 2.0, free.azimuthRadians, 1.0e-10)
        val locked = draft.copy(altitudeLocked = true).atWorldPoint(point)
        assertEquals(1_500_000.0, locked.altitudeM!!, 0.0)
        assertEquals(PI / 2.0, locked.azimuthRadians, 1.0e-10)
    }

    @Test fun customVelocityDoesNotChangeWhenMovingTheProbe() {
        val draft = LabProbeDraft(earth, plane, "probe-1", 0.0).withMotion(LabPlacementMotion.CUSTOM)
            .copy(speedKmPerSecondText = "7.5", headingDegreesText = "37")
        val next = draft.atWorldPoint(earth.labPosition() - plane.axisU * (earth.radiusM + 2_000_000.0))
        assertEquals(draft.relativeVelocityMps(), next.relativeVelocityMps())
        val released = draft.copy(motion = LabPlacementMotion.RELEASE).candidate()!!
        assertEquals(earth.labVelocity(), released.labVelocity())
    }

    @Test fun velocityHandleChangesMotionWithoutMovingTheDraft() {
        val draft = LabProbeDraft(earth, plane, "probe-1", 0.0)
        val next = draft.withVelocityPoint(draft.positionM!! + plane.axisU * 10_000.0, 0.75)
        assertEquals(draft.positionM, next.positionM)
        assertEquals(LabPlacementMotion.CUSTOM, next.motion)
        assertEquals(7_500.0, labLength(next.relativeVelocityMps()!!), 1.0e-8)
    }

    @Test fun invalidAndInsideSurfaceDraftsCannotCreateBodies() {
        val draft = LabProbeDraft(earth, plane, "probe-1", 0.0)
        listOf("NaN", "Infinity", "", "-100", "0", "1e309").forEach { altitude ->
            assertNull(draft.copy(altitudeKmText = altitude).candidate())
        }
        assertNull(draft.copy(motion = LabPlacementMotion.CUSTOM, speedKmPerSecondText = "-1").candidate())
        assertNull(draft.copy(motion = LabPlacementMotion.CUSTOM, headingDegreesText = "NaN").candidate())
        assertEquals(draft, draft.atWorldPoint(earth.labPosition()))
    }
}
