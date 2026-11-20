package com.sednalabs.solarlab.runtime

import org.json.JSONObject

/** Method choice for conservative coast. Powered flight uses the positive second-order split. */
enum class CoastIntegrator(val nativeCode: Int) {
    Leapfrog(0), FourthOrder(1), AdaptiveEncounter(2);

    companion object {
        fun fromNative(code: Int): CoastIntegrator = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unsupported coast integrator: $code")
    }
}

data class FlightDynamicsSettings(
    val integrator: CoastIntegrator,
    /** Read-only count; changing the coast method preserves authored gravity models. */
    val harmonicCount: Int = 0,
)

/** Read-only finite-burn calculation made by the same Rust solver as live flight. */
data class FlightBurnPreview(
    val beforePeriapsisM: Double?,
    val afterPeriapsisM: Double?,
    val beforeApoapsisM: Double?,
    val afterApoapsisM: Double?,
    val fuelUsedKg: Double,
    val elapsedSeconds: Double,
    val warnings: List<String>,
    val epochSeconds: Double,
    val revision: Long,
    val beforeGuidance: OrbitGuidance? = null,
    val afterGuidance: OrbitGuidance? = null,
)

internal fun flightDynamicsFromNative(values: DoubleArray): FlightDynamicsSettings {
    require(values.size == 3 && values[0] == 1.0 && values.all(Double::isFinite)) { "Invalid flight dynamics schema" }
    require(values[1] == values[1].toInt().toDouble() && values[2] in 0.0..64.0 && values[2] == values[2].toInt().toDouble())
    return FlightDynamicsSettings(CoastIntegrator.fromNative(values[1].toInt()), values[2].toInt())
}

internal fun flightBurnPreviewFromNative(bytes: ByteArray): FlightBurnPreview {
    require(bytes.size <= 16_384) { "Burn preview payload exceeds its bound" }
    val value = JSONObject(bytes.toString(Charsets.UTF_8))
    fun scalar(key: String) = value.getDouble(key).also { require(it.isFinite()) { "Non-finite burn preview" } }
    fun optional(key: String): Double? = if (value.isNull(key)) null else scalar(key)
    val warnings = value.getJSONArray("warnings")
    require(warnings.length() <= 16)
    val result = FlightBurnPreview(optional("beforePeriapsisM"), optional("afterPeriapsisM"),
        optional("beforeApoapsisM"), optional("afterApoapsisM"), scalar("fuelUsedKg"), scalar("elapsedSeconds"),
        (0 until warnings.length()).map { warnings.getString(it) }, scalar("epochSeconds"), value.getLong("revision"),
        orbitGuidanceFromJson(value.optJSONObject("beforeGuidance")),
        orbitGuidanceFromJson(value.optJSONObject("afterGuidance")))
    require(result.fuelUsedKg >= 0.0 && result.elapsedSeconds > 0.0 && result.epochSeconds >= 0.0 && result.revision >= 0)
    return result
}
