package com.sednalabs.solarlab.physics.nativeandroid

import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.simulation.SimulationEngine

object AndroidPhysicsEngineFactory {
    data class Selection(
        val engineFactory: (SimulationSnapshot, SimulationConfig) -> SimulationEngine,
        val accelerationBackendSummary: String,
    )

    fun selection(): Selection {
        if (!NativePhysicsBridge.isAvailable()) {
            return Selection(
                engineFactory = { snapshot, config ->
                    SimulationEngine(
                        initialSnapshot = snapshot,
                        config = config,
                    )
                },
                accelerationBackendSummary = "kotlin-reference (native-scalar unavailable)",
            )
        }

        val backendSummary = "native-authoritative/${NativePhysicsBridge.backendSummary()}"
        return Selection(
            engineFactory = { snapshot, config ->
                SimulationEngine(
                    initialSnapshot = snapshot,
                    config = config,
                    massiveAccelerationKernel = NativeScalarMassiveAccelerationKernel,
                    tracerAccelerationKernel = NativeScalarTracerAccelerationKernel,
                )
            },
            accelerationBackendSummary = backendSummary,
        )
    }
}
