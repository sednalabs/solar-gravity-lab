package com.sednalabs.solarlab

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo

internal fun SemanticsNodeInteraction.performScrollToIfPossible(): SemanticsNodeInteraction =
    try {
        performScrollTo()
    } catch (error: AssertionError) {
        if (error.message?.contains("Scroll SemanticsAction") == true) {
            this
        } else {
            throw error
        }
    }
