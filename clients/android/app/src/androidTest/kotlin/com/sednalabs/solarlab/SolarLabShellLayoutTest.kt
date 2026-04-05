package com.sednalabs.solarlab

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.RenderStatusPresentation
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.SnapshotPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        )
    )

    @Before
    fun setUpContent() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.setContent {
                TestApp(runtimeFacade = runtimeFacade)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun primaryShellElements_areRenderedVisible_andTouchAccessible() {
        assertVisibleInScrollableShell(SolarLabTestTags.SHELL_COLUMN)
        assertVisibleInScrollableShell(SolarLabTestTags.TITLE)
        assertVisibleInScrollableShell(SolarLabTestTags.STATUS_LINE)
        assertVisibleInScrollableShell(SolarLabTestTags.RENDER_PANEL)
        assertVisibleInScrollableShell(SolarLabTestTags.DETAIL_LINE)
        assertVisibleInScrollableShell(SolarLabTestTags.SESSION_HANDLE)
        assertVisibleInScrollableShell(SolarLabTestTags.RENDER_PACKET_SUMMARY)
        assertVisibleInScrollableShell(SolarLabTestTags.FOCUS_BODY_FIELD)
        assertVisibleInScrollableShell(SolarLabTestTags.FOCUS_BODY_SET_BUTTON)
        assertVisibleInScrollableShell(SolarLabTestTags.FOCUS_SELECTION_BUTTON)
        assertVisibleInScrollableShell(SolarLabTestTags.CHECKPOINT_ID_FIELD)
        assertVisibleInScrollableShell(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON)
        assertVisibleInScrollableShell(SolarLabTestTags.BRANCH_FROM_CHECKPOINT_FIELD)
        assertVisibleInScrollableShell(SolarLabTestTags.BRANCH_NAME_FIELD)
        assertVisibleInScrollableShell(SolarLabTestTags.CREATE_BRANCH_FROM_CHECKPOINT_BUTTON)
        assertVisibleInScrollableShell(SolarLabTestTags.METADATA_FOCUS_TARGET)
        assertVisibleInScrollableShell(SolarLabTestTags.METADATA_OBSERVER_MODE)
        assertVisibleInScrollableShell(SolarLabTestTags.METADATA_ACTIVE_BRANCH)
        assertVisibleInScrollableShell(SolarLabTestTags.METADATA_ACTIVE_CHECKPOINT)
        assertVisibleInScrollableShell(SolarLabTestTags.METADATA_PROVENANCE)
        assertVisibleInScrollableShell(SolarLabTestTags.METADATA_LIGHTS)

        // Touch input is accepted by the render panel layout node.
        composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN)
            .performScrollToNode(hasTestTag(SolarLabTestTags.RENDER_PANEL))
        composeRule.onNodeWithTag(SolarLabTestTags.RENDER_PANEL)
            .performTouchInput { click() }
            .assertIsDisplayed()
    }

    @Test
    fun shellControls_emitCommands_whenUserInteracts() {
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_FIELD).performTextInput("body-7")
        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_BODY_SET_BUTTON).performTouchInput { click() }
        assertTrue(runtimeFacade.commands.any { it is RuntimeCommand.FocusBody && it.bodyId == "body-7" })

        composeRule.onNodeWithTag(SolarLabTestTags.FOCUS_SELECTION_BUTTON).performTouchInput { click() }
        assertTrue(
            runtimeFacade.commands.any {
                it is RuntimeCommand.SetObserverMode && it.mode == RuntimeObserverMode.FollowSelected
            },
        )

        composeRule.onNodeWithTag(SolarLabTestTags.CHECKPOINT_ID_FIELD).performTextInput("checkpoint-1")
        composeRule.onNodeWithTag(SolarLabTestTags.CREATE_CHECKPOINT_BUTTON).performTouchInput { click() }
        assertTrue(
            runtimeFacade.commands.any {
                it is RuntimeCommand.CreateCheckpoint && it.checkpointId == "checkpoint-1"
            },
        )

        composeRule.onNodeWithTag(SolarLabTestTags.BRANCH_FROM_CHECKPOINT_FIELD).performTextInput("checkpoint-1")
        composeRule.onNodeWithTag(SolarLabTestTags.BRANCH_NAME_FIELD).performTextInput("branch-a")
        composeRule.onNodeWithTag(SolarLabTestTags.CREATE_BRANCH_FROM_CHECKPOINT_BUTTON).performTouchInput {
            click()
        }
        assertTrue(
            runtimeFacade.commands.any {
                it is RuntimeCommand.CreateBranchFromCheckpoint &&
                    it.checkpointId == "checkpoint-1" &&
                    it.newBranchId == "branch-a"
            },
        )

        assertTrue(runtimeFacade.commands.size > 4)
    }

    @Test
    fun shellContent_respectsSystemBarsSafeDrawingPadding() {
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
            .onNodeWithTag(SolarLabTestTags.TITLE)
            .fetchSemanticsNode()
            .boundsInRoot
        val renderPanelBounds = composeRule
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
        val commands = mutableListOf<RuntimeCommand>()

        override val uiState: StateFlow<ShellUiState> = state

        override suspend fun startSession() = Unit

        override suspend fun refresh() = Unit

        override suspend fun applyCommand(command: RuntimeCommand) {
            commands.add(command)
        }
    }

    private fun assertVisibleInScrollableShell(tag: String) {
        composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN)
            .performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }
}
