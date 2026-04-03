package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d

/**
 * Parser for the SolarLab Horizons/DE cartesian seed bundle exchange format.
 *
 * Format:
 * - UTF-8 text
 * - comment lines begin with '#'
 * - metadata header uses key=value lines
 * - a separator line consisting of '---'
 * - tab-separated body records with a mandatory column header row
 */
object CartesianSeedBundleParser {

    private val requiredHeaderKeys = setOf(
        "bundle_version",
        "dataset_name",
        "source",
        "epoch_jd_tdb",
        "center_id",
        "frame",
        "time_scale",
        "position_units",
        "velocity_units",
    )

    private val requiredColumns = listOf(
        "body_id",
        "name",
        "target",
        "center_id",
        "frame",
        "epoch_jd_tdb",
        "x_m",
        "y_m",
        "z_m",
        "vx_mps",
        "vy_mps",
        "vz_mps",
        "source",
    )

    fun parse(text: String): CartesianSeedBundle {
        val lines = text.lineSequence().toList()
        val separatorIndex = lines.indexOfFirst { it.trim() == "---" }
        require(separatorIndex >= 0) { "Missing metadata/data separator line '---'" }

        val metadataLines = lines.subList(0, separatorIndex)
        val dataLines = lines.subList(separatorIndex + 1, lines.size)

        val metadataMap = parseMetadata(metadataLines)
        val metadata = metadataFromMap(metadataMap)
        val records = parseRecords(dataLines)

        return CartesianSeedBundle(
            metadata = metadata,
            recordsByBodyId = records.associateBy { it.bodyId },
        )
    }

    private fun parseMetadata(lines: List<String>): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEachIndexed
            }
            val equalsIndex = line.indexOf('=')
            require(equalsIndex > 0) {
                "Invalid metadata line ${index + 1}: expected key=value"
            }
            val key = line.substring(0, equalsIndex).trim()
            val value = line.substring(equalsIndex + 1).trim()
            entries[key] = value
        }

        val missing = requiredHeaderKeys - entries.keys
        require(missing.isEmpty()) {
            "Missing required bundle metadata keys: ${missing.sorted().joinToString(", ")}"
        }
        return entries
    }

    private fun metadataFromMap(values: Map<String, String>): CartesianSeedBundleMetadata =
        CartesianSeedBundleMetadata(
            bundleVersion = values.getValue("bundle_version"),
            datasetName = values.getValue("dataset_name"),
            source = values.getValue("source"),
            epochJdTdb = values.getValue("epoch_jd_tdb").toDouble(),
            centerId = values.getValue("center_id"),
            frame = values.getValue("frame"),
            timeScale = values.getValue("time_scale"),
            positionUnits = values.getValue("position_units"),
            velocityUnits = values.getValue("velocity_units"),
            generatedAtUtc = values["generated_at_utc"],
            notes = values["notes"],
        )

    private fun parseRecords(lines: List<String>): List<CartesianSeedRecord> {
        val contentLines = lines.map { it.trimEnd() }.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        require(contentLines.isNotEmpty()) { "Missing tab-separated body records" }

        val headerColumns = contentLines.first().split('\t')
        require(headerColumns == requiredColumns) {
            "Unexpected bundle columns. Expected ${requiredColumns.joinToString("\t")}, got ${headerColumns.joinToString("\t")}"
        }

        return contentLines.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == requiredColumns.size) {
                "Invalid seed record on data line ${index + 2}: expected ${requiredColumns.size} tab-separated fields"
            }
            val values = requiredColumns.zip(fields).toMap()
            CartesianSeedRecord(
                bodyId = values.getValue("body_id"),
                displayName = values.getValue("name"),
                targetSpecifier = values.getValue("target"),
                centerId = values.getValue("center_id"),
                frame = values.getValue("frame"),
                epochJdTdb = values.getValue("epoch_jd_tdb").toDouble(),
                positionM = Vector3d(
                    x = values.getValue("x_m").toDouble(),
                    y = values.getValue("y_m").toDouble(),
                    z = values.getValue("z_m").toDouble(),
                ),
                velocityMps = Vector3d(
                    x = values.getValue("vx_mps").toDouble(),
                    y = values.getValue("vy_mps").toDouble(),
                    z = values.getValue("vz_mps").toDouble(),
                ),
                source = values.getValue("source"),
            )
        }
    }
}
