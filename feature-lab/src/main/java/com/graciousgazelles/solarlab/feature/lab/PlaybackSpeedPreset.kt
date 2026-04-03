package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.PhysicalConstants

enum class PlaybackSpeedPreset(
    val label: String,
    val simSecondsPerRealSecond: Double,
) {
    REALTIME_1X("1×", 1.0),
    X10("10×", 10.0),
    X60("60×", 60.0),
    X600("600×", 600.0),
    HOUR_PER_SECOND("1 h/s", 3600.0),
    SIX_HOURS_PER_SECOND("6 h/s", 6.0 * 3600.0),
    DAY_PER_SECOND("1 d/s", PhysicalConstants.DAY_SECONDS),
    WEEK_PER_SECOND("7 d/s", 7.0 * PhysicalConstants.DAY_SECONDS),
    MONTH_PER_SECOND("30 d/s", 30.0 * PhysicalConstants.DAY_SECONDS),
}
