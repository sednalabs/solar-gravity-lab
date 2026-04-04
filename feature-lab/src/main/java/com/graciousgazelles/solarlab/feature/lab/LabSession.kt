package com.graciousgazelles.solarlab.feature.lab

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.model.TimelineMode
import com.graciousgazelles.solarlab.core.simulation.CatalogBodyDefinition
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine
import com.graciousgazelles.solarlab.core.simulation.SolarSystemScenarios
import com.graciousgazelles.solarlab.feature.lab.data.CartesianSeedBundleAssetLoader
import com.graciousgazelles.solarlab.feature.lab.data.OrbitingBodyCatalogAssetLoader
import com.sednalabs.solarlab.physics.nativeandroid.AndroidPhysicsEngineFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LabSession private constructor(
    private val defaultCatalogEpochJdTdb: Double,
    private val defaultScenarioFactory: (Double) -> SimulationSnapshot,
    initialConfig: SimulationConfig,
    private val engineFactory: (SimulationSnapshot, SimulationConfig) -> SimulationEngine,
    private val accelerationBackendSummary: String?,
    private val listener: LabFrameListener,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "solarlab-sim").apply { isDaemon = true }
    }

    @Volatile
    private var running: Boolean = false

    private var config: SimulationConfig = initialConfig
    private var playbackSpeedPreset: PlaybackSpeedPreset = DEFAULT_PLAYBACK_SPEED
    private var stepQuantumPreset: StepQuantumPreset = DEFAULT_STEP_QUANTUM
    private var lastTickNanoTime: Long = 0L
    private var pendingSimulationSeconds: Double = 0.0
    private var runningTickCount: Int = 0
    private val perfSamples = PerfSampleAccumulator()

    private var scheduledTask: ScheduledFuture<*>? = null
    private var engine: SimulationEngine = engineFactory(defaultScenarioFactory(defaultCatalogEpochJdTdb), config)
    private var latestCatalogCheckpoint: SimulationSnapshot? = engine.snapshot().takeIf { it.isCatalogBacked }

    fun isRunning(): Boolean = running

    fun collisionMode(): CollisionMode = config.collisionMode

    fun tracerMutualGravityEnabled(): Boolean = config.includeTracerMutualGravity

    fun playbackSpeedPreset(): PlaybackSpeedPreset = playbackSpeedPreset

    fun stepQuantumPreset(): StepQuantumPreset = stepQuantumPreset

    fun start() {
        if (running) return
        running = true
        lastTickNanoTime = 0L
        pendingSimulationSeconds = 0.0
        runningTickCount = 0
        scheduledTask = executor.scheduleAtFixedRate(
            { tick() },
            0L,
            FRAME_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun pause() {
        running = false
        lastTickNanoTime = 0L
        pendingSimulationSeconds = 0.0
        runningTickCount = 0
        scheduledTask?.cancel(false)
        scheduledTask = null
    }

    fun resetDefault() {
        pause()
        executor.execute {
            engine.reset(defaultScenarioFactory(defaultCatalogEpochJdTdb))
            emitCurrentFrame(emptyList())
        }
    }

    fun dispatchCurrentFrame() {
        executor.execute {
            emitCurrentFrame(emptyList())
        }
    }

    fun stepOnce() {
        executor.execute {
            val advanceStartNs = System.nanoTime()
            val result = advanceBySimulationSeconds(
                totalSeconds = stepQuantumPreset.seconds,
                requestFreshDiagnostics = true,
            )
            val advanceDurationNs = System.nanoTime() - advanceStartNs
            emitFrame(
                snapshot = result.snapshot,
                diagnostics = result.diagnostics,
                diagnosticsFresh = result.diagnosticsFresh,
                collisions = result.collisions,
                simulationAdvanceDurationNs = advanceDurationNs,
                simulationBacklogSeconds = 0.0,
                recordPerformanceSample = false,
            )
        }
    }

    fun jumpTimelineByStep(direction: Int) {
        if (direction == 0) return
        executor.execute {
            val snapshot = engine.snapshot()
            if (direction < 0) {
                when (val action = resolveBackControlAction(snapshot, latestCatalogCheckpoint)) {
                    BackControlAction.StepCatalog -> {
                        val currentJd = snapshot.absoluteJulianDateTdbOrNull() ?: defaultCatalogEpochJdTdb
                        val targetJd = currentJd + (direction * stepQuantumPreset.seconds / PhysicalConstants.DAY_SECONDS)
                        engine.reset(defaultScenarioFactory(targetJd))
                        emitCurrentFrame(emptyList())
                    }

                    is BackControlAction.RestoreCheckpoint -> {
                        engine.reset(action.checkpoint)
                        emitCurrentFrame(emptyList())
                    }

                    BackControlAction.None -> {
                        emitCurrentFrame(emptyList())
                    }
                }
            } else if (snapshot.isCatalogBacked) {
                val currentJd = snapshot.absoluteJulianDateTdbOrNull() ?: defaultCatalogEpochJdTdb
                val targetJd = currentJd + (direction * stepQuantumPreset.seconds / PhysicalConstants.DAY_SECONDS)
                engine.reset(defaultScenarioFactory(targetJd))
                emitCurrentFrame(emptyList())
            } else if (direction > 0) {
                val advanceStartNs = System.nanoTime()
                val result = advanceBySimulationSeconds(
                    totalSeconds = stepQuantumPreset.seconds,
                    requestFreshDiagnostics = true,
                )
                val advanceDurationNs = System.nanoTime() - advanceStartNs
                emitFrame(
                    snapshot = result.snapshot,
                    diagnostics = result.diagnostics,
                    diagnosticsFresh = result.diagnosticsFresh,
                    collisions = result.collisions,
                    simulationAdvanceDurationNs = advanceDurationNs,
                    simulationBacklogSeconds = 0.0,
                    recordPerformanceSample = false,
                )
            } else {
                emitCurrentFrame(emptyList())
            }
        }
    }

    fun cyclePlaybackSpeed(direction: Int) {
        executor.execute {
            playbackSpeedPreset = playbackSpeedPreset.shifted(direction)
            pendingSimulationSeconds = 0.0
            emitCurrentFrame(emptyList())
        }
    }

    fun cycleStepQuantum(direction: Int) {
        executor.execute {
            stepQuantumPreset = stepQuantumPreset.shifted(direction)
            emitCurrentFrame(emptyList())
        }
    }

    fun addBody(body: BodyState) {
        executor.execute {
            engine.addBody(body)
            emitCurrentFrame(emptyList())
        }
    }

    fun updateBody(body: BodyState) {
        executor.execute {
            if (engine.updateBody(body)) {
                emitCurrentFrame(emptyList())
            }
        }
    }

    fun removeBody(bodyId: String) {
        executor.execute {
            if (engine.removeBody(bodyId)) {
                emitCurrentFrame(emptyList())
            }
        }
    }

    fun setCollisionMode(collisionMode: CollisionMode) {
        executor.execute {
            if (config.collisionMode == collisionMode) {
                emitCurrentFrame(emptyList())
                return@execute
            }
            config = config.copy(collisionMode = collisionMode)
            engine = engineFactory(engine.snapshot(), config)
            pendingSimulationSeconds = 0.0
            emitCurrentFrame(emptyList())
        }
    }

    fun setTracerMutualGravityEnabled(enabled: Boolean) {
        executor.execute {
            if (config.includeTracerMutualGravity == enabled) {
                emitCurrentFrame(emptyList())
                return@execute
            }
            config = config.copy(includeTracerMutualGravity = enabled)
            engine = engineFactory(engine.snapshot(), config)
            pendingSimulationSeconds = 0.0
            emitCurrentFrame(emptyList())
        }
    }

    fun release() {
        pause()
        executor.shutdownNow()
    }

    private fun tick() {
        if (!running) return
        val now = System.nanoTime()
        if (lastTickNanoTime == 0L) {
            lastTickNanoTime = now
            return
        }
        val realDeltaSeconds = ((now - lastTickNanoTime).toDouble() / 1_000_000_000.0).coerceIn(0.0, MAX_REAL_DELTA_SECONDS)
        lastTickNanoTime = now
        val simDeltaSeconds = realDeltaSeconds * playbackSpeedPreset.simSecondsPerRealSecond
        if (simDeltaSeconds <= 0.0) return
        pendingSimulationSeconds = simulationAdvanceBudget(
            totalPendingSeconds = pendingSimulationSeconds + simDeltaSeconds,
            collisionMode = config.collisionMode,
            playbackSpeedPreset = playbackSpeedPreset,
        ).cappedPendingSeconds
        if (pendingSimulationSeconds <= SUBSTEP_EPSILON_SECONDS) return
        val requestFreshDiagnostics = shouldRefreshDiagnosticsForRunningTick()
        val advanceBudget = simulationAdvanceBudget(
            totalPendingSeconds = pendingSimulationSeconds,
            collisionMode = config.collisionMode,
            playbackSpeedPreset = playbackSpeedPreset,
        )
        if (advanceBudget.secondsToAdvance <= SUBSTEP_EPSILON_SECONDS) {
            emitCurrentFrame(emptyList(), pendingSimulationSeconds)
            return
        }
        val advanceStartNs = System.nanoTime()
        val result = advanceBySimulationSeconds(
            totalSeconds = advanceBudget.secondsToAdvance,
            requestFreshDiagnostics = requestFreshDiagnostics,
            maxSubstepSeconds = advanceBudget.maxSubstepSeconds,
        )
        val advanceDurationNs = System.nanoTime() - advanceStartNs
        pendingSimulationSeconds = advanceBudget.deferredSeconds
        emitFrame(
            snapshot = result.snapshot,
            diagnostics = result.diagnostics,
            diagnosticsFresh = result.diagnosticsFresh,
            collisions = result.collisions,
            simulationAdvanceDurationNs = advanceDurationNs,
            simulationBacklogSeconds = pendingSimulationSeconds,
            recordPerformanceSample = true,
        )
    }

    private fun advanceBySimulationSeconds(
        totalSeconds: Double,
        requestFreshDiagnostics: Boolean,
        maxSubstepSeconds: Double = MAX_SIMULATION_SUBSTEP_SECONDS,
    ): com.graciousgazelles.solarlab.core.simulation.SimulationStepResult {
        var remaining = totalSeconds
        var latestResult = com.graciousgazelles.solarlab.core.simulation.SimulationStepResult(
            snapshot = engine.snapshot(),
            diagnostics = engine.diagnostics(forceRecompute = false),
            collisions = emptyList(),
            diagnosticsFresh = true,
        )
        val allCollisions = mutableListOf<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>()
        while (remaining > 0.0) {
            val substep = remaining.coerceAtMost(maxSubstepSeconds)
            val isFinalSubstep = (remaining - substep) <= SUBSTEP_EPSILON_SECONDS
            val recomputeDiagnostics = requestFreshDiagnostics && isFinalSubstep
            latestResult = engine.step(
                deltaTimeSeconds = substep,
                recomputeDiagnostics = recomputeDiagnostics,
            )
            allCollisions += latestResult.collisions
            remaining -= substep
        }
        if (allCollisions.isNotEmpty() && !latestResult.diagnosticsFresh) {
            latestResult = latestResult.copy(
                diagnostics = engine.diagnostics(forceRecompute = true),
                diagnosticsFresh = true,
            )
        }
        return latestResult.copy(collisions = allCollisions)
    }

    private fun emitCurrentFrame(
        collisions: List<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>,
        simulationBacklogSeconds: Double = 0.0,
    ) {
        emitFrame(
            snapshot = engine.snapshot(),
            diagnostics = engine.diagnostics(),
            diagnosticsFresh = true,
            collisions = collisions,
            simulationAdvanceDurationNs = 0L,
            simulationBacklogSeconds = simulationBacklogSeconds,
            recordPerformanceSample = false,
        )
    }

    private fun emitFrame(
        snapshot: SimulationSnapshot,
        diagnostics: com.graciousgazelles.solarlab.core.simulation.SystemDiagnostics,
        diagnosticsFresh: Boolean,
        collisions: List<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>,
        simulationAdvanceDurationNs: Long,
        simulationBacklogSeconds: Double,
        recordPerformanceSample: Boolean,
    ) {
        val frameBuildStartNs = System.nanoTime()
        if (snapshot.isCatalogBacked) {
            latestCatalogCheckpoint = snapshot
        }
        val backControlAction = resolveBackControlAction(snapshot, latestCatalogCheckpoint)
        val frame = LabFrame(
            snapshot = snapshot,
            diagnostics = diagnostics,
            diagnosticsFresh = diagnosticsFresh,
            collisions = collisions,
            accelerationBackendSummary = accelerationBackendSummary,
            timeline = TimelineStatus(
                mode = snapshot.timelineMode,
                referenceEpochJdTdb = snapshot.referenceEpochJdTdb,
                absoluteJulianDateTdb = snapshot.absoluteJulianDateTdbOrNull(),
                playbackSpeed = playbackSpeedPreset,
                stepQuantum = stepQuantumPreset,
                simulationBacklogSeconds = simulationBacklogSeconds,
                canJumpAbsolute = snapshot.isCatalogBacked,
                canStepBackward = backControlAction != BackControlAction.None,
            ),
        )
        val frameBuildDurationNs = System.nanoTime() - frameBuildStartNs
        val handoffStartNs = System.nanoTime()
        mainHandler.post {
            if (recordPerformanceSample) {
                val handoffLatencyNs = System.nanoTime() - handoffStartNs
                val perfSummary = perfSamples.record(
                    simulationAdvanceDurationNs = simulationAdvanceDurationNs,
                    frameBuildDurationNs = frameBuildDurationNs,
                    handoffLatencyNs = handoffLatencyNs,
                    simulationBacklogSeconds = simulationBacklogSeconds,
                )
                if (perfSummary != null) {
                    Log.i(TAG, perfSummary)
                }
            }
            listener.onLabFrame(frame)
        }
    }

    private fun shouldRefreshDiagnosticsForRunningTick(): Boolean {
        runningTickCount += 1
        return (runningTickCount % RUNNING_DIAGNOSTICS_REFRESH_EVERY_N_TICKS) == 0
    }

    private class PerfSampleAccumulator {
        private var sampleCount: Int = 0
        private var simulationAdvanceTotalNs: Long = 0L
        private var frameBuildTotalNs: Long = 0L
        private var handoffLatencyTotalNs: Long = 0L
        private var simulationAdvanceMaxNs: Long = 0L
        private var frameBuildMaxNs: Long = 0L
        private var handoffLatencyMaxNs: Long = 0L
        private var simulationBacklogTotalSeconds: Double = 0.0
        private var simulationBacklogMaxSeconds: Double = 0.0

        fun record(
            simulationAdvanceDurationNs: Long,
            frameBuildDurationNs: Long,
            handoffLatencyNs: Long,
            simulationBacklogSeconds: Double,
        ): String? {
            sampleCount += 1
            simulationAdvanceTotalNs += simulationAdvanceDurationNs
            frameBuildTotalNs += frameBuildDurationNs
            handoffLatencyTotalNs += handoffLatencyNs
            simulationAdvanceMaxNs = maxOf(simulationAdvanceMaxNs, simulationAdvanceDurationNs)
            frameBuildMaxNs = maxOf(frameBuildMaxNs, frameBuildDurationNs)
            handoffLatencyMaxNs = maxOf(handoffLatencyMaxNs, handoffLatencyNs)
            simulationBacklogTotalSeconds += simulationBacklogSeconds
            simulationBacklogMaxSeconds = maxOf(simulationBacklogMaxSeconds, simulationBacklogSeconds)
            if (sampleCount < PERF_LOG_SAMPLE_WINDOW_FRAMES) {
                return null
            }
            val summary = "PerfStats window=$sampleCount " +
                "simAvgMs=${toMillis(simulationAdvanceTotalNs, sampleCount)} simMaxMs=${toMillis(simulationAdvanceMaxNs)} " +
                "buildAvgMs=${toMillis(frameBuildTotalNs, sampleCount)} buildMaxMs=${toMillis(frameBuildMaxNs)} " +
                "handoffAvgMs=${toMillis(handoffLatencyTotalNs, sampleCount)} handoffMaxMs=${toMillis(handoffLatencyMaxNs)} " +
                "backlogAvgS=${toSeconds(simulationBacklogTotalSeconds, sampleCount)} backlogMaxS=${toSeconds(simulationBacklogMaxSeconds)}"
            reset()
            return summary
        }

        private fun reset() {
            sampleCount = 0
            simulationAdvanceTotalNs = 0L
            frameBuildTotalNs = 0L
            handoffLatencyTotalNs = 0L
            simulationAdvanceMaxNs = 0L
            frameBuildMaxNs = 0L
            handoffLatencyMaxNs = 0L
            simulationBacklogTotalSeconds = 0.0
            simulationBacklogMaxSeconds = 0.0
        }

        private fun toMillis(nanoseconds: Long): String = "%.3f".format(nanoseconds / 1_000_000.0)

        private fun toMillis(totalNanoseconds: Long, count: Int): String = "%.3f".format((totalNanoseconds / count.toDouble()) / 1_000_000.0)

        private fun toSeconds(seconds: Double): String = "%.3f".format(seconds)

        private fun toSeconds(totalSeconds: Double, count: Int): String = "%.3f".format(totalSeconds / count.toDouble())
    }

    companion object {
        private const val TAG: String = "LabSessionPerf"
        private const val FRAME_PERIOD_MS: Long = 16L
        private const val MAX_REAL_DELTA_SECONDS: Double = 0.25
        private const val MAX_SIMULATION_SUBSTEP_SECONDS: Double = 3600.0
        private const val PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK: Double = 12.0
        private const val HOST_RELATIVE_PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK: Double = 24.0
        private const val HOST_RELATIVE_SHORT_WINDOW_MAX_SECONDS: Double = PhysicalConstants.DAY_SECONDS
        private const val HOST_RELATIVE_SHORT_WINDOW_MAX_EFFECTIVE_SUBSTEP_SECONDS: Double = 10_800.0
        private const val PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS: Double = 32_400.0
        private const val HIGH_SPEED_PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS: Double = 21_600.0
        private const val HIGH_SPEED_PLAYBACK_THRESHOLD_SIM_SECONDS_PER_REAL_SECOND: Double =
            7.0 * PhysicalConstants.DAY_SECONDS
        private const val PLAYBACK_MAX_SUBSTEPS_PER_RENDER_TICK: Double = 3.0
        private const val COLLISION_MAX_SUBSTEPS_PER_RENDER_TICK: Double = 2.0
        private const val MAX_RENDER_TICK_BACKLOG_WINDOWS: Double = 4.0
        private const val RUNNING_DIAGNOSTICS_REFRESH_EVERY_N_TICKS: Int = 4
        private const val PERF_LOG_SAMPLE_WINDOW_FRAMES: Int = 120
        private const val SUBSTEP_EPSILON_SECONDS: Double = 1.0e-9
        private val DEFAULT_PLAYBACK_SPEED: PlaybackSpeedPreset = PlaybackSpeedPreset.SIX_HOURS_PER_SECOND
        private val DEFAULT_STEP_QUANTUM: StepQuantumPreset = StepQuantumPreset.SIX_HOURS

        fun createDefault(
            listener: LabFrameListener,
        ): LabSession {
            val defaultEpoch = SolarSystemScenarios.defaultSeedJulianDateTdb()
            return LabSession(
                defaultCatalogEpochJdTdb = defaultEpoch,
                defaultScenarioFactory = { targetJd ->
                    SolarSystemScenarios.defaultLabScenario(julianDateTdb = targetJd)
                },
                initialConfig = SimulationConfig(),
                engineFactory = { snapshot, config -> SimulationEngine(snapshot, config) },
                accelerationBackendSummary = "kotlin-reference",
                listener = listener,
            )
        }

        fun createDefault(
            context: Context,
            listener: LabFrameListener,
        ): LabSession {
            val seedBundle = CartesianSeedBundleAssetLoader.loadIfAvailable(context)
            val importedMoons = OrbitingBodyCatalogAssetLoader.loadIfAvailable(
                context = context,
                assetPath = OrbitingBodyCatalogAssetLoader.PLANETARY_MOONS_ASSET_PATH,
            )
            val importedSmallBodies = OrbitingBodyCatalogAssetLoader.loadIfAvailable(
                context = context,
                assetPath = OrbitingBodyCatalogAssetLoader.CURATED_SMALL_BODIES_ASSET_PATH,
            )
            val importedCatalogBodies = buildList<CatalogBodyDefinition> {
                addAll(importedMoons)
                addAll(importedSmallBodies)
            }
            val defaultEpoch = SolarSystemScenarios.defaultSeedJulianDateTdb(seedBundle)
            val physicsSelection = AndroidPhysicsEngineFactory.selection()
            return LabSession(
                defaultCatalogEpochJdTdb = defaultEpoch,
                defaultScenarioFactory = { targetJd ->
                    SolarSystemScenarios.defaultLabScenario(
                        julianDateTdb = targetJd,
                        seedBundle = seedBundle,
                        importedCatalogBodies = importedCatalogBodies,
                    )
                },
                initialConfig = SimulationConfig(),
                engineFactory = physicsSelection.engineFactory,
                accelerationBackendSummary = physicsSelection.accelerationBackendSummary,
                listener = listener,
            )
        }

        internal fun playbackSubstepPlan(
            totalSeconds: Double,
            collisionMode: CollisionMode,
            playbackSpeedPreset: PlaybackSpeedPreset,
        ): PlaybackSubstepPlan {
            return PlaybackSubstepPlan(
                totalSeconds = totalSeconds,
                maxSubstepSeconds = effectivePlaybackMaxSubstepSeconds(
                    totalSeconds = totalSeconds,
                    collisionMode = collisionMode,
                    playbackSpeedPreset = playbackSpeedPreset,
                ),
            )
        }

        internal data class PlaybackSubstepPlan(
            val totalSeconds: Double,
            val maxSubstepSeconds: Double,
        )

        internal data class SimulationAdvanceBudget(
            val cappedPendingSeconds: Double,
            val secondsToAdvance: Double,
            val deferredSeconds: Double,
            val maxSubstepSeconds: Double,
        )

        internal fun effectivePlaybackMaxSubstepSeconds(
            totalSeconds: Double,
            collisionMode: CollisionMode,
            playbackSpeedPreset: PlaybackSpeedPreset = DEFAULT_PLAYBACK_SPEED,
        ): Double {
            if (collisionMode != CollisionMode.NONE) {
                return MAX_SIMULATION_SUBSTEP_SECONDS
            }
            val isHighSpeedPlayback = playbackSpeedPreset.simSecondsPerRealSecond >=
                HIGH_SPEED_PLAYBACK_THRESHOLD_SIM_SECONDS_PER_REAL_SECOND
            val maxEffectiveSubstepSeconds = if (
                isHighSpeedPlayback
            ) {
                HIGH_SPEED_PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS
            } else {
                PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS
            }
            val adaptiveSubstep = totalSeconds / PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK
            val presetBasedCap = adaptiveSubstep.coerceIn(
                minimumValue = MAX_SIMULATION_SUBSTEP_SECONDS,
                maximumValue = maxEffectiveSubstepSeconds,
            )
            if (!shouldApplyHostRelativeShortWindowCap(totalSeconds, playbackSpeedPreset)) {
                return presetBasedCap
            }
            val hostRelativeAdaptiveSubstep = totalSeconds / HOST_RELATIVE_PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK
            val hostRelativeCap = hostRelativeAdaptiveSubstep.coerceIn(
                minimumValue = MAX_SIMULATION_SUBSTEP_SECONDS,
                maximumValue = HOST_RELATIVE_SHORT_WINDOW_MAX_EFFECTIVE_SUBSTEP_SECONDS,
            )
            return minOf(presetBasedCap, hostRelativeCap)
        }

        internal fun simulationAdvanceBudget(
            totalPendingSeconds: Double,
            collisionMode: CollisionMode,
            playbackSpeedPreset: PlaybackSpeedPreset = DEFAULT_PLAYBACK_SPEED,
        ): SimulationAdvanceBudget {
            val nonNegativePending = totalPendingSeconds.coerceAtLeast(0.0)
            if (nonNegativePending <= SUBSTEP_EPSILON_SECONDS) {
                return SimulationAdvanceBudget(
                    cappedPendingSeconds = 0.0,
                    secondsToAdvance = 0.0,
                    deferredSeconds = 0.0,
                    maxSubstepSeconds = MAX_SIMULATION_SUBSTEP_SECONDS,
                )
            }
            val playbackPlan = playbackSubstepPlan(
                totalSeconds = nonNegativePending,
                collisionMode = collisionMode,
                playbackSpeedPreset = playbackSpeedPreset,
            )
            val maxSubstepsPerTick = if (collisionMode == CollisionMode.NONE) {
                PLAYBACK_MAX_SUBSTEPS_PER_RENDER_TICK
            } else {
                COLLISION_MAX_SUBSTEPS_PER_RENDER_TICK
            }
            val maxSecondsPerTick = playbackPlan.maxSubstepSeconds * maxSubstepsPerTick
            val cappedPendingSeconds = nonNegativePending.coerceAtMost(maxSecondsPerTick * MAX_RENDER_TICK_BACKLOG_WINDOWS)
            val secondsToAdvance = cappedPendingSeconds.coerceAtMost(maxSecondsPerTick)
            return SimulationAdvanceBudget(
                cappedPendingSeconds = cappedPendingSeconds,
                secondsToAdvance = secondsToAdvance,
                deferredSeconds = (cappedPendingSeconds - secondsToAdvance).coerceAtLeast(0.0),
                maxSubstepSeconds = playbackPlan.maxSubstepSeconds,
            )
        }

        private fun shouldApplyHostRelativeShortWindowCap(
            totalSeconds: Double,
            playbackSpeedPreset: PlaybackSpeedPreset,
        ): Boolean {
            if (playbackSpeedPreset != PlaybackSpeedPreset.MONTH_PER_SECOND) return false
            return totalSeconds <= HOST_RELATIVE_SHORT_WINDOW_MAX_SECONDS
        }
    }
}

internal sealed interface BackControlAction {
    data object StepCatalog : BackControlAction
    data class RestoreCheckpoint(val checkpoint: SimulationSnapshot) : BackControlAction
    data object None : BackControlAction
}

internal fun resolveBackControlAction(
    snapshot: SimulationSnapshot,
    latestCatalogCheckpoint: SimulationSnapshot?,
): BackControlAction = when {
    snapshot.isCatalogBacked -> BackControlAction.StepCatalog
    latestCatalogCheckpoint != null -> BackControlAction.RestoreCheckpoint(latestCatalogCheckpoint)
    else -> BackControlAction.None
}

private fun PlaybackSpeedPreset.shifted(direction: Int): PlaybackSpeedPreset {
    val entries = PlaybackSpeedPreset.entries
    val index = entries.indexOf(this).coerceAtLeast(0)
    return entries[(index + direction).coerceIn(0, entries.lastIndex)]
}

private fun StepQuantumPreset.shifted(direction: Int): StepQuantumPreset {
    val entries = StepQuantumPreset.entries
    val index = entries.indexOf(this).coerceAtLeast(0)
    return entries[(index + direction).coerceIn(0, entries.lastIndex)]
}
