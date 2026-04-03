package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d

/**
 * Bundle metadata for a set of authoritative cartesian starter states.
 *
 * The intent is that external agents can generate this once from JPL Horizons (or an equivalent
 * DE-backed source), drop it into app assets, and the simulation will pick it up without any
 * physics- or renderer-layer changes.
 */
data class CartesianSeedBundleMetadata(
    val bundleVersion: String,
    val datasetName: String,
    val source: String,
    val epochJdTdb: Double,
    val centerId: String,
    val frame: String,
    val timeScale: String,
    val positionUnits: String,
    val velocityUnits: String,
    val generatedAtUtc: String? = null,
    val notes: String? = null,
)

data class CartesianSeedRecord(
    val bodyId: String,
    val displayName: String,
    val targetSpecifier: String,
    val centerId: String,
    val frame: String,
    val epochJdTdb: Double,
    val positionM: Vector3d,
    val velocityMps: Vector3d,
    val source: String,
)

data class CartesianSeedBundle(
    val metadata: CartesianSeedBundleMetadata,
    val recordsByBodyId: Map<String, CartesianSeedRecord>,
) {
    fun recordFor(bodyId: String): CartesianSeedRecord? = recordsByBodyId[bodyId]

    fun requireRecord(bodyId: String): CartesianSeedRecord =
        recordFor(bodyId) ?: error("No cartesian seed record for '$bodyId'")

    fun hasRecord(bodyId: String): Boolean = recordsByBodyId.containsKey(bodyId)
}
