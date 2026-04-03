package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.CollisionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LabSessionPlaybackSubstepPolicyTest {

    @Test
    fun `ordinary playback intervals keep existing one-hour cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 3_600.0,
            collisionMode = CollisionMode.NONE,
        )

        assertEquals(3_600.0, effective, 0.0)
    }

    @Test
    fun `large playback intervals raise max substep adaptively`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 86_400.0,
            collisionMode = CollisionMode.NONE,
        )

        assertEquals(7_200.0, effective, 0.0)
    }

    @Test
    fun `very large playback intervals clamp at bounded upper cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
            totalSeconds = 864_000.0,
            collisionMode = CollisionMode.NONE,
        )

        assertEquals(43_200.0, effective, 0.0)
    }

    @Test
    fun `all collision-enabled playback modes keep conservative one-hour cap`() {
        listOf(
            CollisionMode.MERGE,
            CollisionMode.ELASTIC,
            CollisionMode.FRAGMENTATION,
        ).forEach { collisionMode ->
            val effective = LabSession.effectivePlaybackMaxSubstepSeconds(
                totalSeconds = 864_000.0,
                collisionMode = collisionMode,
            )

            assertEquals(3_600.0, effective, 0.0)
        }
    }
}
