package com.sednalabs.solarlab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.sednalabs.solarlab.runtime.BridgeBackedRuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android entrypoint for the current client surface.
 *
 * Every production surface binds the same Rust-authoritative world. Android
 * owns lifecycle, controls, accessibility, and presentation only.
 */
class MainActivity : ComponentActivity() {
    private val runtimeViewModel: RuntimeSessionViewModel by viewModels()
    private val stageFirstRuntimeMountedState = mutableStateOf(false)

    @VisibleForTesting
    internal val runtimeFacadeForTesting: RuntimeFacade
        get() = runtimeViewModel.runtimeFacade

    @VisibleForTesting
    internal fun isStageFirstRuntimeMountedForTesting(): Boolean =
        stageFirstRuntimeMountedState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtimeViewModel.ensureStarted()

        setContent {
            if (BuildConfig.STAGE_FIRST_CLIENT) {
                StageFirstRuntimeApp(
                    runtimeFacade = runtimeViewModel.runtimeFacade,
                    ensureRuntimeStarted = runtimeViewModel::ensureStarted,
                    runtimeMountedState = stageFirstRuntimeMountedState,
                )
            } else {
                SolarLabApp(runtimeFacade = runtimeViewModel.runtimeFacade)
            }
        }

        handleSemanticIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSemanticIntent(intent)
    }

    private fun handleSemanticIntent(intent: Intent?) {
        SolarLabSemanticActionBridge.parseIntent(intent)?.let(SolarLabSemanticActionBridge::submit)
    }
}

internal class RuntimeSessionViewModel : ViewModel() {
    val runtimeFacade: RuntimeFacade = BridgeBackedRuntimeFacade()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionStarted: Boolean = false

    fun ensureStarted() {
        if (sessionStarted) {
            return
        }
        sessionStarted = true
        runtimeScope.launch {
            runtimeFacade.startSession()
        }
    }

    override fun onCleared() {
        runtimeScope.cancel()
    }
}
