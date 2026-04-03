package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.abs

data class SystemDiagnostics(
    val totalMassKg: Double,
    val totalKineticEnergyJ: Double,
    val totalPotentialEnergyJ: Double,
    val totalEnergyJ: Double,
    val linearMomentumKgMps: Vector3d,
    val angularMomentumKgM2PerS: Vector3d,
    val barycenterM: Vector3d,
    val barycenterVelocityMps: Vector3d,
    val massiveBodyCount: Int,
    val tracerBodyCount: Int,
) {
    fun toPrettyString(): String = buildString {
        appendLine("Massive bodies: $massiveBodyCount | Tracers: $tracerBodyCount")
        appendLine("Total mass: ${formatScientific(totalMassKg)} kg")
        appendLine("Kinetic:    ${formatScientific(totalKineticEnergyJ)} J")
        appendLine("Potential:  ${formatScientific(totalPotentialEnergyJ)} J")
        appendLine("Total E:    ${formatScientific(totalEnergyJ)} J")
        appendLine("Barycenter: ${formatVector(barycenterM)} m")
        append("Momentum:   ${formatVector(linearMomentumKgMps)} kg·m/s")
    }

    private fun formatScientific(value: Double): String = "% .3e".format(value)

    private fun formatVector(value: Vector3d): String =
        "[${formatCompact(value.x)}, ${formatCompact(value.y)}, ${formatCompact(value.z)}]"

    private fun formatCompact(value: Double): String {
        if (abs(value) < 1_000.0) return "% .2f".format(value)
        return "% .2e".format(value)
    }
}
