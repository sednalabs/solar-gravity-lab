package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.GravitationalRole

data class CatalogBodyDefinition(
    val id: String,
    val name: String,
    val category: BodyCategory,
    val gravitationalRole: GravitationalRole,
    val hostBodyId: String,
    val massKg: Double,
    val radiusM: Double,
    val colorArgb: Int,
    val orbit: KeplerianOrbitAtEpoch,
    val enabledByDefault: Boolean = true,
    val notes: String? = null,
)
