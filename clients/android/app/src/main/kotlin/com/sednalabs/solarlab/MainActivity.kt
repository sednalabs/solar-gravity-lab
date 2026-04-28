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
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
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
    private companion object {
        const val STATE_STAGE_FIRST_EXPERIENCE_MODE = "stage_first_experience_mode"
    }

    private val runtimeViewModel: RuntimeSessionViewModel by viewModels()
    private val stageFirstExperienceModeState = mutableStateOf(StageFirstExperienceMode.LOCAL_SANDBOX)
    private val stageFirstRuntimeMirrorMountedState = mutableStateOf(false)
    private val stageFirstRuntimeMirrorRenderHostState = mutableStateOf<SolarSystemRenderHostView?>(null)

    @VisibleForTesting
    internal val runtimeFacadeForTesting: RuntimeFacade
        get() = runtimeViewModel.runtimeFacade

    @VisibleForTesting
    internal fun showStageFirstRuntimeMirrorForTesting() {
        if (BuildConfig.STAGE_FIRST_CLIENT && BuildConfig.STAGE_FIRST_RUNTIME_MIRROR) {
            stageFirstExperienceModeState.value = StageFirstExperienceMode.RUNTIME_MIRROR
        }
    }

    @VisibleForTesting
    internal fun isStageFirstRuntimeMirrorMountedForTesting(): Boolean =
        stageFirstRuntimeMirrorMountedState.value

    @VisibleForTesting
    internal fun stageFirstRuntimeMirrorRenderHostForTesting(): SolarSystemRenderHostView? =
        stageFirstRuntimeMirrorRenderHostState.value

    override fun onCreate(savedInstanceState: Bundle?) {
        stageFirstExperienceModeState.value = savedInstanceState
            ?.getString(STATE_STAGE_FIRST_EXPERIENCE_MODE)
            ?.let(StageFirstExperienceMode::valueOf)
            ?: StageFirstExperienceMode.LOCAL_SANDBOX

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
                    experienceModeState = stageFirstExperienceModeState,
                    runtimeMirrorMountedState = stageFirstRuntimeMirrorMountedState,
                    runtimeMirrorRenderHostState = stageFirstRuntimeMirrorRenderHostState,
                )
            } else {
                SolarLabApp(runtimeFacade = runtimeViewModel.runtimeFacade)
            }
        }

        handleSemanticIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_STAGE_FIRST_EXPERIENCE_MODE, stageFirstExperienceModeState.value.name)
        super.onSaveInstanceState(outState)
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
