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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LabSession private constructor(
    private val defaultCatalogEpochJdTdb: Double,
    private val defaultScenarioFactory: (Double) -> SimulationSnapshot,
    initialConfig: SimulationConfig,
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
    private var runningTickCount: Int = 0
    private val perfSamples = PerfSampleAccumulator()

    private var scheduledTask: ScheduledFuture<*>? = null
    private var engine: SimulationEngine = SimulationEngine(defaultScenarioFactory(defaultCatalogEpochJdTdb), config)
    private var latestCatalogCheckpoint: SimulationSnapshot? = engine.snapshot().takeIf { it.isCatalogBacked }

    fun isRunning(): Boolean = running

    fun collisionMode(): CollisionMode = config.collisionMode

    fun playbackSpeedPreset(): PlaybackSpeedPreset = playbackSpeedPreset

    fun stepQuantumPreset(): StepQuantumPreset = stepQuantumPreset

    fun start() {
        if (running) return
        running = true
        lastTickNanoTime = 0L
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
            engine = SimulationEngine(engine.snapshot(), config)
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
        val requestFreshDiagnostics = shouldRefreshDiagnosticsForRunningTick()
        val effectiveMaxSubstepSeconds = effectivePlaybackMaxSubstepSeconds(simDeltaSeconds)
        val advanceStartNs = System.nanoTime()
        val result = advanceBySimulationSeconds(
            totalSeconds = simDeltaSeconds,
            requestFreshDiagnostics = requestFreshDiagnostics,
            maxSubstepSeconds = effectiveMaxSubstepSeconds,
        )
        val advanceDurationNs = System.nanoTime() - advanceStartNs
        emitFrame(
            snapshot = result.snapshot,
            diagnostics = result.diagnostics,
            diagnosticsFresh = result.diagnosticsFresh,
            collisions = result.collisions,
            simulationAdvanceDurationNs = advanceDurationNs,
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

    private fun emitCurrentFrame(collisions: List<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>) {
        emitFrame(
            snapshot = engine.snapshot(),
            diagnostics = engine.diagnostics(),
            diagnosticsFresh = true,
            collisions = collisions,
            simulationAdvanceDurationNs = 0L,
            recordPerformanceSample = false,
        )
    }

    private fun emitFrame(
        snapshot: SimulationSnapshot,
        diagnostics: com.graciousgazelles.solarlab.core.simulation.SystemDiagnostics,
        diagnosticsFresh: Boolean,
        collisions: List<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>,
        simulationAdvanceDurationNs: Long,
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
            timeline = TimelineStatus(
                mode = snapshot.timelineMode,
                referenceEpochJdTdb = snapshot.referenceEpochJdTdb,
                absoluteJulianDateTdb = snapshot.absoluteJulianDateTdbOrNull(),
                playbackSpeed = playbackSpeedPreset,
                stepQuantum = stepQuantumPreset,
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

        fun record(
            simulationAdvanceDurationNs: Long,
            frameBuildDurationNs: Long,
            handoffLatencyNs: Long,
        ): String? {
            sampleCount += 1
            simulationAdvanceTotalNs += simulationAdvanceDurationNs
            frameBuildTotalNs += frameBuildDurationNs
            handoffLatencyTotalNs += handoffLatencyNs
            simulationAdvanceMaxNs = maxOf(simulationAdvanceMaxNs, simulationAdvanceDurationNs)
            frameBuildMaxNs = maxOf(frameBuildMaxNs, frameBuildDurationNs)
            handoffLatencyMaxNs = maxOf(handoffLatencyMaxNs, handoffLatencyNs)
            if (sampleCount < PERF_LOG_SAMPLE_WINDOW_FRAMES) {
                return null
            }
            val summary = "PerfStats window=$sampleCount " +
                "simAvgMs=${toMillis(simulationAdvanceTotalNs, sampleCount)} simMaxMs=${toMillis(simulationAdvanceMaxNs)} " +
                "buildAvgMs=${toMillis(frameBuildTotalNs, sampleCount)} buildMaxMs=${toMillis(frameBuildMaxNs)} " +
                "handoffAvgMs=${toMillis(handoffLatencyTotalNs, sampleCount)} handoffMaxMs=${toMillis(handoffLatencyMaxNs)}"
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
        }

        private fun toMillis(nanoseconds: Long): String = "%.3f".format(nanoseconds / 1_000_000.0)

        private fun toMillis(totalNanoseconds: Long, count: Int): String = "%.3f".format((totalNanoseconds / count.toDouble()) / 1_000_000.0)
    }

    companion object {
        private const val TAG: String = "LabSessionPerf"
        private const val FRAME_PERIOD_MS: Long = 16L
        private const val MAX_REAL_DELTA_SECONDS: Double = 0.25
        private const val MAX_SIMULATION_SUBSTEP_SECONDS: Double = 3600.0
        private const val PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK: Double = 12.0
        private const val PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS: Double = 43_200.0
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
                listener = listener,
            )
        }

        internal fun effectivePlaybackMaxSubstepSeconds(totalSeconds: Double): Double {
            val adaptiveSubstep = totalSeconds / PLAYBACK_TARGET_MAX_SUBSTEPS_PER_TICK
            return adaptiveSubstep.coerceIn(
                minimumValue = MAX_SIMULATION_SUBSTEP_SECONDS,
                maximumValue = PLAYBACK_MAX_EFFECTIVE_SUBSTEP_SECONDS,
            )
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
