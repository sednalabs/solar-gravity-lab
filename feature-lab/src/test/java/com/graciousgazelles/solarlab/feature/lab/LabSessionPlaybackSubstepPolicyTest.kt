package com.graciousgazelles.solarlab.feature.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class LabSessionPlaybackSubstepPolicyTest {

    @Test
    fun `ordinary playback intervals keep existing one-hour cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(totalSeconds = 3_600.0)

        assertEquals(3_600.0, effective, 0.0)
    }

    @Test
    fun `large playback intervals raise max substep adaptively`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(totalSeconds = 86_400.0)

        assertEquals(7_200.0, effective, 0.0)
    }

    @Test
    fun `very large playback intervals clamp at bounded upper cap`() {
        val effective = LabSession.effectivePlaybackMaxSubstepSeconds(totalSeconds = 864_000.0)

        assertEquals(43_200.0, effective, 0.0)
    }
}
