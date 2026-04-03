package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.PhysicalConstants

enum class StepQuantumPreset(
    val label: String,
    val seconds: Double,
) {
    ONE_SECOND("1 s", 1.0),
    TEN_SECONDS("10 s", 10.0),
    ONE_MINUTE("1 min", 60.0),
    TEN_MINUTES("10 min", 10.0 * 60.0),
    ONE_HOUR("1 h", 3600.0),
    SIX_HOURS("6 h", 6.0 * 3600.0),
    ONE_DAY("1 d", PhysicalConstants.DAY_SECONDS),
    ONE_WEEK("7 d", 7.0 * PhysicalConstants.DAY_SECONDS),
    THIRTY_DAYS("30 d", 30.0 * PhysicalConstants.DAY_SECONDS),
}
