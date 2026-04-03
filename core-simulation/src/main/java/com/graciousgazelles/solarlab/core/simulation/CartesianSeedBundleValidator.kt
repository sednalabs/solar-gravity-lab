package com.graciousgazelles.solarlab.core.simulation

data class CartesianSeedBundleValidation(
    val errors: List<String>,
    val warnings: List<String>,
) {
    val isUsable: Boolean get() = errors.isEmpty()
}

object CartesianSeedBundleValidator {

    val requiredSunThroughDwarfPlanetIds: Set<String> = linkedSetOf(
        "sun",
        "mercury",
        "venus",
        "earth",
        "mars",
        "jupiter",
        "saturn",
        "uranus",
        "neptune",
        "ceres",
        "pluto",
        "haumea",
        "makemake",
        "eris",
    )

    fun validate(
        bundle: CartesianSeedBundle,
        requiredBodyIds: Set<String> = requiredSunThroughDwarfPlanetIds,
    ): CartesianSeedBundleValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!bundle.metadata.positionUnits.equals("m", ignoreCase = true)) {
            errors += "Bundle position_units must be 'm'"
        }
        if (!bundle.metadata.velocityUnits.equals("m/s", ignoreCase = true)) {
            errors += "Bundle velocity_units must be 'm/s'"
        }
        if (!bundle.metadata.timeScale.equals("TDB", ignoreCase = true)) {
            errors += "Bundle time_scale must be 'TDB'"
        }
        if (!bundle.metadata.frame.equals("ICRF", ignoreCase = true)) {
            warnings += "Bundle frame is '${bundle.metadata.frame}', not the recommended ICRF"
        }

        val missing = requiredBodyIds.filterNot(bundle::hasRecord)
        if (missing.isNotEmpty()) {
            warnings += "Bundle is missing bodies: ${missing.joinToString(", ")}"
        }

        bundle.recordsByBodyId.values.forEach { record ->
            if (!record.centerId.equals(bundle.metadata.centerId, ignoreCase = false)) {
                errors += "Record '${record.bodyId}' center_id '${record.centerId}' does not match bundle center_id '${bundle.metadata.centerId}'"
            }
            if (!record.frame.equals(bundle.metadata.frame, ignoreCase = false)) {
                errors += "Record '${record.bodyId}' frame '${record.frame}' does not match bundle frame '${bundle.metadata.frame}'"
            }
            if (record.epochJdTdb != bundle.metadata.epochJdTdb) {
                errors += "Record '${record.bodyId}' epoch_jd_tdb ${record.epochJdTdb} does not match bundle epoch_jd_tdb ${bundle.metadata.epochJdTdb}"
            }
        }

        return CartesianSeedBundleValidation(
            errors = errors,
            warnings = warnings,
        )
    }
}
