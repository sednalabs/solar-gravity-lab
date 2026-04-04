package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine
import com.graciousgazelles.solarlab.core.simulation.SolarSystemScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabSessionPlaybackSubstepPolicyTest {

    @Test
    fun `ordinary playback intervals keep existing one-hour cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 3_600.0,
            collisionMode = CollisionMode.NONE,
        )

        assertEquals(3_600.0, effective, 0.0)
    }

    @Test
    fun `large playback intervals raise max substep adaptively`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 86_400.0,
            collisionMode = CollisionMode.NONE,
        )

        assertEquals(7_200.0, effective, 0.0)
    }

    @Test
    fun `very large playback intervals clamp at bounded upper cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 864_000.0,
            collisionMode = CollisionMode.NONE,
        )

        assertEquals(43_200.0, effective, 0.0)
    }

    @Test
    fun `all collision-enabled playback modes keep conservative one-hour cap`() {
        listOf(
            CollisionMode.MERGE,
            CollisionMode.ELASTIC,
            CollisionMode.FRAGMENTATION,
        ).forEach { collisionMode ->
            val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
                totalSeconds = 864_000.0,
                collisionMode = collisionMode,
            )

            assertEquals(3_600.0, effective, 0.0)
        }
    }

    @Test
    fun `coarse playback substeps drift moon host-relative state versus one-hour baseline`() {
        val playbackTickSeconds = 864_000.0
        val playbackTicks = 3
        val baselineMaxSubstepSeconds = 3_600.0
        val playbackMaxSubstepSeconds = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = playbackTickSeconds,
            collisionMode = CollisionMode.NONE,
        )

        val start = SolarSystemScenarios.defaultLabScenario(
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )
        val baseline = advanceSnapshot(
            start = start,
            ticks = playbackTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = baselineMaxSubstepSeconds,
        )
        val playback = advanceSnapshot(
            start = start,
            ticks = playbackTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = playbackMaxSubstepSeconds,
        )

        val baselineMoon = baseline.bodies.first { it.id == "moon" }
        val baselineEarth = baseline.bodies.first { it.id == "earth" }
        val playbackMoon = playback.bodies.first { it.id == "moon" }
        val playbackEarth = playback.bodies.first { it.id == "earth" }
        val baselineMoonFromEarth = baselineMoon.positionM - baselineEarth.positionM
        val playbackMoonFromEarth = playbackMoon.positionM - playbackEarth.positionM
        val hostRelativeDriftMeters = (playbackMoonFromEarth - baselineMoonFromEarth).magnitude()

        assertEquals(43_200.0, playbackMaxSubstepSeconds, 0.0)
        assertTrue(
            "Expected measurable moon/earth drift from playback coarse substeps, drift=$hostRelativeDriftMeters m",
            hostRelativeDriftMeters > 100_000.0,
        )
    }

    private fun advanceSnapshot(
        start: SimulationSnapshot,
        ticks: Int,
        tickSeconds: Double,
        maxSubstepSeconds: Double,
    ): SimulationSnapshot {
        val engine = SimulationEngine(start, SimulationConfig(collisionMode = CollisionMode.NONE))
        repeat(ticks) {
            var remaining = tickSeconds
            while (remaining > 0.0) {
                val substep = remaining.coerceAtMost(maxSubstepSeconds)
                engine.step(substep)
                remaining -= substep
            }
        }
        return engine.snapshot()
    }
}
