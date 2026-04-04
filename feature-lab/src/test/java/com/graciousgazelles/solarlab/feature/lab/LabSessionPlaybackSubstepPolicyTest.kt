package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine
import com.graciousgazelles.solarlab.core.simulation.SolarSystemScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.abs

class LabSessionPlaybackSubstepPolicyTest {

    @Test
    fun `ordinary playback intervals keep existing one-hour cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 3_600.0,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.HOUR_PER_SECOND,
        )

        assertEquals(3_600.0, effective, 0.0)
    }

    @Test
    fun `large playback intervals raise max substep adaptively`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 86_400.0,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.DAY_PER_SECOND,
        )

        assertEquals(7_200.0, effective, 0.0)
    }

    @Test
    fun `very large non-high-speed intervals clamp at bounded upper cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 864_000.0,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.DAY_PER_SECOND,
        )

        assertEquals(32_400.0, effective, 0.0)
    }

    @Test
    fun `high-speed playback presets clamp with tighter cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 864_000.0,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )

        assertEquals(21_600.0, effective, 0.0)
    }

    @Test
    fun `highest playback preset keeps worst-case tick fanout bounded with high-speed cap`() {
        val worstCaseTickSeconds = PlaybackSpeedPreset.MONTH_PER_SECOND.simSecondsPerRealSecond * 0.25
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = worstCaseTickSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )
        val substepCount = ceil(worstCaseTickSeconds / effective).toInt()

        assertEquals(21_600.0, effective, 0.0)
        assertTrue(
            "Expected worst-case month playback fanout to stay within 30 substeps, count=$substepCount",
            substepCount <= 30,
        )
    }

    @Test
    fun `runtime tick planning uses high-speed substep cap for worst-case month-per-second delta`() {
        val simulatedTickSeconds = PlaybackSpeedPreset.MONTH_PER_SECOND.simSecondsPerRealSecond * 0.25
        val plan = LabSession.playbackSubstepPlan(
            totalSeconds = simulatedTickSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )

        val substepCount = ceil(plan.totalSeconds / plan.maxSubstepSeconds).toInt()

        assertEquals(21_600.0, plan.maxSubstepSeconds, 0.0)
        assertTrue(
            "Expected runtime planning to cap worst-case high-speed fanout within 30 substeps, count=$substepCount",
            substepCount <= 30,
        )
    }

    @Test
    fun `simulation advance budget caps high-speed tick work and defers the remainder`() {
        val simulatedTickSeconds = PlaybackSpeedPreset.MONTH_PER_SECOND.simSecondsPerRealSecond * 0.05
        val budget = LabSession.simulationAdvanceBudget(
            totalPendingSeconds = simulatedTickSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )

        assertEquals(10_800.0, budget.maxSubstepSeconds, 0.0)
        assertEquals(32_400.0, budget.secondsToAdvance, 0.0)
        assertEquals(simulatedTickSeconds - 32_400.0, budget.deferredSeconds, 1.0e-6)
        assertEquals(simulatedTickSeconds, budget.cappedPendingSeconds, 1.0e-6)
    }

    @Test
    fun `simulation advance budget bounds runaway backlog to a few render windows`() {
        val hugePendingSeconds = PlaybackSpeedPreset.MONTH_PER_SECOND.simSecondsPerRealSecond * 2.0
        val budget = LabSession.simulationAdvanceBudget(
            totalPendingSeconds = hugePendingSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )

        assertEquals(259_200.0, budget.cappedPendingSeconds, 0.0)
        assertEquals(64_800.0, budget.secondsToAdvance, 0.0)
        assertEquals(194_400.0, budget.deferredSeconds, 0.0)
    }

    @Test
    fun `repeated high-speed tick planning bounds backlog over sustained playback`() {
        val simSecondsPerTick = PlaybackSpeedPreset.MONTH_PER_SECOND.simSecondsPerRealSecond * 0.05
        var pendingSeconds = 0.0

        repeat(240) {
            val budget = LabSession.simulationAdvanceBudget(
                totalPendingSeconds = pendingSeconds + simSecondsPerTick,
                collisionMode = CollisionMode.NONE,
                playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
            )
            pendingSeconds = budget.deferredSeconds

            assertTrue(
                "Expected scheduler to cap sustained backlog, backlog=$pendingSeconds",
                pendingSeconds <= budget.secondsToAdvance * 3.0,
            )
            assertTrue(
                "Expected scheduler to keep advancing work each render tick",
                budget.secondsToAdvance > 0.0,
            )
        }
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
                playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
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
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
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

        assertEquals(21_600.0, policyMaxSubstepSeconds, 0.0)
        assertTrue(
            "Expected policy drift ($policyDriftMeters m) to stay below coarse legacy drift ($coarseLegacyDriftMeters m)",
            policyDriftMeters < coarseLegacyDriftMeters,
        )
    }

    @Test
    fun `playback policy keeps short-window moon-earth turning-angle jitter below coarse legacy baseline`() {
        // This is a host-relative playback proxy, not a full screen-space render-jank assertion.
        val playbackTickSeconds = 86_400.0
        val playbackWindowTicks = 6
        val fineMaxSubstepSeconds = 120.0
        val coarseLegacyMaxSubstepSeconds = 43_200.0
        val policyMaxSubstepSeconds = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = playbackTickSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )

        val start = SolarSystemScenarios.defaultLabScenario(
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )
        val baseline = hostRelativeTurningAngles(
            start = start,
            ticks = playbackWindowTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = fineMaxSubstepSeconds,
        )
        val policy = hostRelativeTurningAngles(
            start = start,
            ticks = playbackWindowTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = policyMaxSubstepSeconds,
        )
        val coarseLegacy = hostRelativeTurningAngles(
            start = start,
            ticks = playbackWindowTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = coarseLegacyMaxSubstepSeconds,
        )

        assertEquals(3_600.0, policyMaxSubstepSeconds, 0.0)
        assertEquals(playbackWindowTicks, baseline.size)
        assertEquals(playbackWindowTicks, policy.size)
        assertEquals(playbackWindowTicks, coarseLegacy.size)

        val policyMaxTurningError = maxTurningAngleError(policy, baseline)
        val coarseLegacyMaxTurningError = maxTurningAngleError(coarseLegacy, baseline)
        val improvement = coarseLegacyMaxTurningError - policyMaxTurningError

        assertTrue(
            "Expected policy turning-angle max-jitter (" +
                "$policyMaxTurningError rad) to be below coarse-legacy jitter (" +
                "$coarseLegacyMaxTurningError rad) with improvement $improvement rad",
            policyMaxTurningError < coarseLegacyMaxTurningError,
        )
    }

    @Test
    fun `host-relative short-window cap tightens legacy high-speed path and does not regress turning-angle jitter`() {
        val playbackTickSeconds = 86_400.0
        val playbackWindowTicks = 6
        val fineMaxSubstepSeconds = 120.0
        val legacyPolicyCap = legacyHighSpeedPolicyCap(playbackTickSeconds)
        val policyPlan = LabSession.playbackSubstepPlan(
            totalSeconds = playbackTickSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )
        val policyCap = policyPlan.maxSubstepSeconds
        val twoDayPlan = LabSession.playbackSubstepPlan(
            totalSeconds = 2.0 * playbackTickSeconds,
            collisionMode = CollisionMode.NONE,
            playbackSpeedPreset = PlaybackSpeedPreset.MONTH_PER_SECOND,
        )
        val start = SolarSystemScenarios.defaultLabScenario(
            includeSyntheticAsteroidBelt = false,
            includeSyntheticOortCloud = false,
        )
        val baseline = hostRelativeTurningAngles(
            start = start,
            ticks = playbackWindowTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = fineMaxSubstepSeconds,
        )
        val legacyPolicy = hostRelativeTurningAngles(
            start = start,
            ticks = playbackWindowTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = legacyPolicyCap,
        )
        val currentPolicy = hostRelativeTurningAngles(
            start = start,
            ticks = playbackWindowTicks,
            tickSeconds = playbackTickSeconds,
            maxSubstepSeconds = policyCap,
        )
        val legacyPolicyMaxTurningError = maxTurningAngleError(legacyPolicy, baseline)
        val currentPolicyMaxTurningError = maxTurningAngleError(currentPolicy, baseline)

        assertEquals(7_200.0, legacyPolicyCap, 0.0)
        assertEquals(playbackTickSeconds, policyPlan.totalSeconds, 0.0)
        assertEquals(3_600.0, policyCap, 0.0)
        assertEquals(14_400.0, twoDayPlan.maxSubstepSeconds, 0.0)
        assertTrue(
            "Expected host-relative cap to tighten legacy high-speed day-window path (policy=$policyCap legacy=$legacyPolicyCap)",
            policyCap < legacyPolicyCap,
        )
        assertTrue(
            "Expected tightened host-relative policy jitter (" +
                "$currentPolicyMaxTurningError rad) to not exceed legacy jitter (" +
                "$legacyPolicyMaxTurningError rad)",
            currentPolicyMaxTurningError <= legacyPolicyMaxTurningError,
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

    private fun hostRelativeTurningAngles(
        start: SimulationSnapshot,
        ticks: Int,
        tickSeconds: Double,
        maxSubstepSeconds: Double,
    ): List<Double> {
        val engine = SimulationEngine(start, SimulationConfig(collisionMode = CollisionMode.NONE))
        val hostRelativeVectors = ArrayList<Vector3d>(ticks + 1).apply {
            add(hostRelativeMoonFromEarth(engine.snapshot()))
        }

        repeat(ticks) {
            var remaining = tickSeconds
            while (remaining > 0.0) {
                val substep = remaining.coerceAtMost(maxSubstepSeconds)
                engine.step(substep)
                remaining -= substep
            }
            hostRelativeVectors.add(hostRelativeMoonFromEarth(engine.snapshot()))
        }

        return hostRelativeVectors.zipWithNext { previous, current ->
            turningAngle(previous, current)
        }
    }

    private fun maxTurningAngleError(
        actual: List<Double>,
        expected: List<Double>,
    ): Double = actual.zip(expected) { a, b -> abs(a - b) }.maxOrNull() ?: 0.0

    private fun legacyHighSpeedPolicyCap(totalSeconds: Double): Double {
        val adaptiveSubstep = totalSeconds / 12.0
        return adaptiveSubstep.coerceIn(3_600.0, 21_600.0)
    }

    private fun turningAngle(
        previous: Vector3d,
        current: Vector3d,
    ): Double {
        val denominator = previous.magnitude() * current.magnitude()
        if (denominator == 0.0) return 0.0
        val cosine = (previous.dot(current) / denominator).coerceIn(-1.0, 1.0)
        return acos(cosine)
    }

    private fun hostRelativeMoonFromEarth(snapshot: SimulationSnapshot): Vector3d {
        val moon = snapshot.bodies.first { it.id == "moon" }
        val earth = snapshot.bodies.first { it.id == "earth" }
        return moon.positionM - earth.positionM
    }
}
