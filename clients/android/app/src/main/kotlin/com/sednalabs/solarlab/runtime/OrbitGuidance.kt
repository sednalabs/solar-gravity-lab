package com.sednalabs.solarlab.runtime

import org.json.JSONObject

/** Native instantaneous two-body guidance. It is not a propagated burn plan. */
enum class OrbitRegime(val nativeCode: Int, val wireName: String) {
    Elliptic(1, "elliptic"),
    NearCircular(2, "nearCircular"),
    Hyperbolic(3, "hyperbolic"),
    NearParabolic(4, "nearParabolic");

    companion object {
        fun fromNative(code: Int): OrbitRegime = entries.firstOrNull { it.nativeCode == code }
            ?: error("Unsupported orbital guidance regime: $code")

        fun fromWire(name: String): OrbitRegime = entries.firstOrNull { it.wireName == name }
            ?: error("Unsupported orbital guidance regime: $name")
    }
}

data class OrbitGuidance(
    val regime: OrbitRegime,
    val timeToPeriapsisSeconds: Double?,
    val timeToApoapsisSeconds: Double?,
    /** Ideal instantaneous impulse at the current position, including radial cancellation. */
    val circularisationDeltaVMps: Double,
) {
    init {
        require(circularisationDeltaVMps.isFinite() && circularisationDeltaVMps >= 0.0)
        require(listOfNotNull(timeToPeriapsisSeconds, timeToApoapsisSeconds).all { it.isFinite() && it >= 0.0 })
        when (regime) {
            OrbitRegime.NearCircular, OrbitRegime.NearParabolic ->
                require(timeToPeriapsisSeconds == null && timeToApoapsisSeconds == null)
            OrbitRegime.Hyperbolic -> require(timeToApoapsisSeconds == null)
            OrbitRegime.Elliptic -> Unit
        }
    }
}

internal fun orbitGuidanceFromNative(values: DoubleArray): OrbitGuidance? {
    if (values.size == 44) return null // Version 1 has no navigation fields.
    require(values.size == 48 && values[0] == 2.0)
    require((44..47).all { values[it].isFinite() }) { "Non-finite orbital guidance" }
    require(values[44] == values[44].toInt().toDouble()) { "Invalid orbital guidance regime" }
    if (values[44] == 0.0) {
        require((45..47).all { values[it] == -1.0 }) { "Unavailable guidance contains values" }
        return null
    }
    fun time(index: Int): Double? {
        val value = values[index]
        require(value == -1.0 || value >= 0.0) { "Invalid apsis time" }
        return value.takeUnless { it == -1.0 }
    }
    return OrbitGuidance(OrbitRegime.fromNative(values[44].toInt()), time(45), time(46), values[47])
}

internal fun orbitGuidanceFromJson(value: JSONObject?): OrbitGuidance? {
    if (value == null) return null
    fun time(key: String): Double? = if (value.isNull(key)) null else value.getDouble(key)
    return OrbitGuidance(OrbitRegime.fromWire(value.getString("regime")),
        time("timeToPeriapsisSeconds"), time("timeToApoapsisSeconds"),
        value.getDouble("circularisationDeltaVMps"))
}
