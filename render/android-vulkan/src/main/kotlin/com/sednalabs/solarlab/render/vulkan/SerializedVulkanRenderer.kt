package com.sednalabs.solarlab.render.vulkan

import android.os.Process
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The native renderer and every handle derived from it belong to one serial execution context.
 * Android lifecycle callbacks may submit work here, but must never wait for Vulkan from the UI
 * thread. A process-wide executor also orders destruction of an old Activity's renderer before
 * work already queued for its replacement reaches the driver.
 */
internal class SerializedVulkanRenderer(
    private val backend: LifecycleBackend,
    private val executor: Executor = VulkanRenderExecutor.executor,
    private val dispatchResult: (() -> Unit) -> Unit,
) {
    internal interface LifecycleBackend {
        fun createRenderer(): Long
        fun destroyRenderer(handle: Long)
        fun releaseNativeWindow(nativeWindowHandle: Long)
        fun onSurfaceCreated(
            handle: Long,
            nativeWindowHandle: Long,
            width: Int,
            height: Int,
        ): Boolean

        fun onSurfaceChanged(
            handle: Long,
            nativeWindowHandle: Long,
            width: Int,
            height: Int,
        ): Boolean

        fun onSurfaceDestroyed(handle: Long)
        fun lastError(handle: Long): String
    }

    internal data class SurfaceResult(
        val generation: Long,
        val isReady: Boolean,
        val error: String? = null,
    )

    private val submissionLock = Any()
    private var acceptingCommands = true

    // Worker-owned state. It is read and written only by tasks on [executor].
    private var rendererHandle = 0L
    private var latestSurfaceGeneration = NO_SURFACE_GENERATION
    private var activeSurfaceGeneration = NO_SURFACE_GENERATION
    private var surfaceReady = false

    fun surfaceCreated(
        generation: Long,
        nativeWindowHandle: Long,
        width: Int,
        height: Int,
        onResult: (SurfaceResult) -> Unit,
    ) {
        submitOwnedWindow(nativeWindowHandle) {
            if (generation <= latestSurfaceGeneration) {
                backend.releaseNativeWindow(nativeWindowHandle)
                return@submitOwnedWindow
            }
            if (surfaceReady && rendererHandle != 0L) {
                backend.onSurfaceDestroyed(rendererHandle)
                surfaceReady = false
            }
            latestSurfaceGeneration = generation
            activeSurfaceGeneration = generation
            if (rendererHandle == 0L) {
                rendererHandle = backend.createRenderer()
            }
            if (rendererHandle == 0L) {
                backend.releaseNativeWindow(nativeWindowHandle)
                dispatchResult {
                    onResult(
                        SurfaceResult(
                            generation = generation,
                            isReady = false,
                            error = "Failed to create native Vulkan renderer.",
                        ),
                    )
                }
                return@submitOwnedWindow
            }

            // Ownership of nativeWindowHandle transfers to the backend at this call boundary.
            surfaceReady = backend.onSurfaceCreated(
                handle = rendererHandle,
                nativeWindowHandle = nativeWindowHandle,
                width = width,
                height = height,
            )
            val result = SurfaceResult(
                generation = generation,
                isReady = surfaceReady,
                error = if (surfaceReady) null else backend.lastError(rendererHandle),
            )
            dispatchResult { onResult(result) }
        }
    }

    fun surfaceChanged(
        generation: Long,
        nativeWindowHandle: Long,
        width: Int,
        height: Int,
        onResult: (SurfaceResult) -> Unit,
    ) {
        submitOwnedWindow(nativeWindowHandle) {
            if (
                generation != activeSurfaceGeneration ||
                rendererHandle == 0L ||
                !surfaceReady
            ) {
                backend.releaseNativeWindow(nativeWindowHandle)
                return@submitOwnedWindow
            }

            // Ownership of nativeWindowHandle transfers to the backend at this call boundary.
            surfaceReady = backend.onSurfaceChanged(
                handle = rendererHandle,
                nativeWindowHandle = nativeWindowHandle,
                width = width,
                height = height,
            )
            val result = SurfaceResult(
                generation = generation,
                isReady = surfaceReady,
                error = if (surfaceReady) null else backend.lastError(rendererHandle),
            )
            dispatchResult { onResult(result) }
        }
    }

    fun surfaceDestroyed(generation: Long) {
        submit {
            if (generation != activeSurfaceGeneration || rendererHandle == 0L) return@submit
            if (surfaceReady) {
                backend.onSurfaceDestroyed(rendererHandle)
            }
            surfaceReady = false
            activeSurfaceGeneration = NO_SURFACE_GENERATION
        }
    }

    fun execute(
        requireSurface: Boolean = false,
        action: (Long) -> Unit,
    ) {
        submit {
            val handle = rendererHandle
            if (handle == 0L || (requireSurface && !surfaceReady)) return@submit
            action(handle)
        }
    }

    fun <T : Any> query(
        requireSurface: Boolean = false,
        action: (Long) -> T?,
        onResult: (T?) -> Unit,
    ) {
        submit {
            val handle = rendererHandle
            val result = if (handle == 0L || (requireSurface && !surfaceReady)) {
                null
            } else {
                action(handle)
            }
            dispatchResult { onResult(result) }
        }
    }

    /** Returns immediately; native teardown remains ordered after all previously accepted work. */
    fun release(beforeDestroy: (Long) -> Unit = {}) {
        synchronized(submissionLock) {
            if (!acceptingCommands) return
            acceptingCommands = false
            executor.execute {
                val handle = rendererHandle
                if (handle != 0L) {
                    beforeDestroy(handle)
                    if (surfaceReady) {
                        backend.onSurfaceDestroyed(handle)
                    }
                    backend.destroyRenderer(handle)
                }
                rendererHandle = 0L
                surfaceReady = false
                latestSurfaceGeneration = NO_SURFACE_GENERATION
                activeSurfaceGeneration = NO_SURFACE_GENERATION
            }
        }
    }

    private fun submitOwnedWindow(
        nativeWindowHandle: Long,
        action: () -> Unit,
    ) {
        synchronized(submissionLock) {
            if (!acceptingCommands) {
                backend.releaseNativeWindow(nativeWindowHandle)
                return
            }
            try {
                executor.execute { action() }
            } catch (error: Throwable) {
                backend.releaseNativeWindow(nativeWindowHandle)
                throw error
            }
        }
    }

    private fun submit(action: () -> Unit) {
        synchronized(submissionLock) {
            if (!acceptingCommands) return
            executor.execute { action() }
        }
    }

    private companion object {
        const val NO_SURFACE_GENERATION = 0L
    }
}

private object VulkanRenderExecutor {
    val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                task.run()
            },
            "solarlab-vulkan-render",
        ).apply {
            isDaemon = true
        }
    }
}
