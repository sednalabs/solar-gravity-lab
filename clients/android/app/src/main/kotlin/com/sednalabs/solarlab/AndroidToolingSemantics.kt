@file:OptIn(ExperimentalComposeUiApi::class)

package com.sednalabs.solarlab

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Expose stable Compose test tags through the accessibility/UIAutomator surface so external
 * Android harnesses can target the same controls our instrumentation tests already rely on.
 */
internal fun Modifier.androidToolingSemantics(): Modifier = semantics {
    testTagsAsResourceId = true
}
