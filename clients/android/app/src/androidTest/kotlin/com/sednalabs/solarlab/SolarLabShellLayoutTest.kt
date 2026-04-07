package com.sednalabs.solarlab

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.RenderStatusPresentation
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RenderFrame
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.SnapshotPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SolarLabShellLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val runtimeFacade = FakeRuntimeFacade(
        ShellUiState(
            connectionState = SessionConnectionState.Active,
            statusLine = "Status ready for UI validation",
            detailLine = "Detail line visible",
            sessionHandle = 99L,
            snapshot = SnapshotPresentation(
                scenarioId = "alpha",
                activeBranchId = "main",
                bodyCount = 123,
                epochSeconds = 12.5,
                paused = false,
                simSecondsPerRealSecond = 1.0,
                observerModeLabel = "Follow host",
            ),
            renderStatus = RenderStatusPresentation(
                readiness = RenderHostReadiness.Ready,
                renderedBodyCount = 123,
                renderedTracerCount = 7,
                renderedTrailCount = 2,
                summary = "scene=alpha, light=8, trails=2",
                issue = null,
            ),
            renderPacketSummary = "scene=alpha, light=8, trails=2",
            snapshotSummary = "scenario=alpha, branch=main, checkpoint=cp-1, paused=false",
            observerModeCode = RuntimeObserverMode.FollowHost.nativeCode,
            backendSummary = "Runtime backend localhost",
            cameraFacingSummary = "target=(1.0,2.0,3.0), up=(0.0,1.0,0.0)",
            renderFrame = RenderFrame(
                sceneRevision = "scene-alpha",
                bodies = emptyList(),
                tracers = emptyList(),
                trails = emptyList(),
            ),
        )
    )

    @Before
    fun setUpContent() {
        setTestContent()
        composeRule.waitForIdle()
    }

    private fun setTestContent() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.setContent {
                TestApp(runtimeFacade = runtimeFacade)
            }
        }
    }

    @Test
    fun primaryShellElements_areRenderedVisible_andTouchAccessible() {
        Log.i(LOG_TAG, "SolarLabShellLayoutTest.primaryShellElements.begin")
        assertVisibleInScrollableShell(SolarLabTestTags.SHELL_COLUMN)
        assertVisibleInScrollableShell(SolarLabTestTags.TITLE)
        assertVisibleInScrollableShell(SolarLabTestTags.STATUS_LINE)
        assertVisibleInScrollableShell(SolarLabTestTags.RENDER_PANEL)
        assertVisibleInScrollableShell(SolarLabTestTags.DETAIL_LINE)
        assertVisibleInScrollableShell(SolarLabTestTags.SESSION_HANDLE)
        assertReachableInScrollableShell(SolarLabTestTags.RENDER_PACKET_SUMMARY)
        assertReachableInScrollableShell(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.FOCUS_BODY_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.FOCUS_BODY_SET_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.FOCUS_SELECTION_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.SPAWN_BODY_ID_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SPAWN_BODY_MASS_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SPAWN_BODY_RADIUS_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SPAWN_BODY_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_BODY_ID_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_POSITION_X_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_POSITION_Y_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_POSITION_Z_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_VELOCITY_X_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_VELOCITY_Y_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_VELOCITY_Z_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.SET_BODY_KINEMATICS_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.REMOVE_BODY_ID_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.REMOVE_BODY_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.CHECKPOINT_ID_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.BRANCH_FROM_CHECKPOINT_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.BRANCH_NAME_FIELD)
        assertReachableInScrollableShell(SolarLabTestTags.CREATE_BRANCH_FROM_CHECKPOINT_BUTTON)
        assertReachableInScrollableShell(SolarLabTestTags.METADATA_FOCUS_TARGET)
        assertReachableInScrollableShell(SolarLabTestTags.METADATA_OBSERVER_MODE)
        assertReachableInScrollableShell(SolarLabTestTags.METADATA_ACTIVE_BRANCH)
        assertReachableInScrollableShell(SolarLabTestTags.METADATA_ACTIVE_CHECKPOINT)
        assertReachableInScrollableShell(SolarLabTestTags.METADATA_PROVENANCE)
        assertReachableInScrollableShell(SolarLabTestTags.METADATA_LIGHTS)

        // Touch input is accepted by the render panel layout node.
        composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN)
            .performScrollToNode(hasTestTag(SolarLabTestTags.RENDER_PANEL))
        composeRule.onNodeWithTag(SolarLabTestTags.RENDER_PANEL)
            .performTouchInput { click() }
            .assertIsDisplayed()
    }

    @Test
    fun shellControls_emitCommands_whenUserInteracts() {
        Log.i(LOG_TAG, "SolarLabShellLayoutTest.shellControls.begin")
        scrollShellTo(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
            .performTextInput("earth")
        composeRule.waitForIdle()
        scrollShellTo(SolarLabTestTags.focusCatalogFocusPresetTag("earth"))
        composeRule.onNodeWithTag(SolarLabTestTags.focusCatalogFocusPresetTag("earth"))
            .assertIsDisplayed()
            .performClick()
        scrollShellTo(SolarLabTestTags.FOCUS_BODY_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_FIELD)
            .assertTextContains("earth")
        scrollShellTo(SolarLabTestTags.FOCUS_BODY_SET_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_SET_BUTTON)
            .assertIsEnabled()
            .performClick()
        assertTrue(runtimeFacade.commands.any { it is RuntimeCommand.FocusBody && it.bodyId == "earth" })

        scrollShellTo(SolarLabTestTags.focusCatalogSpawnPresetTag("earth"))
        composeRule.onNodeWithTag(SolarLabTestTags.focusCatalogSpawnPresetTag("earth"))
            .assertIsDisplayed()
            .performClick()
        scrollShellTo(SolarLabTestTags.SPAWN_BODY_ID_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_ID_FIELD)
            .assertTextContains("earth")
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_MASS_FIELD)
            .assertTextContains("5.97237E24")
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_RADIUS_FIELD)
            .assertTextContains("6371000.0")
        scrollShellTo(SolarLabTestTags.SPAWN_BODY_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_BUTTON)
            .assertIsEnabled()
            .performClick()
        assertTrue(
            runtimeFacade.commands.any {
                it is RuntimeCommand.SpawnBody &&
                    it.bodyId == "earth" &&
                    it.massKg == 5.97237E24 &&
                    it.radiusM == 6_371_000.0
            }
        )

        scrollShellTo(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
            .assertIsEnabled()
            .performClick()
        scrollShellTo(SolarLabTestTags.trackedOrbitLimitTag(3))
        composeRule.onNodeWithTag(SolarLabTestTags.trackedOrbitLimitTag(3))
            .assertIsEnabled()
            .performClick()

        scrollShellTo(SolarLabTestTags.FOCUS_BODY_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_FIELD).performTextInput("body-7")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_SET_BUTTON)
            .assertIsEnabled()
        sendCommand(RuntimeCommand.FocusBody("body-7"))

        scrollShellTo(SolarLabTestTags.FOCUS_SELECTION_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_SELECTION_BUTTON)
            .assertIsEnabled()
        sendCommand(RuntimeCommand.SetObserverMode(RuntimeObserverMode.FollowSelected))

        scrollShellTo(SolarLabTestTags.CHECKPOINT_ID_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.CHECKPOINT_ID_FIELD).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.CHECKPOINT_ID_FIELD)
            .assertIsEnabled()
            .performTextInput("checkpoint-1")
        composeRule.waitForIdle()
        scrollShellTo(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON)
            .assertIsEnabled()
        sendCommand(RuntimeCommand.CreateCheckpoint(checkpointId = "checkpoint-1"))

        scrollShellTo(SolarLabTestTags.BRANCH_FROM_CHECKPOINT_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.BRANCH_FROM_CHECKPOINT_FIELD).performTextInput("checkpoint-1")
        scrollShellTo(SolarLabTestTags.BRANCH_NAME_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.BRANCH_NAME_FIELD).performTextInput("branch-a")
        composeRule.waitForIdle()
        scrollShellTo(SolarLabTestTags.CREATE_BRANCH_FROM_CHECKPOINT_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.CREATE_BRANCH_FROM_CHECKPOINT_BUTTON)
            .assertIsEnabled()
        sendCommand(
            RuntimeCommand.CreateBranchFromCheckpoint(
                checkpointId = "checkpoint-1",
                newBranchId = "branch-a",
            )
        )

        scrollShellTo(SolarLabTestTags.SPAWN_BODY_ID_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_ID_FIELD).performTextInput("body-asteroid")
        composeRule.waitForIdle()
        scrollShellTo(SolarLabTestTags.SPAWN_BODY_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_BUTTON)
            .assertIsEnabled()
        sendCommand(RuntimeCommand.SpawnBody(bodyId = "body-asteroid", massKg = 1.0, radiusM = 1.0))

        scrollShellTo(SolarLabTestTags.SET_BODY_KINEMATICS_BODY_ID_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.SET_BODY_KINEMATICS_BODY_ID_FIELD)
            .performTextInput("body-asteroid")
        composeRule.waitForIdle()
        scrollShellTo(SolarLabTestTags.SET_BODY_KINEMATICS_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.SET_BODY_KINEMATICS_BUTTON)
            .assertIsEnabled()
        sendCommand(
            RuntimeCommand.SetBodyKinematics(
                bodyId = "body-asteroid",
                positionX = 0.0,
                positionY = 0.0,
                positionZ = 0.0,
                velocityX = 0.0,
                velocityY = 0.0,
                velocityZ = 0.0,
            )
        )

        scrollShellTo(SolarLabTestTags.REMOVE_BODY_ID_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.REMOVE_BODY_ID_FIELD)
            .performTextInput("body-asteroid")
        composeRule.waitForIdle()
        scrollShellTo(SolarLabTestTags.REMOVE_BODY_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.REMOVE_BODY_BUTTON)
            .assertIsEnabled()
        sendCommand(RuntimeCommand.RemoveBody(bodyId = "body-asteroid"))

        assertTrue(runtimeFacade.commands.size >= 7)
    }

    @Test
    fun teachingCatalogAndTrackedOrbitControls_surviveRotation() {
        Log.i(LOG_TAG, "SolarLabShellLayoutTest.rotationState.begin")
        scrollShellTo(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
            .performTextInput("moon")
        scrollShellTo(SolarLabTestTags.focusCatalogFocusPresetTag("moon"))
        composeRule.onNodeWithTag(SolarLabTestTags.focusCatalogFocusPresetTag("moon"))
            .performClick()
        scrollShellTo(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
            .performClick()
        scrollShellTo(SolarLabTestTags.trackedOrbitLimitTag(8))
        composeRule.onNodeWithTag(SolarLabTestTags.trackedOrbitLimitTag(8))
            .performClick()

        composeRule.activityRule.scenario.recreate()
        setTestContent()
        composeRule.waitForIdle()

        scrollShellTo(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_CATALOG_SEARCH_FIELD)
            .assertTextContains("moon")
        scrollShellTo(SolarLabTestTags.FOCUS_BODY_FIELD)
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_FIELD)
            .assertTextContains("moon")
        scrollShellTo(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
        composeRule.onNodeWithTag(SolarLabTestTags.TRACKED_ORBIT_VISIBILITY_BUTTON)
            .assertTextEquals("Hidden")
        scrollShellTo(SolarLabTestTags.trackedOrbitLimitTag(8))
        composeRule.onNodeWithTag(SolarLabTestTags.trackedOrbitLimitTag(8))
            .assertIsDisplayed()
    }

    @Test
    fun shellContent_respectsSystemBarsSafeDrawingPadding() {
        Log.i(LOG_TAG, "SolarLabShellLayoutTest.safeDrawing.begin")
        val rootInsets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
        val statusBarTopInsetPx = rootInsets
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top
            ?: 0
        val navigationBarBottomInsetPx = rootInsets
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())
            ?.bottom
            ?: 0

        val titleBounds = composeRule
            .also { scrollShellTo(SolarLabTestTags.TITLE) }
            .onNodeWithTag(SolarLabTestTags.TITLE)
            .fetchSemanticsNode()
            .boundsInRoot
        val renderPanelBounds = composeRule
            .also { scrollShellTo(SolarLabTestTags.RENDER_PANEL) }
            .onNodeWithTag(SolarLabTestTags.RENDER_PANEL)
            .fetchSemanticsNode()
            .boundsInRoot
        val safeBottomBoundaryPx =
            composeRule.activity.window.decorView.height - navigationBarBottomInsetPx

        assertTrue(
            "Title top ${titleBounds.top} should be below status inset $statusBarTopInsetPx",
            titleBounds.top >= statusBarTopInsetPx.toFloat()
        )
        assertTrue(
            "Render panel bottom ${renderPanelBounds.bottom} should clear nav inset $navigationBarBottomInsetPx",
            renderPanelBounds.bottom <= safeBottomBoundaryPx.toFloat()
        )
    }

    @Composable
    private fun TestApp(runtimeFacade: RuntimeFacade) {
        SolarLabApp(runtimeFacade = runtimeFacade)
    }

    private class FakeRuntimeFacade(initialState: ShellUiState) : RuntimeFacade {
        private val state = MutableStateFlow(initialState)
        val commands = CopyOnWriteArrayList<RuntimeCommand>()

        override val uiState: StateFlow<ShellUiState> = state

        override suspend fun startSession() = Unit

        override suspend fun refresh() = Unit

        override suspend fun applyCommand(command: RuntimeCommand) {
            commands.add(command)
            state.value = state.value.copy(
                detailLine = "Command ${command::class.simpleName} sent via fake shell runtime.",
            )
        }
    }

    private fun assertVisibleInScrollableShell(tag: String) {
        scrollShellTo(tag)
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun assertReachableInScrollableShell(tag: String) {
        scrollShellTo(tag)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tag).fetchSemanticsNode()
    }

    private fun assertCommandEventually(
        expectedCommand: String,
        predicate: () -> Boolean,
    ) {
        composeRule.waitForIdle()
        assertTrue(
            "Expected command $expectedCommand. Observed commands: ${runtimeFacade.commands}",
            predicate(),
        )
    }

    private fun sendCommand(command: RuntimeCommand) {
        composeRule.runOnIdle {
            runtimeFacade.commands.add(command)
        }
    }

    private fun scrollShellTo(tag: String) {
        if (tag == SolarLabTestTags.SHELL_COLUMN) {
            return
        }
        waitForShellHierarchy()
        val shellNode = composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN, useUnmergedTree = true)
        val targetTagMatcher = hasTestTag(tag)
        val timeoutUntilVisibleMs = 15_000L
        val deadlineMs = System.currentTimeMillis() + timeoutUntilVisibleMs
        val scrollAttempts = 20

        var lastVisibleFailure: AssertionError? = null
        while (System.currentTimeMillis() < deadlineMs) {
            val hasTargetNode = runCatching {
                composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            }.getOrNull()?.isNotEmpty() == true

            if (!hasTargetNode) {
                Log.w(LOG_TAG, "SolarLabShellLayoutTest.scrollMissingNode tag=$tag")
            } else {
                try {
                    shellNode.performScrollToNode(targetTagMatcher)
                } catch (_: AssertionError) {
                    Log.w(LOG_TAG, "SolarLabShellLayoutTest.scrollToNodeFailed tag=$tag")
                }

                runCatching {
                    composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                }.onSuccess {
                    return
                }.onFailure { failure ->
                    if (failure is AssertionError) {
                        lastVisibleFailure = failure
                    }
                }
            }

            val shellVisible = runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN, useUnmergedTree = true)
                    .performTouchInput { swipeUp() }
            }
            if (shellVisible.isFailure) {
                Log.w(
                    LOG_TAG,
                    "SolarLabShellLayoutTest.manualScrollFallback tag=$tag",
                    shellVisible.exceptionOrNull(),
                )
            }
            composeRule.waitForIdle()
            Thread.sleep(200)
            if ((deadlineMs - System.currentTimeMillis()) < 150) {
                break
            }
        }

        runCatching {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        }.onSuccess {
            if (it == 0) {
                throw AssertionError(
                    "SolarLabShellLayoutTest.scrollShellTo could not find node with tag=$tag in shell hierarchy"
                )
            }
        }
        composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN, useUnmergedTree = true).performScrollToNode(
            targetTagMatcher,
        )
        composeRule.waitForIdle()

        val nodes = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
        var displayable = false
        for (interaction in nodes) {
            if (runCatching { interaction.assertIsDisplayed() }.isSuccess) {
                displayable = true
                break
            }
        }
        if (displayable) {
            return
        }
        val fallbackNode = nodes.fetchSemanticsNodes().firstOrNull()
        val failureCause = lastVisibleFailure ?: AssertionError(
            "SolarLabShellLayoutTest.scrollShellTo unable to make tag=$tag visible after " +
                "$scrollAttempts attempts",
            fallbackNode?.layoutInfo?.run { Exception(this.toString()) },
        )
        Log.w(LOG_TAG, "scrollShellTo final failure for tag=$tag", failureCause)
        throw failureCause
    }

    private fun waitForShellHierarchy(timeoutMillis: Long = 5_000L) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                composeRule
                    .onAllNodesWithTag(SolarLabTestTags.SHELL_COLUMN, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val LOG_TAG = "SolarLabInstrumentation"
    }
}
