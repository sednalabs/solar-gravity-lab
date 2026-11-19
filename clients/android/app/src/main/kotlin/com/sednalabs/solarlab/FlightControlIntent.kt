package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl

/** UI prevention complements the native joint's command gate, including late queued input. */
internal fun flightControlForAttachment(intent: FlightControl, attached: Boolean): FlightControl =
    if (attached) FlightControl.Cut else intent

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

/** Each touch owns one button, not an entire axis. Opposing touches cancel;
 * releasing either restores the still-held direction. All six demands are
 * rebuilt so a mode change, cut or guidance handoff cannot retain stale axes. */
internal fun flightHeldAxisIntent(
    current: FlightControl,
    translation: Boolean,
    heldButtons: Set<Int>,
    precision: Boolean,
): FlightControl {
    require(heldButtons.all { it in 0..5 }) { "Unknown flight control button" }
    val gain = if (precision) 0.2 else 1.0
    fun axis(positive: Int, negative: Int) =
        ((if (positive in heldButtons) 1.0 else 0.0) - (if (negative in heldButtons) 1.0 else 0.0)) * gain
    val neutral = current.copy(translationX = 0.0, translationY = 0.0, translationZ = 0.0,
        rotationX = 0.0, rotationY = 0.0, rotationZ = 0.0)
    return if (translation) neutral.copy(translationX = axis(3, 0), translationY = axis(1, 4), translationZ = axis(2, 5))
    else neutral.copy(rotationX = axis(4, 1), rotationY = axis(3, 0), rotationZ = axis(5, 2),
        assist = if (heldButtons.isEmpty()) current.assist else FlightAssist.Manual)
}
