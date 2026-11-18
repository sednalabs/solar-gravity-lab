package com.sednalabs.solarlab.runtime

/** Presentation contract only. The Rust world owns every physical quantity. */
const val REFERENCE_SPACECRAFT_ID = "flight.odyssey"

enum class FlightAssist(val nativeCode: Int, val label: String) {
    Manual(0, "Manual"), Hold(1, "Stabilise"), Prograde(2, "Prograde"),
    Retrograde(3, "Retrograde"), Normal(4, "Normal"), AntiNormal(5, "Anti-normal");

    companion object {
        fun fromNative(code: Int): FlightAssist = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unsupported flight assist: $code")
    }
}

data class FlightControl(
    val throttle: Double = 0.0,
    val translationX: Double = 0.0,
    val translationY: Double = 0.0,
    val translationZ: Double = 0.0,
    val rotationX: Double = 0.0,
    val rotationY: Double = 0.0,
    val rotationZ: Double = 0.0,
    val assist: FlightAssist = FlightAssist.Hold,
) {
    val hasManualInput: Boolean get() = translationX != 0.0 || translationY != 0.0 || translationZ != 0.0 ||
        rotationX != 0.0 || rotationY != 0.0 || rotationZ != 0.0
    val requiresRealTime: Boolean get() = throttle > 0.0 || hasManualInput

    fun toNativeValues(): DoubleArray {
        val values = doubleArrayOf(throttle, translationX, translationY, translationZ,
            rotationX, rotationY, rotationZ, assist.nativeCode.toDouble())
        require(values.all(Double::isFinite) && throttle in 0.0..1.0 &&
            values.sliceArray(1..6).all { it in -1.0..1.0 }) { "Invalid spacecraft control" }
        return values
    }

    companion object {
        val Cut = FlightControl(assist = FlightAssist.Manual)
    }
}

data class FlightState(
    val epochSeconds: Double,
    val revision: Long,
    val dryMassKg: Double,
    val propellantMassKg: Double,
    val totalMassKg: Double,
    val maxThrustN: Double,
    val commandedThrottle: Double,
    val remainingDeltaVMps: Double,
    val altitudeM: Double,
    val speedMps: Double,
    val radialSpeedMps: Double,
    val periapsisAltitudeM: Double?,
    val apoapsisAltitudeM: Double?,
    val eccentricity: Double?,
    val inclinationRadians: Double?,
    val periodSeconds: Double?,
    val attitudeX: Double, val attitudeY: Double, val attitudeZ: Double, val attitudeW: Double,
    val angularVelocityX: Double, val angularVelocityY: Double, val angularVelocityZ: Double,
    val assist: FlightAssist,
    val massFlowKgPerSecond: Double,
    val fullThrottleSecondsRemaining: Double,
    val moonSurfaceDistanceM: Double?,
    val relativeX: Double, val relativeY: Double, val relativeZ: Double,
    val relativeVelocityX: Double, val relativeVelocityY: Double, val relativeVelocityZ: Double,
    val hostRadiusM: Double,
    val hasValidOrbit: Boolean,
    val hasBoundOrbit: Boolean,
    val control: FlightControl,
    val actualMainAccelerationMps2: Double,
)

internal fun flightStateFromNative(values: DoubleArray): FlightState {
    require(values.size == 44 && values[0] == 1.0) { "Unsupported spacecraft telemetry schema" }
    val required = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11) + (17..26) + (28..43)
    require(required.all { values[it].isFinite() }) { "Non-finite spacecraft telemetry" }
    require(values[3] > 0.0 && values[4] >= 0.0 && values[5] > 0.0 && values[7] in 0.0..1.0) {
        "Invalid spacecraft mass or throttle telemetry"
    }
    val validOrbit = values[35] == 1.0
    val boundOrbit = validOrbit && values[36] == 1.0
    fun orbit(index: Int) = values[index].takeIf { validOrbit && it.isFinite() }
    val assist = FlightAssist.fromNative(values[24].toInt())
    return FlightState(
        epochSeconds = values[1], revision = values[2].toLong(), dryMassKg = values[3],
        propellantMassKg = values[4], totalMassKg = values[5], maxThrustN = values[6],
        commandedThrottle = values[7], remainingDeltaVMps = values[8], altitudeM = values[9],
        speedMps = values[10], radialSpeedMps = values[11], periapsisAltitudeM = orbit(12),
        apoapsisAltitudeM = orbit(13)?.takeIf { boundOrbit }, eccentricity = orbit(14),
        inclinationRadians = orbit(15), periodSeconds = orbit(16)?.takeIf { boundOrbit && it > 0.0 },
        attitudeX = values[17], attitudeY = values[18], attitudeZ = values[19], attitudeW = values[20],
        angularVelocityX = values[21], angularVelocityY = values[22], angularVelocityZ = values[23],
        assist = assist, massFlowKgPerSecond = values[25], fullThrottleSecondsRemaining = values[26],
        moonSurfaceDistanceM = values[27].takeIf { it.isFinite() && it >= 0.0 },
        relativeX = values[28], relativeY = values[29], relativeZ = values[30],
        relativeVelocityX = values[31], relativeVelocityY = values[32], relativeVelocityZ = values[33],
        hostRadiusM = values[34], hasValidOrbit = validOrbit, hasBoundOrbit = boundOrbit,
        control = FlightControl(values[7], values[37], values[38], values[39], values[40], values[41], values[42], assist),
        actualMainAccelerationMps2 = values[43],
    )
}
