package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.model.TimelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabSessionBackControlActionTest {

    @Test
    fun `catalog snapshot keeps catalog step behavior`() {
        val catalogSnapshot = SimulationSnapshot(
            epochSeconds = 0.0,
            bodies = emptyList(),
            referenceEpochJdTdb = 2_460_000.5,
            timelineMode = TimelineMode.CATALOG,
        )

        val action = resolveBackControlAction(
            snapshot = catalogSnapshot,
            latestCatalogCheckpoint = null,
        )

        assertEquals(BackControlAction.StepCatalog, action)
    }

    @Test
    fun `sandbox snapshot restores latest catalog checkpoint`() {
        val sandboxSnapshot = SimulationSnapshot(
            epochSeconds = 120.0,
            bodies = emptyList(),
            timelineMode = TimelineMode.SANDBOX_BRANCH,
        )
        val checkpoint = SimulationSnapshot(
            epochSeconds = 60.0,
            bodies = emptyList(),
            referenceEpochJdTdb = 2_460_000.5,
            timelineMode = TimelineMode.CATALOG,
        )

        val action = resolveBackControlAction(
            snapshot = sandboxSnapshot,
            latestCatalogCheckpoint = checkpoint,
        )

        assertTrue(action is BackControlAction.RestoreCheckpoint)
        assertEquals(checkpoint, (action as BackControlAction.RestoreCheckpoint).checkpoint)
    }

    @Test
    fun `sandbox snapshot without checkpoint has no back action`() {
        val sandboxSnapshot = SimulationSnapshot(
            epochSeconds = 120.0,
            bodies = emptyList(),
            timelineMode = TimelineMode.SANDBOX_BRANCH,
        )

        val action = resolveBackControlAction(
            snapshot = sandboxSnapshot,
            latestCatalogCheckpoint = null,
        )

        assertEquals(BackControlAction.None, action)
    }
}
