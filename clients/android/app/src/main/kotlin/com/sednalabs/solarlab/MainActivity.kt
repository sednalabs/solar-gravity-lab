package com.sednalabs.solarlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sednalabs.solarlab.runtime.BridgeBackedRuntimeFacade
import com.sednalabs.solarlab.runtime.PlaceholderRuntimeBridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val runtimeFacade = BridgeBackedRuntimeFacade(bridge = PlaceholderRuntimeBridge())

        setContent {
            SolarLabApp(runtimeFacade = runtimeFacade)
        }
    }
}
