package com.sednalabs.solarlab.render.vulkan

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process

/** Uses the public Java API on API 31+, independent of CPU/GPU vendor or instruction set. */
internal class AndroidRenderHintFactory(context: Context) : RenderPerformanceHints.Factory {
    private val applicationContext = context.applicationContext

    override fun create(targetDurationNanos: Long): RenderPerformanceHints.Creation {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RenderPerformanceHints.Creation(null, "unavailable_api")
        }
        val manager = applicationContext.getSystemService(PerformanceHintManager::class.java)
            ?: return RenderPerformanceHints.Creation(null, "unavailable_service")
        // Called only from the stable Vulkan executor. Never substitute the UI thread ID.
        val nativeSession = manager.createHintSession(intArrayOf(Process.myTid()), targetDurationNanos)
            ?: return RenderPerformanceHints.Creation(null, "unsupported")
        return RenderPerformanceHints.Creation(object : RenderPerformanceHints.Session {
            override fun updateTarget(durationNanos: Long) = nativeSession.updateTargetWorkDuration(durationNanos)
            override fun reportActual(durationNanos: Long) = nativeSession.reportActualWorkDuration(durationNanos)
            override fun close() = nativeSession.close()
        })
    }
}
