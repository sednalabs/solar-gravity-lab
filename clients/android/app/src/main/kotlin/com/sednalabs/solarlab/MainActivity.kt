package com.sednalabs.solarlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sednalabs.solarlab.runtime.BridgeBackedRuntimeFacade

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val runtimeFacade = BridgeBackedRuntimeFacade()

        setContent {
            SolarLabApp(runtimeFacade = runtimeFacade)
        }
    }
}
