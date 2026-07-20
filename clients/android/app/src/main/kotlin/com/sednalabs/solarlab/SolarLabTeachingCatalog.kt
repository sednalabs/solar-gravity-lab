package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.RuntimeBodyClass

internal data class SolarLabTeachingCatalogEntry(
    val bodyId: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val spawnBodyClass: RuntimeBodyClass = RuntimeBodyClass.Planet,
    val spawnMassKg: Double,
    val spawnRadiusM: Double,
)

internal object SolarLabTeachingCatalog {
    val entries: List<SolarLabTeachingCatalogEntry> = listOf(
        SolarLabTeachingCatalogEntry(
            bodyId = "sun",
            displayName = "Sun",
            aliases = listOf("star", "heliocentric anchor", "center"),
            spawnBodyClass = RuntimeBodyClass.Star,
            spawnMassKg = 1.98847e30,
            spawnRadiusM = 6.9634e8,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "mercury",
            displayName = "Mercury",
            aliases = listOf("innermost planet", "fast orbit"),
            spawnMassKg = 3.3011e23,
            spawnRadiusM = 2.4397e6,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "venus",
            displayName = "Venus",
            aliases = listOf("second planet", "bright planet"),
            spawnMassKg = 4.8675e24,
            spawnRadiusM = 6.0518e6,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "earth",
            displayName = "Earth",
            aliases = listOf("home world", "blue planet"),
            spawnMassKg = 5.97237e24,
            spawnRadiusM = 6.3710e6,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "moon",
            displayName = "Moon",
            aliases = listOf("lunar companion", "earth moon"),
            spawnBodyClass = RuntimeBodyClass.Moon,
            spawnMassKg = 7.342e22,
            spawnRadiusM = 1.7374e6,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "mars",
            displayName = "Mars",
            aliases = listOf("red planet", "outer rocky body"),
            spawnMassKg = 6.4171e23,
            spawnRadiusM = 3.3895e6,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "jupiter",
            displayName = "Jupiter",
            aliases = listOf("gas giant", "largest planet"),
            spawnMassKg = 1.8982e27,
            spawnRadiusM = 6.9911e7,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "saturn",
            displayName = "Saturn",
            aliases = listOf("ringed planet", "teaching gas giant"),
            spawnMassKg = 5.6834e26,
            spawnRadiusM = 5.8232e7,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "uranus",
            displayName = "Uranus",
            aliases = listOf("ice giant", "tilted planet"),
            spawnMassKg = 8.6810e25,
            spawnRadiusM = 2.5362e7,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "neptune",
            displayName = "Neptune",
            aliases = listOf("outer ice giant", "deep blue planet"),
            spawnMassKg = 1.02413e26,
            spawnRadiusM = 2.4622e7,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "pluto",
            displayName = "Pluto",
            aliases = listOf("dwarf planet", "kuiper belt"),
            spawnBodyClass = RuntimeBodyClass.DwarfPlanet,
            spawnMassKg = 1.303e22,
            spawnRadiusM = 1.1883e6,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "ceres",
            displayName = "Ceres",
            aliases = listOf("main belt dwarf", "asteroid belt"),
            spawnBodyClass = RuntimeBodyClass.DwarfPlanet,
            spawnMassKg = 9.3835e20,
            spawnRadiusM = 4.73e5,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "vesta",
            displayName = "Vesta",
            aliases = listOf("large asteroid", "main belt"),
            spawnBodyClass = RuntimeBodyClass.SmallBody,
            spawnMassKg = 2.59076e20,
            spawnRadiusM = 2.627e5,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "halley",
            displayName = "Halley",
            aliases = listOf("halleys comet", "periodic comet"),
            spawnBodyClass = RuntimeBodyClass.Comet,
            spawnMassKg = 2.2e14,
            spawnRadiusM = 5.5e3,
        ),
        SolarLabTeachingCatalogEntry(
            bodyId = "sedna",
            displayName = "Sedna",
            aliases = listOf("trans-neptunian", "oort cloud approach"),
            spawnBodyClass = RuntimeBodyClass.DwarfPlanet,
            spawnMassKg = 4.0e21,
            spawnRadiusM = 4.95e5,
        ),
    )
}

internal fun SolarLabTeachingCatalogEntry.matches(query: String): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return true

    return listOf(displayName, bodyId, *aliases.toTypedArray())
        .any { candidate -> candidate.lowercase().contains(normalized) }
}
