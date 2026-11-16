package com.sednalabs.solarlab.render.vulkan

/** Owned by the serial render executor, including creation, reporting and closure. */
internal class RenderPerformanceHints(
    private val factory: Factory,
    private val nanoTime: () -> Long = System::nanoTime,
    private val threadId: () -> Long = { Thread.currentThread().id },
    private val onStateChanged: (String) -> Unit = {},
    private val requested: Boolean = true,
) {
    interface Session {
        fun updateTarget(durationNanos: Long)
        fun reportActual(durationNanos: Long)
        fun close()
    }

    fun interface Factory {
        fun create(targetDurationNanos: Long): Creation
    }

    data class Creation(val session: Session?, val unavailableReason: String = "unsupported")

    private var session: Session? = null
    private var attempted = false
    private var state = if (requested) "implemented" else "disabled"
    private var targetNanos = 0L
    private var lastWorkNanos = 0L
    private var reports = 0L
    private var ownerThreadId: Long? = null

    fun begin(targetDurationNanos: Long): Long {
        val target = targetDurationNanos.coerceAtLeast(1L)
        val currentThreadId = threadId()
        if (ownerThreadId != null && ownerThreadId != currentThreadId) close()
        if (requested && !attempted) {
            attempted = true
            ownerThreadId = currentThreadId
            targetNanos = target
            try {
                val created = factory.create(target)
                session = created.session
                setState(if (session != null) "active" else created.unavailableReason)
            } catch (_: RuntimeException) {
                fail()
            }
        } else if (session != null && target != targetNanos) {
            try {
                session?.updateTarget(target)
                targetNanos = target
            } catch (_: RuntimeException) {
                fail()
            }
        }
        // Session setup is not render work. Nor is time spent waiting in the executor queue.
        return nanoTime()
    }

    fun end(startedAtNanos: Long, completed: Boolean) {
        val duration = nanoTime() - startedAtNanos
        if (!completed) {
            close()
            return
        }
        if (duration <= 0L) return
        lastWorkNanos = duration
        try {
            session?.let {
                it.reportActual(duration)
                reports += 1
            }
        } catch (_: RuntimeException) {
            fail()
        }
    }

    fun close() {
        closeSession()
        attempted = false
        ownerThreadId = null
        setState(if (requested) "inactive" else "disabled")
    }

    fun summary(): String = "ADPF[requested=$requested,implemented=true,state=$state," +
        "reported=$reports,targetNs=$targetNanos,workNs=$lastWorkNanos]"

    private fun fail() {
        closeSession()
        // Retry only after an explicit lifecycle/idle close, never on every display callback.
        setState("unavailable_error")
    }

    private fun closeSession() {
        val previous = session
        session = null
        try {
            previous?.close()
        } catch (_: RuntimeException) {
            // Hint support cannot prevent normal rendering or native teardown.
        }
    }

    private fun setState(next: String) {
        if (state == next) return
        state = next
        onStateChanged(summary())
    }
}

internal fun displayFrameTargetNanos(refreshRateHz: Float): Long {
    val rate = refreshRateHz.takeIf { it.isFinite() && it > 0f } ?: 60f
    return (1_000_000_000.0 / rate).toLong().coerceAtLeast(1L)
}
