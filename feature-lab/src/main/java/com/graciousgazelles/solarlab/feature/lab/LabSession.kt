package com.graciousgazelles.solarlab.feature.lab

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine
import com.graciousgazelles.solarlab.core.simulation.SolarSystemScenarios
import com.graciousgazelles.solarlab.feature.lab.data.CartesianSeedBundleAssetLoader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LabSession private constructor(
    private val defaultScenarioFactory: () -> SimulationSnapshot,
    private val config: SimulationConfig,
    private val listener: LabFrameListener,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "solarlab-sim").apply { isDaemon = true }
    }

    @Volatile
    private var running: Boolean = false

    private var scheduledTask: ScheduledFuture<*>? = null
    private var engine: SimulationEngine = SimulationEngine(defaultScenarioFactory(), config)

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        scheduledTask = executor.scheduleAtFixedRate(
            { tick() },
            0L,
            FRAME_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun pause() {
        running = false
        scheduledTask?.cancel(false)
        scheduledTask = null
    }

    fun resetDefault() {
        pause()
        executor.execute {
            engine.reset(defaultScenarioFactory())
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
            val result = engine.step(DEFAULT_SIMULATION_STEP_SECONDS)
            emitFrame(result.snapshot, result.diagnostics, result.collisions)
        }
    }

    fun release() {
        pause()
        executor.shutdownNow()
    }

    private fun tick() {
        if (!running) return
        val result = engine.step(DEFAULT_SIMULATION_STEP_SECONDS)
        emitFrame(result.snapshot, result.diagnostics, result.collisions)
    }

    private fun emitCurrentFrame(collisions: List<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>) {
        emitFrame(engine.snapshot(), engine.diagnostics(), collisions)
    }

    private fun emitFrame(
        snapshot: SimulationSnapshot,
        diagnostics: com.graciousgazelles.solarlab.core.simulation.SystemDiagnostics,
        collisions: List<com.graciousgazelles.solarlab.core.simulation.CollisionEvent>,
    ) {
        val frame = LabFrame(
            snapshot = snapshot,
            diagnostics = diagnostics,
            collisions = collisions,
        )
        mainHandler.post {
            listener.onLabFrame(frame)
        }
    }

    companion object {
        private const val FRAME_PERIOD_MS: Long = 16L
        private const val DEFAULT_SIMULATION_STEP_SECONDS: Double = 6.0 * 3600.0

        fun createDefault(
            listener: LabFrameListener,
        ): LabSession = LabSession(
            defaultScenarioFactory = {
                SolarSystemScenarios.defaultLabScenario()
            },
            config = SimulationConfig(),
            listener = listener,
        )

        fun createDefault(
            context: Context,
            listener: LabFrameListener,
        ): LabSession {
            val seedBundle = CartesianSeedBundleAssetLoader.loadIfAvailable(context)
            return LabSession(
                defaultScenarioFactory = {
                    SolarSystemScenarios.defaultLabScenario(seedBundle = seedBundle)
                },
                config = SimulationConfig(),
                listener = listener,
            )
        }
    }
}
