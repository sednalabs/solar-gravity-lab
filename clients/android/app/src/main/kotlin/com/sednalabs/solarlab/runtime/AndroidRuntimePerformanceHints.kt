package com.sednalabs.solarlab.runtime

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log

/** Lets the OS choose suitable Oryon/core capacity under its current thermal policy. */
internal fun androidRuntimePerformanceHints(context: Context): RuntimePerformanceHints {
    val applicationContext = context.applicationContext
    return RuntimePerformanceHints(
        factory = RuntimePerformanceHints.Factory { target ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                null
            } else {
                val manager = applicationContext.getSystemService(PerformanceHintManager::class.java)
                val native = manager?.createHintSession(intArrayOf(Process.myTid()), target)
                native?.let { session ->
                    object : RuntimePerformanceHints.Session {
                        override fun updateTarget(durationNanos: Long) = session.updateTargetWorkDuration(durationNanos)
                        override fun reportActual(durationNanos: Long) = session.reportActualWorkDuration(durationNanos)
                        override fun close() = session.close()
                    }
                }
            }
        },
        threadId = { Process.myTid().toLong() },
        onStateChanged = { Log.i("SolarLabPhysicsHints", it) },
    )
}
