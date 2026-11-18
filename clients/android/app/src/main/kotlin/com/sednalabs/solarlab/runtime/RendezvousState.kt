package com.sednalabs.solarlab.runtime

/** Commands select native, force-driven guidance; the shell never integrates relative motion. */
enum class RendezvousGuidance(val nativeCode: Int) {
    Manual(0), Approach(1), MatchVelocity(2);

    companion object {
        fun fromNative(value: Double) = entries.firstOrNull { it.nativeCode.toDouble() == value }
            ?: error("Unsupported rendezvous guidance: $value")
    }
}

enum class RendezvousStatus(val nativeCode: Int, val label: String) {
    Manual(0, "Manual approach"), Approaching(1, "Approaching stand-off"),
    VelocityMatching(2, "Matching velocity"), Holding(3, "Holding position"),
    KeepOut(4, "Keep-out braking"), OutOfFuel(5, "Propellant depleted"),
    OutOfRange(6, "Outside guidance range"), TargetUnavailable(7, "Station unavailable");

    companion object {
        fun fromNative(value: Double) = entries.firstOrNull { it.nativeCode.toDouble() == value }
            ?: error("Unsupported rendezvous status: $value")
    }
}

/** Relative frames, observations and completion latches are authoritative native telemetry. */
data class RendezvousState(
    val targetBodyId: String,
    val epochSeconds: Double,
    val elapsedSeconds: Double,
    val guidance: RendezvousGuidance,
    val status: RendezvousStatus,
    val targetAvailable: Boolean,
    val rangeM: Double,
    val closingSpeedMps: Double,
    val relativeSpeedMps: Double,
    val positionTargetX: Double,
    val positionTargetY: Double,
    val positionTargetZ: Double,
    val velocityTargetX: Double,
    val velocityTargetY: Double,
    val velocityTargetZ: Double,
    val targetDirectionX: Double,
    val targetDirectionY: Double,
    val targetDirectionZ: Double,
    val keepOutDistanceM: Double,
    val standOffDistanceM: Double,
    val standOffErrorM: Double,
    val closestObservedRangeM: Double,
    val closestObservedEpochSeconds: Double,
    val propellantUsedKg: Double,
    val remainingPropellantKg: Double,
    val standOffAchieved: Boolean,
    val keepOutEntered: Boolean,
)

internal fun rendezvousStateFromNative(values: DoubleArray, targetBodyId: String): RendezvousState {
    require(values.size == 27 && values[0] == 1.0) { "Unsupported rendezvous telemetry schema" }
    require(targetBodyId.isNotBlank()) { "Rendezvous target identity is missing" }
    require(values.all(Double::isFinite)) { "Non-finite rendezvous telemetry" }
    require(listOf(2, 6, 8, 18, 19, 21, 23, 24).all { values[it] >= 0.0 } &&
        listOf(5, 25, 26).all { values[it] == 0.0 || values[it] == 1.0 } && values[19] > values[18]) {
        "Invalid rendezvous telemetry"
    }
    return RendezvousState(
        targetBodyId, values[1], values[2], RendezvousGuidance.fromNative(values[3]),
        RendezvousStatus.fromNative(values[4]), values[5] == 1.0,
        values[6], values[7], values[8], values[9], values[10], values[11], values[12], values[13], values[14],
        values[15], values[16], values[17], values[18], values[19], values[20], values[21], values[22],
        values[23], values[24], values[25] == 1.0, values[26] == 1.0,
    )
}
