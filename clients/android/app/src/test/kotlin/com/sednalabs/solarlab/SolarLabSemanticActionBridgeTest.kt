package com.sednalabs.solarlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarLabSemanticActionBridgeTest {
    @Test
    fun parseSemanticCommand_mapsFocusBodyCommand() {
        val action = SolarLabSemanticActionBridge.parseSemanticCommand(
            action = SolarLabSemanticActionBridge.INTENT_ACTION,
            command = "focus_body",
            bodyQuery = "earth",
        )

        assertEquals(SolarLabSemanticAction.FocusBody("earth"), action)
    }

    @Test
    fun parseSemanticCommand_mapsScenarioLoadCommand() {
        val action = SolarLabSemanticActionBridge.parseSemanticCommand(
            action = SolarLabSemanticActionBridge.INTENT_ACTION,
            command = "load_scenario",
            bodyQuery = null,
            scenarioId = "showcase.jupiter-system",
        )

        assertEquals(SolarLabSemanticAction.LoadScenario("showcase.jupiter-system"), action)
    }

    @Test
    fun parseSemanticCommand_returnsNullForMissingBodyQuery() {
        val action = SolarLabSemanticActionBridge.parseSemanticCommand(
            action = SolarLabSemanticActionBridge.INTENT_ACTION,
            command = "focus_body",
            bodyQuery = null,
        )

        assertNull(action)
    }

    @Test
    fun parseSemanticCommand_mapsResetCameraCommand() {
        assertEquals(
            SolarLabSemanticAction.ResetCamera,
            SolarLabSemanticActionBridge.parseSemanticCommand(
                action = SolarLabSemanticActionBridge.INTENT_ACTION,
                command = "reset_camera",
                bodyQuery = null,
            ),
        )
    }

    @Test
    fun submit_replaysUntilClearedForColdStartDelivery() {
        SolarLabSemanticActionBridge.clearPendingReplay()

        val action = SolarLabSemanticAction.FocusBody("earth")
        assertTrue(SolarLabSemanticActionBridge.submit(action))
        assertEquals(listOf(action), SolarLabSemanticActionBridge.commands.replayCache)

        SolarLabSemanticActionBridge.clearPendingReplay()
        assertTrue(SolarLabSemanticActionBridge.commands.replayCache.isEmpty())
    }

    @Test
    fun shouldAttachRuntimeRenderHost_tracksSessionRatherThanTransientPacketMetadata() {
        assertFalse(
            shouldAttachRuntimeRenderHost(
                runtimeSessionHandle = 0L,
                renderHostEstablished = false,
                hostedDebugModeEnabled = false,
                hostedDebugModeApplied = false,
            )
        )
        assertTrue(
            shouldAttachRuntimeRenderHost(
                runtimeSessionHandle = 42L,
                renderHostEstablished = false,
                hostedDebugModeEnabled = false,
                hostedDebugModeApplied = false,
            )
        )
        assertTrue(
            shouldAttachRuntimeRenderHost(
                runtimeSessionHandle = 0L,
                renderHostEstablished = true,
                hostedDebugModeEnabled = false,
                hostedDebugModeApplied = false,
            )
        )
    }

    @Test
    fun shouldAttachRuntimeRenderHost_defersHostedDebugUntilPauseIsApplied() {
        assertFalse(
            shouldAttachRuntimeRenderHost(
                runtimeSessionHandle = 42L,
                renderHostEstablished = false,
                hostedDebugModeEnabled = true,
                hostedDebugModeApplied = false,
            )
        )
        assertTrue(
            shouldAttachRuntimeRenderHost(
                runtimeSessionHandle = 42L,
                renderHostEstablished = false,
                hostedDebugModeEnabled = true,
                hostedDebugModeApplied = true,
            )
        )
    }

    @Test
    fun semanticActionsEnabled_tracksDebugBuildGate() {
        assertEquals(BuildConfig.DEBUG, SolarLabSemanticActionBridge.semanticActionsEnabled())
        assertFalse(BuildConfig.BUILD_TYPE == "prerelease" && SolarLabSemanticActionBridge.semanticActionsEnabled())
    }
}
