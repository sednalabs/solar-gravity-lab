package com.sednalabs.solarlab

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstRuntimeLifecycleInstrumentationTest {
    @Test
    fun repeatedColdLaunchBackgroundAndRecreationKeepTheMainThreadResponsive() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)

        repeat(COLD_LAUNCH_COUNT) { launchIndex ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForResponsiveStage(scenario, "cold launch ${launchIndex + 1}")

                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitForResponsiveStage(scenario, "foreground ${launchIndex + 1}")

                scenario.recreate()
                waitForResponsiveStage(scenario, "recreation ${launchIndex + 1}")
            }
        }
    }

    private fun waitForResponsiveStage(
        scenario: ActivityScenario<MainActivity>,
        phase: String,
    ) {
        val deadlineMs = SystemClock.uptimeMillis() + STAGE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadlineMs) {
            var ready = false
            scenario.onActivity { activity ->
                val host = findRenderHostView(activity.window.decorView)
                ready = activity.isStageFirstRuntimeMountedForTesting() &&
                    host?.isAttachedToWindow == true &&
                    host.width > 0 &&
                    host.height > 0
            }
            if (ready) {
                assertMainThreadHeartbeat(phase)
                return
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for a responsive stage after $phase")
    }

    private fun assertMainThreadHeartbeat(phase: String) {
        var heartbeatObserved = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            heartbeatObserved = true
        }
        assertTrue("Android main thread did not answer after $phase", heartbeatObserved)
    }

    private fun findRenderHostView(root: View): SolarSystemRenderHostView? {
        if (root is SolarSystemRenderHostView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findRenderHostView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val COLD_LAUNCH_COUNT = 3
        const val STAGE_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
