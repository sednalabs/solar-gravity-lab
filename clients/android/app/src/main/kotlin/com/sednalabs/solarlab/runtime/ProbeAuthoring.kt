package com.sednalabs.solarlab.runtime

/** A point relative to the reference body at the SAME predicted epoch, in fixed world axes. */
data class ProbePreviewSample(
    val elapsedSeconds: Double,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
)

/** A bounded Rust forecast of a candidate; it has not changed the live experiment. */
data class ProbePreviewResult(
    val sessionHandle: Long,
    val baselineRevision: Long,
    val epochSeconds: Double,
    val candidate: RuntimeBodyState,
    val referenceBody: RuntimeBodyState,
    val samples: List<ProbePreviewSample>,
    val horizonSeconds: Double,
    val limited: Boolean,
    val intersectsReferenceBody: Boolean,
)

/** Undo returns to this epoch on a new branch, preserving the abandoned future. */
data class ProbeCommitResult(
    val sessionHandle: Long,
    val checkpointId: String,
    val bodyId: String,
    val epochSeconds: Double,
    val committedRevision: Long,
)

// These constructors are called directly by Rust JNI and covered by Native* R8 rules.
internal data class NativeProbePreviewResult(
    val baselineRevision: Long,
    val epochSeconds: Double,
    val horizonSeconds: Double,
    val limited: Boolean,
    val intersectsReferenceBody: Boolean,
    val referenceBodyValues: DoubleArray,
    val samples: DoubleArray,
)

internal data class NativeProbeCommitResult(
    val checkpointId: String,
    val committedRevision: Long,
    val epochSeconds: Double,
)

internal fun RuntimeBodyState.toNativeBodyValues(): DoubleArray = doubleArrayOf(
    bodyClass.nativeCode.toDouble(), massKg, sourceMassKg, radiusM,
    positionX, positionY, positionZ, velocityX, velocityY, velocityZ,
)

internal fun bodyStateFromNative(bodyId: String, values: DoubleArray): RuntimeBodyState {
    require(values.size == 10 && values.all(Double::isFinite)) { "Invalid body state from runtime" }
    val bodyClass = RuntimeBodyClass.values().firstOrNull { it.nativeCode.toDouble() == values[0] }
        ?: error("Unknown native body class")
    return RuntimeBodyState(bodyId, bodyClass, values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9])
}

internal fun NativeProbePreviewResult.toPreview(
    sessionHandle: Long,
    candidate: RuntimeBodyState,
    referenceBodyId: String,
): ProbePreviewResult {
    require(baselineRevision >= 0 && epochSeconds.isFinite() && epochSeconds >= 0.0 &&
        horizonSeconds.isFinite() && horizonSeconds > 0.0 && horizonSeconds <= 43_200.000001) {
        "Invalid preview identity or duration from runtime"
    }
    require(samples.size in 8..388 && samples.size % 4 == 0 && samples.all(Double::isFinite)) {
        "Invalid preview samples from runtime"
    }
    val points = samples.asList().chunked(4).map { ProbePreviewSample(it[0], it[1], it[2], it[3]) }
    require(points.first().elapsedSeconds == 0.0 && points.last().elapsedSeconds == horizonSeconds &&
        points.zipWithNext().all { (before, after) -> after.elapsedSeconds > before.elapsedSeconds }) {
        "Invalid preview timeline from runtime"
    }
    return ProbePreviewResult(
        sessionHandle, baselineRevision, epochSeconds, candidate,
        bodyStateFromNative(referenceBodyId, referenceBodyValues), points,
        horizonSeconds, limited, intersectsReferenceBody,
    )
}
