package com.sednalabs.solarlab.render.vulkan

/** UI-thread state: coalesce invalidations while allowing only one native frame in flight. */
internal class PresentationFrameGate {
    data class Request(val refreshRuntimeScene: Boolean)

    private var requested = false
    private var refreshRequested = false
    private var inFlight = false
    private var presentationPending = false

    fun request(refreshRuntimeScene: Boolean = true) {
        requested = true
        refreshRequested = refreshRequested || refreshRuntimeScene
    }

    fun canSchedule(active: Boolean): Boolean =
        active && !inFlight && (requested || presentationPending)

    fun begin(active: Boolean): Request? {
        if (!canSchedule(active)) return null
        inFlight = true
        val next = Request(refreshRequested)
        requested = false
        refreshRequested = false
        presentationPending = false
        return next
    }

    fun complete(hasPendingPresentation: Boolean) {
        inFlight = false
        presentationPending = hasPendingPresentation
    }

    fun stopPresentation() {
        presentationPending = false
    }
}
