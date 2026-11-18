package com.sednalabs.solarlab.runtime

/** Serial native-boundary owner; reports completed solver calls, excluding queue waits. */
internal class RuntimePerformanceHints(
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
        fun create(targetDurationNanos: Long): Session?
    }

    private var session: Session? = null
    private var ownerThreadId: Long? = null
    private var attempted = false
    private var targetNanos = 0L
    private var workNanos = 0L
    private var reports = 0L
    private var state = if (requested) "implemented" else "disabled"

    fun <T> measure(targetDurationNanos: Long, successful: (T) -> Boolean, work: () -> T): T {
        val started = begin(targetDurationNanos)
        var completed = false
        try {
            return work().also { completed = successful(it) }
        } finally {
            val elapsed = nanoTime() - started
            if (!completed) {
                close()
            } else if (elapsed > 0L) {
                workNanos = elapsed
                try {
                    session?.let { it.reportActual(elapsed); reports += 1 }
                } catch (_: RuntimeException) {
                    fail()
                }
            }
        }
    }

    private fun begin(targetDurationNanos: Long): Long {
        val tid = threadId()
        // limitedParallelism(1) serializes coroutines but does not guarantee a
        // stable Linux TID. Recreate even on API 31-33, without naming UI threads.
        if (ownerThreadId != null && ownerThreadId != tid) close()
        val target = targetDurationNanos.coerceAtLeast(1L)
        if (requested && !attempted) {
            attempted = true
            ownerThreadId = tid
            targetNanos = target
            try {
                session = factory.create(target)
                setState(if (session == null) "unavailable" else "active")
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
        // Creation and target-update costs are not reported as physics work.
        return nanoTime()
    }

    fun close() {
        closeSession()
        attempted = false
        ownerThreadId = null
        setState(if (requested) "inactive" else "disabled")
    }

    fun summary(): String = "physicsADPF[requested=$requested,state=$state," +
        "reported=$reports,targetNs=$targetNanos,workNs=$workNanos]"

    private fun fail() {
        closeSession()
        setState("unavailable_error")
    }

    private fun closeSession() {
        val old = session
        session = null
        try { old?.close() } catch (_: RuntimeException) { /* Hints cannot break physics. */ }
    }

    private fun setState(next: String) {
        if (state == next) return
        state = next
        onStateChanged(summary())
    }

    companion object {
        fun disabled() = RuntimePerformanceHints(Factory { null }, requested = false)
    }
}
