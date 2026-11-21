package com.sednalabs.solarlab

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RuntimeUiStateInstrumentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun workerPublicationsKeepSnapshotApplicationAndLayoutOnMain() {
        val source = MutableStateFlow(0)
        val applied = AtomicInteger(-1)
        val wrongThread = AtomicReference<String?>(null)
        fun observeThread() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                wrongThread.compareAndSet(null, Thread.currentThread().name)
            }
        }
        compose.setContent {
            val value by source.collectAsMainThreadState()
            Text("Worker publication $value", Modifier.layout { measurable, constraints ->
                observeThread()
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            })
            SideEffect { observeThread(); applied.set(value) }
        }
        compose.waitForIdle()
        repeat(24) { index ->
            val value = index + 1
            runBlocking(Dispatchers.Default) {
                assertNotEquals(Looper.getMainLooper().thread.id, Thread.currentThread().id)
                source.value = value
            }
            compose.waitUntil(timeoutMillis = 5_000) { applied.get() == value }
        }
        compose.onNodeWithText("Worker publication 24").assertIsDisplayed()
        assertNull("UI state was applied or measured on ${wrongThread.get()}", wrongThread.get())
    }
}
