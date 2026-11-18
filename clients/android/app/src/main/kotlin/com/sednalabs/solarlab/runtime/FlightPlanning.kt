package com.sednalabs.solarlab.runtime

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
)
