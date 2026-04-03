package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.TimelineMode

data class TimelineStatus(
    val mode: TimelineMode,
    val referenceEpochJdTdb: Double?,
    val absoluteJulianDateTdb: Double?,
    val playbackSpeed: PlaybackSpeedPreset,
    val stepQuantum: StepQuantumPreset,
    val canJumpAbsolute: Boolean,
    val canStepBackward: Boolean,
)
