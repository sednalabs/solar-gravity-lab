package com.sednalabs.solarlab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import com.sednalabs.solarlab.runtime.ExperimentDocumentCodec
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellNoticeTone
import com.sednalabs.solarlab.runtime.StoredExperiment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Real document-provider interaction, with exact checks against the live Rust world. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class LabDocumentTransferInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val resolver get() = instrumentation.targetContext.contentResolver
    private val evidencePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/documents-${System.currentTimeMillis()}/"

    @Test
    fun exportedExperimentReturnsThroughTheSystemPickerWithoutReplacingTheLiveWorld() {
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var invalidDocument: Uri? = null
        try {
            assertTrue("Document qualification uses the production stage", BuildConfig.STAGE_FIRST_CLIENT)
            composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            composeRule.waitUntil(15_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            val facade = composeRule.activity.runtimeFacadeForTesting
            composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
            runBlocking {
                facade.loadScenario("lab.earth-moon")
                facade.applyCommand(RuntimeCommand.PausePlayback)
            }
            advancePaused(facade, 600.0)
            val savedWorld = world(facade)
            assertEquals(3, savedWorld.bodyCount)
            val name = "Document ${System.currentTimeMillis()}"
            val fileName = "$name.sglexp"
            openCatalogue()
            composeRule.onNodeWithTag(LabTags.SAVE_NAME).performTextReplacement(name)
            composeRule.onNodeWithTag(LabTags.SAVE_COMMIT).performScrollTo().performClick()
            composeRule.waitUntil(20_000) {
                facade.experimentStatus.value.busyLabel == null && facade.savedExperiments.value.any { it.name == name }
            }
            closeSoftKeyboard()
            val saved = facade.savedExperiments.value.single { it.name == name }
            val originalIds = catalogueIds(facade)
            composeRule.onNodeWithTag(LabExperimentTags.export(saved.id)).performScrollTo().assertIsDisplayed()
            capture("01-saved-catalogue.png")

            // Cancelling the actual CreateDocument activity must not start an export or alter the world.
            composeRule.onNodeWithTag(LabExperimentTags.export(saved.id)).performClick()
            waitForPicker()
            capture("02-export-picker-cancel.png")
            cancelPicker()
            waitForCatalogue()
            assertEquals(originalIds, catalogueIds(facade))
            assertSameLiveWorld(savedWorld, world(facade))
            assertEquals(null, facade.experimentStatus.value.busyLabel)

            composeRule.onNodeWithTag(LabExperimentTags.export(saved.id)).performScrollTo().performClick()
            waitForPicker()
            openDownloads()
            setPickerText("export filename", { it.isEditable }, fileName)
            capture("03-export-picker-save.png")
            clickPicker("Save document") { it.isEnabled && it.text?.toString().equals("Save", ignoreCase = true) }
            waitForPickerToClose()
            composeRule.waitUntil(20_000) {
                facade.experimentStatus.value.busyLabel == null && facade.experimentStatus.value.notice == "Exported $name"
            }
            assertEquals(originalIds, catalogueIds(facade))
            assertSameLiveWorld(savedWorld, world(facade))

            // Keep a different, nonzero live world while importing the saved version.
            composeRule.onNodeWithText("Done").performClick()
            advancePaused(facade, 1_200.0)
            val liveWorld = world(facade)
            assertNotEquals(savedWorld.epochBits, liveWorld.epochBits)
            openCatalogue()
            composeRule.onNodeWithTag(LabExperimentTags.IMPORT).performScrollTo().performClick()
            waitForPicker()
            capture("04-import-picker-cancel.png")
            cancelPicker()
            waitForCatalogue()
            assertEquals(originalIds, catalogueIds(facade))
            assertSameLiveWorld(liveWorld, world(facade))

            importThroughPicker(fileName, "05-import-picker.png")
            composeRule.waitUntil(20_000) {
                facade.experimentStatus.value.busyLabel == null && facade.savedExperiments.value.size == originalIds.size + 1 &&
                    facade.experimentStatus.value.notice?.startsWith("Imported $name") == true
            }
            val imported = facade.savedExperiments.value.single { it.id !in originalIds }
            assertNotEquals(saved.id, imported.id)
            assertEquals(saved.name, imported.name)
            assertEquals(saved.scenarioId, imported.scenarioId)
            assertTrue(catalogueIds(facade).containsAll(originalIds))
            assertSameLiveWorld(liveWorld, world(facade))
            composeRule.onNodeWithTag(LabTags.load(imported.id)).performScrollTo().assertIsDisplayed()
            capture("06-imported-independent-copy.png")

            // The envelope and checksum are valid. Only authoritative native validation can reject this payload.
            val invalidName = "invalid-native-${System.currentTimeMillis()}.sglexp"
            val invalidBytes = ByteArrayOutputStream().also { stream ->
                ExperimentDocumentCodec.write(stream, StoredExperiment(
                    saved.copy(id = UUID.randomUUID().toString(), name = "Invalid native document"),
                    "This is not a native Solar Gravity Lab world.".toByteArray(Charsets.UTF_8),
                ))
            }.toByteArray()
            invalidDocument = writeDocument(invalidName, "application/octet-stream", Environment.DIRECTORY_DOWNLOADS + "/", invalidBytes)
            val beforeInvalid = catalogueIds(facade)
            importThroughPicker(invalidName, "07-invalid-native-picker.png")
            composeRule.waitUntil(20_000) {
                facade.experimentStatus.value.busyLabel == null && facade.experimentStatus.value.tone == ShellNoticeTone.Caution &&
                    facade.experimentStatus.value.notice?.startsWith("Could not import experiment:") == true
            }
            assertEquals("Rejected native data must not enter the catalogue", beforeInvalid, catalogueIds(facade))
            assertSameLiveWorld(liveWorld, world(facade))
            composeRule.onNodeWithText(requireNotNull(facade.experimentStatus.value.notice)).performScrollTo().assertIsDisplayed()
            capture("08-invalid-native-rejected.png")

            // Remove the original local version: opening must now use the independently imported copy.
            composeRule.onNodeWithTag(LabExperimentTags.remove(saved.id)).performScrollTo().performClick()
            composeRule.onNodeWithTag(LabExperimentTags.REMOVE_CONFIRM).performClick()
            composeRule.waitUntil(20_000) {
                facade.experimentStatus.value.busyLabel == null && saved.id !in catalogueIds(facade)
            }
            assertTrue(imported.id in catalogueIds(facade))
            assertSameLiveWorld(liveWorld, world(facade))
            composeRule.onNodeWithTag(LabTags.load(imported.id)).performScrollTo().performClick()
            composeRule.waitUntil(20_000) {
                facade.experimentStatus.value.busyLabel == null && facade.experimentStatus.value.openedId == imported.id &&
                    facade.uiState.value.snapshot?.epochSeconds?.toBits() == savedWorld.epochBits &&
                    composeRule.onAllNodesWithTag(LabTags.SAVED_DIALOG).fetchSemanticsNodes().isEmpty()
            }
            assertWorldContents(savedWorld, world(facade))
            composeRule.onNodeWithTag(LabTags.focus("earth")).performClick()
            waitForPresentedEarth()
            capture("09-reopened-export.png")
        } catch (error: Throwable) {
            runCatching { capture("failure.png") }
            runCatching { writeDocument("failure-tree.txt", "text/plain", evidencePath, pickerTree().toByteArray()) }
            throw error
        } finally {
            invalidDocument?.let { runCatching { resolver.delete(it, null, null) } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun advancePaused(facade: RuntimeFacade, seconds: Double) {
        val expected = requireNotNull(facade.uiState.value.snapshot).epochSeconds + seconds
        runBlocking { facade.applyCommand(RuntimeCommand.AdvanceEpoch(seconds)) }
        composeRule.waitUntil(20_000) {
            facade.uiState.value.manualAdvance == null && facade.uiState.value.snapshot?.epochSeconds?.toBits() == expected.toBits()
        }
        assertTrue(requireNotNull(facade.uiState.value.snapshot).paused)
    }

    private data class WorldTruth(val handle: Long, val scenario: String, val epochBits: Long, val bodyCount: Int,
        val bodies: Map<String, Pair<String, List<Long>>>)

    private fun world(facade: RuntimeFacade): WorldTruth = runBlocking {
        val state = facade.uiState.value
        val snapshot = requireNotNull(state.snapshot)
        assertTrue("The document journey compares paused worlds", snapshot.paused)
        WorldTruth(requireNotNull(state.sessionHandle), snapshot.scenarioId, snapshot.epochSeconds.toBits(), snapshot.bodyCount,
            listOf("sun", "earth", "moon").associateWith { id ->
                val body = requireNotNull(facade.inspectBody(id))
                body.bodyClass.name to body.scalarBits()
            })
    }

    private fun RuntimeBodyState.scalarBits() = listOf(massKg, sourceMassKg, radiusM,
        positionX, positionY, positionZ, velocityX, velocityY, velocityZ).map(Double::toBits)

    private fun assertSameLiveWorld(expected: WorldTruth, actual: WorldTruth) {
        assertEquals("A catalogue operation must not replace the live native session", expected.handle, actual.handle)
        assertWorldContents(expected, actual)
    }

    private fun assertWorldContents(expected: WorldTruth, actual: WorldTruth) {
        assertEquals(expected.scenario, actual.scenario)
        assertEquals("Epoch round-trips bit for bit", expected.epochBits, actual.epochBits)
        assertEquals(expected.bodyCount, actual.bodyCount)
        assertEquals("Every mass, radius, position and velocity round-trips bit for bit", expected.bodies, actual.bodies)
    }

    private fun catalogueIds(facade: RuntimeFacade) = facade.savedExperiments.value.map { it.id }.toSet()

    private fun openCatalogue() {
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
        composeRule.onNodeWithTag(LabTags.SAVED).performScrollTo().performClick()
        waitForCatalogue()
    }

    private fun waitForCatalogue() {
        composeRule.waitUntil(15_000) {
            appWindowIsActive() &&
                composeRule.onAllNodesWithTag(LabTags.SAVED_DIALOG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LabTags.SAVED_DIALOG).assertIsDisplayed()
        composeRule.waitForIdle()
    }

    private fun appWindowIsActive(): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        return try { root.packageName?.toString() == instrumentation.targetContext.packageName }
        finally { root.recycle() }
    }

    private fun importThroughPicker(fileName: String, screenshot: String) {
        composeRule.onNodeWithTag(LabExperimentTags.IMPORT).performScrollTo().performClick()
        waitForPicker()
        openDownloads()
        if (!awaitMaybe(2_000) { pickerHas { !it.isEditable && it.text?.toString() == fileName } }) {
            clickPicker("Search documents") {
                it.contentDescription?.toString().equals("Search", ignoreCase = true) ||
                    it.viewIdResourceName?.substringAfterLast('/') in setOf("menu_search", "action_menu_search")
            }
            setPickerText("document search", { it.isEditable }, fileName)
            val input = waitPickerNode("document search input") { it.isEditable }
            try {
                if (!input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                        assertTrue(automation.injectInputEvent(KeyEvent(action, KeyEvent.KEYCODE_ENTER), true))
                    }
                }
            } finally { input.recycle() }
        }
        waitPickerNode("document $fileName") { !it.isEditable && it.text?.toString() == fileName }.recycle()
        capture(screenshot)
        // MediaStore evidence can refresh the provider cursor; acquire the actual document row again.
        clickPicker("document $fileName") { !it.isEditable && it.text?.toString() == fileName }
        if (!awaitMaybe(2_000) { !pickerPresent() }) {
            clickPicker("Open selected document") {
                it.isEnabled && it.text?.toString()?.lowercase() in setOf("open", "select")
            }
        }
        waitForPickerToClose()
        waitForCatalogue()
    }

    private fun openDownloads() {
        if (!pickerHas { it.text?.toString() == "Downloads" && it.hasAncestorId("roots_list") }) {
            clickPicker("Document locations") {
                it.contentDescription?.toString()?.lowercase() in setOf("show roots", "show navigation drawer", "open navigation drawer")
            }
        }
        clickPicker("Downloads location") { it.text?.toString() == "Downloads" && it.hasAncestorId("roots_list") }
        await("Downloads location to open") {
            !pickerHas { it.text?.toString() == "Downloads" && it.hasAncestorId("roots_list") }
        }
    }

    private fun setPickerText(description: String, predicate: (AccessibilityNodeInfo) -> Boolean, text: String) {
        val node = waitPickerNode(description, predicate)
        try {
            assertTrue("The observed $description accepts text", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        } finally { node.recycle() }
        await("$description to contain the requested value") { pickerHas { predicate(it) && it.text?.toString() == text } }
    }

    private fun clickPicker(description: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val node = waitPickerNode(description, predicate)
        try { clickNode(node, description) } finally { node.recycle() }
    }

    private fun clickNode(node: AccessibilityNodeInfo, description: String) {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            val candidate = current
            if (candidate.isEnabled && candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                candidate.recycle()
                return
            }
            current = candidate.parent
            candidate.recycle()
        }
        throw AssertionError("The observed $description has no clickable target\n${pickerTree()}")
    }

    private fun AccessibilityNodeInfo.hasAncestorId(id: String): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(this)
        while (current != null) {
            val candidate = current
            if (candidate.viewIdResourceName?.substringAfterLast('/') == id) {
                candidate.recycle()
                return true
            }
            current = candidate.parent
            candidate.recycle()
        }
        return false
    }

    private fun waitForPicker() = await("Android document picker") { pickerPresent() }
    private fun waitForPickerToClose() = await("Android document picker to return") { !pickerPresent() }

    private fun cancelPicker() {
        repeat(4) {
            if (pickerPresent()) {
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                awaitMaybe(1_000) { !pickerPresent() }
            }
        }
        assertFalse("Back must cancel the system document picker", pickerPresent())
    }

    private fun pickerPresent(): Boolean = pickerRoot()?.let { it.recycle(); true } ?: false

    private fun pickerRoot(): AccessibilityNodeInfo? {
        automation.rootInActiveWindow?.let { root ->
            if (root.packageName?.toString()?.contains("documentsui") == true) return root
            root.recycle()
        }
        for (window in automation.windows) {
            try {
                if (!window.isActive && !window.isFocused) continue
                val root = window.root ?: continue
                if (root.packageName?.toString()?.contains("documentsui") == true) return root
                root.recycle()
            } finally { window.recycle() }
        }
        return null
    }

    private fun pickerHas(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean =
        findPickerNode(predicate)?.let { it.recycle(); true } ?: false

    private fun waitPickerNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await(description) { findPickerNode(predicate).also { found = it } != null }
        return requireNotNull(found)
    }

    private fun findPickerNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val root = pickerRoot() ?: return null
        return try { visit(root) } finally { root.recycle() }
    }

    private fun pickerTree(): String {
        val root = pickerRoot() ?: automation.rootInActiveWindow ?: return "No active accessibility window"
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= 220) return
            lines += "${" ".repeat(depth)}${node.className} id=${node.viewIdResourceName} text=${node.text} description=${node.contentDescription} enabled=${node.isEnabled} visible=${node.isVisibleToUser}"
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        try { visit(root, 0) } finally { root.recycle() }
        return lines.joinToString("\n")
    }

    private fun await(description: String, condition: () -> Boolean) {
        if (!awaitMaybe(20_000, condition)) {
            val tree = pickerTree()
            Log.e("SolarLabDocuments", "$description timed out\n$tree")
            throw AssertionError("Timed out waiting for $description\n$tree")
        }
    }

    private fun awaitMaybe(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        do {
            if (condition()) return true
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitForPresentedEarth() {
        fun find(view: View): SolarSystemRenderHostView? {
            if (view is SolarSystemRenderHostView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        composeRule.waitUntil(20_000) {
            var ready = false
            composeRule.runOnUiThread {
                val viewport = find(composeRule.activity.window.decorView)?.currentLabViewportState()
                val earth = viewport?.bodies?.firstOrNull { it.bodyId == "earth" }
                ready = viewport != null && earth != null && !earth.occluded && earth.radiusPx > 30f &&
                    earth.xPx in 0f..viewport.widthPx.toFloat() && earth.yPx in 0f..viewport.heightPx.toFloat()
            }
            ready
        }
    }

    private fun capture(name: String) {
        if (!pickerPresent()) composeRule.waitForIdle()
        runCatching { automation.waitForIdle(150, 2_000) }
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            writeDocument(name, "image/png", evidencePath, bytes)
        } finally { bitmap.recycle() }
    }

    private fun writeDocument(name: String, mimeType: String, path: String, bytes: ByteArray): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
