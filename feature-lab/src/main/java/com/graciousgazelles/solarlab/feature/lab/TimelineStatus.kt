package com.graciousgazelles.solarlab.feature.lab

import com.graciousgazelles.solarlab.core.model.TimelineMode

data class TimelineStatus(
    val mode: TimelineMode,
    val referenceEpochJdTdb: Double?,
    val absoluteJulianDateTdb: Double?,
    val playbackSpeed: PlaybackSpeedPreset,
    val stepQuantum: StepQuantumPreset,
    val simulationBacklogSeconds: Double,
    val canJumpAbsolute: Boolean,
    // True when Back has a valid action (catalog step or sandbox checkpoint restore).
    val canStepBackward: Boolean,
)
