package com.sednalabs.solarlab.runtime

import kotlin.math.hypot

/** The native controller supplies forces and torque; these values are command intent only. */
enum class DockingGuidance(val nativeCode: Int) {
    Manual(0), Approach(1);

    companion object {
        fun fromNative(value: Double) = entries.firstOrNull { it.nativeCode.toDouble() == value }
            ?: error("Unsupported docking guidance: $value")
    }
}

enum class DockingStatus(val nativeCode: Int, val label: String) {
    Manual(0, "Manual flight"), Aligning(1, "Aligning the ports"),
    Approaching(2, "Closing on the port"), CaptureReady(3, "Within capture limits"),
    Docked(4, "Docked to Relay"), UnsafeContact(5, "Unsafe contact recorded"),
    OutOfFuel(6, "Propellant depleted"), OutOfRange(7, "Outside docking range"),
    TargetUnavailable(8, "Station unavailable");

    companion object {
        fun fromNative(value: Double) = entries.firstOrNull { it.nativeCode.toDouble() == value }
            ?: error("Unsupported docking status: $value")
    }
}

/**
 * Native port-to-port observations. Position and velocity are expressed in Relay's
 * port frame: +X right, +Y key-up, +Z outward. A positive Z is an open axial gap.
 * Alignment includes roll; attached is the physical constraint, not a UI latch.
 */
data class DockingState(
    val targetBodyId: String,
    val epochSeconds: Double,
    val guidance: DockingGuidance,
    val status: DockingStatus,
    val targetAvailable: Boolean,
    val armed: Boolean,
    val attached: Boolean,
    val portDistanceM: Double,
    val closingSpeedMps: Double,
    val relativeSpeedMps: Double,
    val positionPortXM: Double,
    val positionPortYM: Double,
    val positionPortZM: Double,
    val velocityPortXMps: Double,
    val velocityPortYMps: Double,
    val velocityPortZMps: Double,
    val alignmentErrorRad: Double,
    val relativeAngularSpeedRadps: Double,
    val captureReady: Boolean,
    val unsafeContact: Boolean,
    val remainingPropellantKg: Double,
    val centreRangeM: Double,
    val captureLateralLimitM: Double,
    val captureGapLimitM: Double,
    val captureSpeedLimitMps: Double,
) {
    val lateralErrorM: Double get() = hypot(positionPortXM, positionPortYM)
}

internal fun dockingStateFromNative(values: DoubleArray, targetBodyId: String): DockingState {
    require(values.size == 25 && values[0] == 1.0) { "Unsupported docking telemetry schema" }
    require(targetBodyId.isNotBlank()) { "Docking target identity is missing" }
    require(values.all(Double::isFinite)) { "Non-finite docking telemetry" }
    require(listOf(7, 9, 16, 17, 20, 21).all { values[it] >= 0.0 } &&
        listOf(4, 5, 6, 18, 19).all { values[it] == 0.0 || values[it] == 1.0 } &&
        listOf(22, 23, 24).all { values[it] > 0.0 } && values[16] <= Math.PI + 1e-9) {
        "Invalid docking telemetry"
    }
    return DockingState(targetBodyId, values[1], DockingGuidance.fromNative(values[2]),
        DockingStatus.fromNative(values[3]), values[4] == 1.0, values[5] == 1.0, values[6] == 1.0,
        values[7], values[8], values[9], values[10], values[11], values[12], values[13], values[14], values[15],
        values[16], values[17], values[18] == 1.0, values[19] == 1.0, values[20], values[21],
        values[22], values[23], values[24])
}
