package com.sednalabs.solarlab.render.vulkan

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedVulkanRendererTest {
    @Test
    fun lifecycleAndRendererActionsNeverRunOnTheSubmittingThread() {
        val executor = ManualExecutor()
        val backend = RecordingBackend()
        val renderer = renderer(backend, executor)
        var actionCount = 0

        renderer.surfaceCreated(
            generation = 1L,
            nativeWindowHandle = 101L,
            width = 1080,
            height = 2400,
            onResult = {},
        )
        renderer.execute(requireSurface = true) { actionCount += 1 }

        assertTrue(backend.events.isEmpty())
        assertEquals(0, actionCount)

        executor.runAll()

        assertEquals(
            listOf("createRenderer", "createSurface:41:101:1080x2400"),
            backend.events.take(2),
        )
        assertEquals(1, actionCount)
    }

    @Test
    fun generationsSerializeDestroyBeforeReplacementCreation() {
        val executor = ManualExecutor()
        val backend = RecordingBackend()
        val renderer = renderer(backend, executor)

        renderer.surfaceCreated(1L, 101L, 100, 200) {}
        renderer.surfaceChanged(1L, 102L, 200, 100) {}
        renderer.surfaceDestroyed(1L)
        renderer.surfaceCreated(2L, 201L, 300, 400) {}
        renderer.release()

        executor.runAll()

        assertEquals(
            listOf(
                "createRenderer",
                "createSurface:41:101:100x200",
                "changeSurface:41:102:200x100",
                "destroySurface:41",
                "createSurface:41:201:300x400",
                "destroySurface:41",
                "destroyRenderer:41",
            ),
            backend.events,
        )
    }

    @Test
    fun staleSurfaceChangeReleasesItsOwnedWindowWithoutTouchingRenderer() {
        val executor = ManualExecutor()
        val backend = RecordingBackend()
        val renderer = renderer(backend, executor)

        renderer.surfaceCreated(1L, 101L, 100, 200) {}
        renderer.surfaceDestroyed(1L)
        renderer.surfaceChanged(1L, 102L, 200, 100) {}

        executor.runAll()

        assertEquals(
            listOf(
                "createRenderer",
                "createSurface:41:101:100x200",
                "destroySurface:41",
                "releaseWindow:102",
            ),
            backend.events,
        )
    }

    @Test
    fun destroyedGenerationCannotBeRevivedByALateCreateCallback() {
        val executor = ManualExecutor()
        val backend = RecordingBackend()
        val renderer = renderer(backend, executor)

        renderer.surfaceCreated(1L, 101L, 100, 200) {}
        renderer.surfaceDestroyed(1L)
        renderer.surfaceCreated(1L, 102L, 100, 200) {}

        executor.runAll()

        assertEquals(
            listOf(
                "createRenderer",
                "createSurface:41:101:100x200",
                "destroySurface:41",
                "releaseWindow:102",
            ),
            backend.events,
        )
    }

    @Test
    fun releaseIsTerminalAndDoesNotLeakAWindowSubmittedAfterIt() {
        val executor = ManualExecutor()
        val backend = RecordingBackend()
        val renderer = renderer(backend, executor)

        renderer.release()
        renderer.surfaceCreated(1L, 101L, 100, 200) {}
        renderer.execute { backend.events += "lateAction" }
        executor.runAll()

        assertEquals(listOf("releaseWindow:101"), backend.events)
    }

    private fun renderer(
        backend: RecordingBackend,
        executor: ManualExecutor,
    ) = SerializedVulkanRenderer(
        backend = backend,
        executor = executor,
        dispatchResult = { action -> action() },
    )

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    private class RecordingBackend : SerializedVulkanRenderer.LifecycleBackend {
        val events = mutableListOf<String>()

        override fun createRenderer(): Long {
            events += "createRenderer"
            return 41L
        }

        override fun destroyRenderer(handle: Long) {
            events += "destroyRenderer:$handle"
        }

        override fun releaseNativeWindow(nativeWindowHandle: Long) {
            events += "releaseWindow:$nativeWindowHandle"
        }

        override fun onSurfaceCreated(
            handle: Long,
            nativeWindowHandle: Long,
            width: Int,
            height: Int,
        ): Boolean {
            events += "createSurface:$handle:$nativeWindowHandle:${width}x$height"
            return true
        }

        override fun onSurfaceChanged(
            handle: Long,
            nativeWindowHandle: Long,
            width: Int,
            height: Int,
        ): Boolean {
            events += "changeSurface:$handle:$nativeWindowHandle:${width}x$height"
            return true
        }

        override fun onSurfaceDestroyed(handle: Long) {
            events += "destroySurface:$handle"
        }

        override fun lastError(handle: Long): String = "error:$handle"
    }
}
