package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlightControlIntentTest {
    private val guidedDemand = FlightControl(throttle = .7, translationX = -.8, translationY = .2,
        translationZ = 1.0, rotationX = .1, rotationY = -.2, rotationZ = .3, assist = FlightAssist.Hold)

    @Test fun guidedOffAxisDemandCannotRemainAfterManualPressAndRelease() {
        val pressed = flightAxisIntent(manualFlightControlBase(guidedDemand, true, false), true, 3, true)
        assertEquals(1.0, pressed.translationX, 0.0)
        assertEquals(0.0, pressed.translationY, 0.0)
        assertEquals(0.0, pressed.translationZ, 0.0)
        assertEquals(0.0, pressed.throttle, 0.0)
        val released = flightAxisIntent(manualFlightControlBase(pressed, true, true), true, 3, false)
        assertNeutral(released)
    }

    @Test fun simultaneousManualDirectionsComposeWhileGuidanceTelemetryIsStillInFlight() {
        var intent = flightAxisIntent(manualFlightControlBase(guidedDemand, true, false), true, 3, true)
        intent = flightAxisIntent(manualFlightControlBase(intent, true, true), true, 1, true)
        intent = flightAxisIntent(manualFlightControlBase(intent, true, true), true, 3, false)
        assertEquals(0.0, intent.translationX, 0.0)
        assertEquals(1.0, intent.translationY, 0.0)
        assertEquals(0.0, intent.translationZ, 0.0)
        intent = flightAxisIntent(manualFlightControlBase(intent, false, true), true, 1, false)
        assertNeutral(intent)
    }

    @Test fun throttleAndAttitudeTakeoverDoNotInheritAnyAutomatedAxes() {
        val throttle = manualFlightControlBase(guidedDemand, true, false).copy(throttle = .25)
        assertEquals(.25, throttle.throttle, 0.0)
        assertNeutral(throttle.copy(throttle = 0.0))
        val assisted = manualFlightControlBase(guidedDemand, true, false).copy(assist = FlightAssist.Prograde)
        assertEquals(FlightAssist.Prograde, assisted.assist)
        assertNeutral(assisted)
        val pitched = flightAxisIntent(manualFlightControlBase(guidedDemand, true, false), false, 1, true)
        assertEquals(-1.0, pitched.rotationX, 0.0)
        assertEquals(FlightAssist.Manual, pitched.assist)
        assertNeutral(flightAxisIntent(pitched, false, 1, false))
    }

    private fun assertNeutral(control: FlightControl) {
        assertEquals(0.0, control.throttle, 0.0)
        assertEquals(0.0, control.translationX, 0.0)
        assertEquals(0.0, control.translationY, 0.0)
        assertEquals(0.0, control.translationZ, 0.0)
        assertEquals(0.0, control.rotationX, 0.0)
        assertEquals(0.0, control.rotationY, 0.0)
        assertEquals(0.0, control.rotationZ, 0.0)
        assertFalse(control.requiresRealTime)
    }
}
