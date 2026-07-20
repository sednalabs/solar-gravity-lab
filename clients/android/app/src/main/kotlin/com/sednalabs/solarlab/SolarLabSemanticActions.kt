package com.sednalabs.solarlab

import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal sealed interface SolarLabSemanticAction {
    data class FocusBody(
        val bodyQuery: String,
        val requestId: String? = null,
    ) : SolarLabSemanticAction
    data class LoadScenario(val scenarioId: String) : SolarLabSemanticAction
    data object ResetCamera : SolarLabSemanticAction
}

internal object SolarLabSemanticActionBridge {
    const val INTENT_ACTION = "com.sednalabs.solarlab.action.SEMANTIC_CONTROL"
    const val EXTRA_COMMAND = "com.sednalabs.solarlab.extra.SEMANTIC_COMMAND"
    const val EXTRA_BODY_QUERY = "com.sednalabs.solarlab.extra.BODY_QUERY"
    const val EXTRA_REQUEST_ID = "com.sednalabs.solarlab.extra.SEMANTIC_REQUEST_ID"
    const val EXTRA_SCENARIO_ID = "com.sednalabs.solarlab.extra.SCENARIO_ID"

    private val commandsFlow = MutableSharedFlow<SolarLabSemanticAction>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val commands: SharedFlow<SolarLabSemanticAction> = commandsFlow.asSharedFlow()

    fun submit(action: SolarLabSemanticAction): Boolean = commandsFlow.tryEmit(action)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearPendingReplay() {
        commandsFlow.resetReplayCache()
    }

    fun parseIntent(intent: Intent?): SolarLabSemanticAction? {
        return parseSemanticCommand(
            action = intent?.action,
            command = intent?.getStringExtra(EXTRA_COMMAND),
            bodyQuery = intent?.getStringExtra(EXTRA_BODY_QUERY),
            requestId = intent?.getStringExtra(EXTRA_REQUEST_ID),
            scenarioId = intent?.getStringExtra(EXTRA_SCENARIO_ID),
        )
    }

    internal fun parseSemanticCommand(
        action: String?,
        command: String?,
        bodyQuery: String?,
        requestId: String? = null,
        scenarioId: String? = null,
    ): SolarLabSemanticAction? {
        if (!semanticActionsEnabled() || action != INTENT_ACTION) {
            return null
        }
        return when (command?.trim()?.lowercase(java.util.Locale.US).orEmpty()) {
            "focus_body" -> bodyQuery
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { normalizedBodyQuery ->
                    SolarLabSemanticAction.FocusBody(
                        bodyQuery = normalizedBodyQuery,
                        requestId = requestId?.trim()?.takeIf(String::isNotEmpty),
                    )
                }
            "load_scenario" -> scenarioId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(SolarLabSemanticAction::LoadScenario)
            "reset_camera" -> SolarLabSemanticAction.ResetCamera
            else -> null
        }
    }

    internal fun semanticActionsEnabled(): Boolean = BuildConfig.DEBUG
}
