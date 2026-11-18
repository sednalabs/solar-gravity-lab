package com.sednalabs.solarlab.runtime

/** Mission observations are sampled and persisted by the authoritative Rust solver. */
enum class LunarMissionPhase(val nativeCode: Int, val label: String) {
    Ready(0, "Departure ready"), PoweredFlight(1, "Departure burn"), Coast(2, "Coasting to the Moon"),
    LunarApproach(3, "Lunar approach"), LunarFlyby(4, "Lunar flyby"),
    LunarSurfaceCrossing(5, "Lunar surface crossed"), EarthSurfaceCrossing(6, "Earth surface crossed"),
    EscapingEarth(7, "Escaping Earth"), Missed(8, "Flight window passed");

    companion object {
        fun fromNative(code: Double) = entries.firstOrNull { it.nativeCode.toDouble() == code }
            ?: error("Unsupported lunar mission phase: $code")
    }
}

enum class LunarMissionOutcome(val nativeCode: Int) {
    InProgress(0), FlybyAchieved(1), LunarSurfaceCrossing(2), EarthSurfaceCrossing(3), Missed(4);

    companion object {
        fun fromNative(code: Double) = entries.firstOrNull { it.nativeCode.toDouble() == code }
            ?: error("Unsupported lunar mission outcome: $code")
    }
}

data class LunarMissionState(
    val epochSeconds: Double,
    val launchEpochSeconds: Double,
    val elapsedSeconds: Double,
    val phase: LunarMissionPhase,
    val outcome: LunarMissionOutcome,
    val plannedBurnSeconds: Double,
    val actualMainFullThrottleSeconds: Double,
    val departureRemainingSeconds: Double,
    val guidedBurnActive: Boolean,
    val guidedBurnRemainingSeconds: Double,
    val earthAltitudeM: Double,
    val moonAltitudeM: Double,
    val moonRangeRateMps: Double,
    val moonRelativeSpeedMps: Double,
    val closestObservedMoonAltitudeM: Double,
    val closestObservedEpochSeconds: Double,
    val propellantMassKg: Double,
    val surfaceResponseModelled: Boolean,
    val expectedFlybyWindowStartSeconds: Double,
    val expectedFlybyWindowEndSeconds: Double,
) {
    val departureProgress: Float get() = (actualMainFullThrottleSeconds / plannedBurnSeconds).coerceIn(0.0, 1.0).toFloat()
    val hasOutcome: Boolean get() = outcome != LunarMissionOutcome.InProgress
}

internal fun lunarMissionStateFromNative(values: DoubleArray): LunarMissionState {
    require(values.size == 21 && values[0] == 1.0) { "Unsupported lunar mission telemetry schema" }
    require(values.all(Double::isFinite)) { "Non-finite lunar mission telemetry" }
    require(values[3] >= 0.0 && values[6] > 0.0 && values[7] >= 0.0 && values[8] >= 0.0 &&
        values[10] >= 0.0 && values[14] >= 0.0 && values[17] >= 0.0 &&
        values[19] >= 0.0 && values[20] >= values[19] &&
        values[9] in listOf(0.0, 1.0) && values[18] in listOf(0.0, 1.0)) { "Invalid lunar mission telemetry" }
    return LunarMissionState(
        epochSeconds = values[1], launchEpochSeconds = values[2], elapsedSeconds = values[3],
        phase = LunarMissionPhase.fromNative(values[4]), outcome = LunarMissionOutcome.fromNative(values[5]),
        plannedBurnSeconds = values[6], actualMainFullThrottleSeconds = values[7], departureRemainingSeconds = values[8],
        guidedBurnActive = values[9] == 1.0, guidedBurnRemainingSeconds = values[10],
        earthAltitudeM = values[11], moonAltitudeM = values[12], moonRangeRateMps = values[13],
        moonRelativeSpeedMps = values[14], closestObservedMoonAltitudeM = values[15],
        closestObservedEpochSeconds = values[16], propellantMassKg = values[17], surfaceResponseModelled = values[18] == 1.0,
        expectedFlybyWindowStartSeconds = values[19], expectedFlybyWindowEndSeconds = values[20],
    )
}
