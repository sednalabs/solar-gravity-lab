package com.sednalabs.solarlab.physics.nativeandroid

import com.graciousgazelles.solarlab.core.simulation.AccelerationVectorBuffers
import com.graciousgazelles.solarlab.core.simulation.MassiveAccelerationKernel
import com.graciousgazelles.solarlab.core.simulation.MassiveSourceBuffers
import com.graciousgazelles.solarlab.core.simulation.TargetBodyBuffers
import com.graciousgazelles.solarlab.core.simulation.TracerAccelerationKernel

internal object NativeScalarMassiveAccelerationKernel : MassiveAccelerationKernel {
    override fun compute(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers = NativePhysicsBridge.computeMassiveAccelerations(
        sources = sources,
        targets = targets,
        gravitationalConstant = gravitationalConstant,
        softeningSquared = softeningSquared,
    )
}

internal object NativeScalarTracerAccelerationKernel : TracerAccelerationKernel {
    override fun compute(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers = NativePhysicsBridge.computeTracerAccelerations(
        sources = sources,
        targets = targets,
        gravitationalConstant = gravitationalConstant,
        softeningSquared = softeningSquared,
    )
}
