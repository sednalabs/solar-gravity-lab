package com.sednalabs.solarlab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** Keep Compose snapshot writes on Android's main thread even when a worker publishes through an unconfined test frame clock. */
@Composable
internal fun <T> StateFlow<T>.collectAsMainThreadState(): State<T> =
    collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
