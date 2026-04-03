package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.GravitationalRole

object OrbitingBodyCatalogParser {

    private val requiredColumns = listOf(
        "body_id",
        "name",
        "category",
        "role",
        "host_body_id",
        "mass_kg",
        "radius_m",
        "color_argb",
        "epoch_jd_tdb",
        "a_m",
        "e",
        "i_deg",
        "node_deg",
        "peri_deg",
        "mean_deg",
        "enabled_by_default",
        "notes",
    )

    fun parse(text: String): List<CatalogBodyDefinition> {
        val lines = text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
            .toList()
        require(lines.isNotEmpty()) { "Missing catalog rows" }
        val header = lines.first().split('\t')
        require(header == requiredColumns) {
            "Unexpected catalog columns. Expected ${requiredColumns.joinToString("\t")}, got ${header.joinToString("\t")}" 
        }
        return lines.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == requiredColumns.size) {
                "Invalid catalog record on line ${index + 2}: expected ${requiredColumns.size} tab-separated fields"
            }
            val row = requiredColumns.zip(fields).toMap()
            CatalogBodyDefinition(
                id = row.getValue("body_id"),
                name = row.getValue("name"),
                category = BodyCategory.valueOf(row.getValue("category")),
                gravitationalRole = GravitationalRole.valueOf(row.getValue("role")),
                hostBodyId = row.getValue("host_body_id"),
                massKg = row.getValue("mass_kg").toDouble(),
                radiusM = row.getValue("radius_m").toDouble(),
                colorArgb = parseColorArgb(row.getValue("color_argb")),
                orbit = KeplerianOrbitAtEpoch(
                    epochJdTdb = row.getValue("epoch_jd_tdb").toDouble(),
                    semiMajorAxisM = row.getValue("a_m").toDouble(),
                    eccentricity = row.getValue("e").toDouble(),
                    inclinationRad = row.getValue("i_deg").toDouble() * (Math.PI / 180.0),
                    longitudeOfAscendingNodeRad = row.getValue("node_deg").toDouble() * (Math.PI / 180.0),
                    argumentOfPeriapsisRad = row.getValue("peri_deg").toDouble() * (Math.PI / 180.0),
                    meanAnomalyAtEpochRad = row.getValue("mean_deg").toDouble() * (Math.PI / 180.0),
                ),
                enabledByDefault = row.getValue("enabled_by_default").equals("true", ignoreCase = true),
                notes = row.getValue("notes").ifBlank { null },
            )
        }
    }

    private fun parseColorArgb(raw: String): Int {
        val stripped = raw.trim().removePrefix("#")
        val normalized = when (stripped.length) {
            6 -> "FF$stripped"
            8 -> stripped
            else -> error("color_argb must be 6 or 8 hex digits")
        }
        return normalized.toULong(16).toLong().toInt()
    }
}
