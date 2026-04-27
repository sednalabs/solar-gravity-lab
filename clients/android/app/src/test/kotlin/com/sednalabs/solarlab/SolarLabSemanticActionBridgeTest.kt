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
    fun parseSemanticCommand_mapsNonDestructiveStageCommands() {
        assertEquals(
            SolarLabSemanticAction.ResetCamera,
            SolarLabSemanticActionBridge.parseSemanticCommand(
                action = SolarLabSemanticActionBridge.INTENT_ACTION,
                command = "reset_camera",
                bodyQuery = null,
            ),
        )
        assertEquals(
            SolarLabSemanticAction.OpenImmersive,
            SolarLabSemanticActionBridge.parseSemanticCommand(
                action = SolarLabSemanticActionBridge.INTENT_ACTION,
                command = "open_immersive",
                bodyQuery = null,
            ),
        )
        assertEquals(
            SolarLabSemanticAction.ReturnToSandbox,
            SolarLabSemanticActionBridge.parseSemanticCommand(
                action = SolarLabSemanticActionBridge.INTENT_ACTION,
                command = "return_to_sandbox",
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
    fun resolveLoadScenarioSemanticRouting_doesNotEnterMirrorForUnknownScenarioFromSandbox() {
        val routing = resolveLoadScenarioSemanticRouting(
            runtimeMirrorAvailable = true,
            currentlyInRuntimeMirror = false,
            scenarioKnown = false,
        )

        assertFalse(routing.shouldEnterRuntimeMirror)
        assertFalse(routing.shouldDeliverAction)
    }

    @Test
    fun resolveLoadScenarioSemanticRouting_allowsKnownScenarioToEnterMirror() {
        val routing = resolveLoadScenarioSemanticRouting(
            runtimeMirrorAvailable = true,
            currentlyInRuntimeMirror = false,
            scenarioKnown = true,
        )

        assertTrue(routing.shouldEnterRuntimeMirror)
        assertTrue(routing.shouldDeliverAction)
    }

    @Test
    fun resolveLoadScenarioSemanticRouting_deliversUnknownScenarioInsideMirrorForNotice() {
        val routing = resolveLoadScenarioSemanticRouting(
            runtimeMirrorAvailable = true,
            currentlyInRuntimeMirror = true,
            scenarioKnown = false,
        )

        assertFalse(routing.shouldEnterRuntimeMirror)
        assertTrue(routing.shouldDeliverAction)
    }

    @Test
    fun semanticActionsEnabled_tracksDebugBuildGate() {
        assertEquals(BuildConfig.DEBUG, SolarLabSemanticActionBridge.semanticActionsEnabled())
        assertFalse(BuildConfig.BUILD_TYPE == "prerelease" && SolarLabSemanticActionBridge.semanticActionsEnabled())
    }
}
