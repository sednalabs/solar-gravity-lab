package com.sednalabs.solarlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sednalabs.solarlab.runtime.BridgeBackedRuntimeFacade

/**
 * Android entrypoint for the v2 shell.
 *
 * Composition and runtime orchestration start here: the activity only creates and injects
 * the runtime facade, then hands full control to composable UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android owns lifecycle; the runtime boundary is created once per Activity instance
        // and passed through dependency-injection-like ownership to the Compose shell.
        val runtimeFacade = BridgeBackedRuntimeFacade()

        setContent {
            SolarLabApp(runtimeFacade = runtimeFacade)
        }
    }
}
