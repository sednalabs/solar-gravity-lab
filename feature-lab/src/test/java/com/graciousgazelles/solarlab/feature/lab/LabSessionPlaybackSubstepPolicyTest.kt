package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine
import com.graciousgazelles.solarlab.core.simulation.SolarSystemScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

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

        assertEquals(32_400.0, effective, 0.0)
    }

    @Test
    fun `highest playback preset keeps worst-case tick fanout bounded`() {
        val worstCaseTickSeconds = PlaybackSpeedPreset.MONTH_PER_SECOND.simSecondsPerRealSecond * 0.25
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = worstCaseTickSeconds,
            collisionMode = CollisionMode.NONE,
        )
        val substepCount = ceil(worstCaseTickSeconds / effective).toInt()

        assertEquals(32_400.0, effective, 0.0)
        assertTrue(
            "Expected worst-case month playback fanout to stay within 20 substeps, count=$substepCount",
            substepCount <= 20,
        )
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
    fun `playback policy drift is lower than coarse legacy cap versus one-hour baseline`() {
        val playbackTickSeconds = 864_000.0
        val playbackTicks = 3
        val baselineMaxSubstepSeconds = 3_600.0
        val policyMaxSubstepSeconds = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = playbackTickSeconds,
            collisionMode = CollisionMode.NONE,
        )
        val coarseLegacyMaxSubstepSeconds = 43_200.0

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
        val coarseLegacy = advanceSnapshot(
            start = start,
            ticks = playbackTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = coarseLegacyMaxSubstepSeconds,
        )
        val policy = advanceSnapshot(
            start = start,
            ticks = playbackTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = policyMaxSubstepSeconds,
        )

        val baselineMoon = baseline.bodies.first { it.id == "moon" }
        val baselineEarth = baseline.bodies.first { it.id == "earth" }
        val coarseLegacyMoon = coarseLegacy.bodies.first { it.id == "moon" }
        val coarseLegacyEarth = coarseLegacy.bodies.first { it.id == "earth" }
        val policyMoon = policy.bodies.first { it.id == "moon" }
        val policyEarth = policy.bodies.first { it.id == "earth" }
        val baselineMoonFromEarth = baselineMoon.positionM - baselineEarth.positionM
        val coarseLegacyMoonFromEarth = coarseLegacyMoon.positionM - coarseLegacyEarth.positionM
        val policyMoonFromEarth = policyMoon.positionM - policyEarth.positionM
        val coarseLegacyDriftMeters = (coarseLegacyMoonFromEarth - baselineMoonFromEarth).magnitude()
        val policyDriftMeters = (policyMoonFromEarth - baselineMoonFromEarth).magnitude()

        assertEquals(32_400.0, policyMaxSubstepSeconds, 0.0)
        assertTrue(
            "Expected policy drift ($policyDriftMeters m) to stay below coarse legacy drift ($coarseLegacyDriftMeters m)",
            policyDriftMeters < coarseLegacyDriftMeters,
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
