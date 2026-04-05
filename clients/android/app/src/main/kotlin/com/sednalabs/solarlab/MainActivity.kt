package com.sednalabs.solarlab

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
 * Android entrypoint for the v2 shell.
 *
 * Composition and runtime orchestration start here: the activity only creates and injects
 * the runtime facade, then hands full control to composable UI.
 */
class MainActivity : ComponentActivity() {
    private val runtimeViewModel: RuntimeSessionViewModel by viewModels()

    @VisibleForTesting
    internal val runtimeFacadeForTesting: RuntimeFacade
        get() = runtimeViewModel.runtimeFacade

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SolarLabApp(runtimeFacade = runtimeViewModel.runtimeFacade)
        }
    }
}

internal class RuntimeSessionViewModel : ViewModel() {
    val runtimeFacade: RuntimeFacade = BridgeBackedRuntimeFacade()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        runtimeScope.launch {
            runtimeFacade.startSession()
        }
    }

    override fun onCleared() {
        runtimeScope.cancel()
    }
}
