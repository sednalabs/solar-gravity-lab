package com.graciousgazelles.solarlab.render.core

/**
 * Camera-aware scene-packet policy for hybrid CPU/GPU rendering.
 *
 * The authoritative simulation remains unchanged; this policy only governs what gets packed for
 * presentation and how aggressively tracers/trails are culled or simplified for a given view.
 */
data class ScenePacketBuildPolicy(
    val nearTracerExtentFactor: Double = 1.5,
    val mediumTracerExtentFactor: Double = 6.0,
    val farTracerExtentFactor: Double = 24.0,
    val nearTracerBudget: Int = 8_192,
    val mediumTracerBudget: Int = 16_384,
    val farTracerBudget: Int = 24_576,
    val trailSimplificationTolerancePx: Double = 2.0,
    val maxTrailVerticesPerTrail: Int = 256,
    val selectedTrailAlphaBoost: Double = 1.25,
) {
    init {
        require(nearTracerExtentFactor > 0.0) { "nearTracerExtentFactor must be positive." }
        require(mediumTracerExtentFactor >= nearTracerExtentFactor) {
            "mediumTracerExtentFactor must be >= nearTracerExtentFactor."
        }
        require(farTracerExtentFactor >= mediumTracerExtentFactor) {
            "farTracerExtentFactor must be >= mediumTracerExtentFactor."
        }
        require(nearTracerBudget >= 0) { "nearTracerBudget must be non-negative." }
        require(mediumTracerBudget >= 0) { "mediumTracerBudget must be non-negative." }
        require(farTracerBudget >= 0) { "farTracerBudget must be non-negative." }
        require(trailSimplificationTolerancePx >= 0.0) {
            "trailSimplificationTolerancePx must be non-negative."
        }
        require(maxTrailVerticesPerTrail >= 2) { "maxTrailVerticesPerTrail must be at least 2." }
        require(selectedTrailAlphaBoost >= 1.0) {
            "selectedTrailAlphaBoost must be at least 1.0."
        }
    }
}

enum class TracerLodTier {
    NEAR,
    MEDIUM,
    FAR,
}
