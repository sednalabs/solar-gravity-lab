package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl

/** Native guidance demand must never become an implicit held touchscreen input. */
internal fun manualFlightControlBase(
    current: FlightControl,
    guidanceActive: Boolean,
    manualInputOwned: Boolean,
): FlightControl = if (guidanceActive && !manualInputOwned) current.copy(
    throttle = 0.0, translationX = 0.0, translationY = 0.0, translationZ = 0.0,
    rotationX = 0.0, rotationY = 0.0, rotationZ = 0.0,
) else current

/** Compose simultaneous held controls from input intent, independently of physical feedback. */
internal fun flightAxisIntent(current: FlightControl, translation: Boolean, index: Int, held: Boolean): FlightControl {
    val value = if (held) 1.0 else 0.0
    return if (translation) when (index) {
        0 -> current.copy(translationX = -value)
        1 -> current.copy(translationY = value)
        2 -> current.copy(translationZ = value)
        3 -> current.copy(translationX = value)
        4 -> current.copy(translationY = -value)
        else -> current.copy(translationZ = -value)
    } else when (index) {
        0 -> current.copy(rotationY = -value, assist = FlightAssist.Manual)
        1 -> current.copy(rotationX = -value, assist = FlightAssist.Manual)
        2 -> current.copy(rotationZ = -value, assist = FlightAssist.Manual)
        3 -> current.copy(rotationY = value, assist = FlightAssist.Manual)
        4 -> current.copy(rotationX = value, assist = FlightAssist.Manual)
        else -> current.copy(rotationZ = value, assist = FlightAssist.Manual)
    }
}
