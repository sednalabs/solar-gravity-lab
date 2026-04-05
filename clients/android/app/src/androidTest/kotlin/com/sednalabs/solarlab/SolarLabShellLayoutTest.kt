package com.sednalabs.solarlab

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.RenderStatusPresentation
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.ShellUiState
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
            renderStatus = RenderStatusPresentation(
                readiness = RenderHostReadiness.Ready,
                summary = "Render packet summary visible",
            ),
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
        composeRule.onNodeWithTag(SolarLabTestTags.SHELL_COLUMN).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STATUS_LINE).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.RENDER_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.DETAIL_LINE).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.SESSION_HANDLE).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.RENDER_PACKET_SUMMARY).assertIsDisplayed()

        // Touch input is accepted by the render panel layout node.
        composeRule.onNodeWithTag(SolarLabTestTags.RENDER_PANEL)
            .performTouchInput { click() }
            .assertIsDisplayed()
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

        override val uiState: StateFlow<ShellUiState> = state

        override suspend fun startSession() = Unit

        override suspend fun refresh() = Unit

        override suspend fun applyCommand(command: RuntimeCommand) = Unit
    }
}
