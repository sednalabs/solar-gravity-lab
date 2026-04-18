package com.sednalabs.solarlab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
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
 * The app can boot either the Rust-authoritative shell or the restored stage-first sandbox,
 * depending on the build variant / build flag.
 */
class MainActivity : ComponentActivity() {
    private val runtimeViewModel: RuntimeSessionViewModel by viewModels()

    @VisibleForTesting
    internal val runtimeFacadeForTesting: RuntimeFacade
        get() = runtimeViewModel.runtimeFacade

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!BuildConfig.STAGE_FIRST_CLIENT) {
            runtimeViewModel.ensureStarted()
        }

        setContent {
            if (BuildConfig.STAGE_FIRST_CLIENT) {
                StageFirstSandboxApp(
                    runtimeFacade = if (BuildConfig.STAGE_FIRST_RUNTIME_MIRROR) {
                        runtimeViewModel.runtimeFacade
                    } else {
                        null
                    },
                    ensureRuntimeStarted = if (BuildConfig.STAGE_FIRST_RUNTIME_MIRROR) {
                        runtimeViewModel::ensureStarted
                    } else {
                        null
                    },
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
