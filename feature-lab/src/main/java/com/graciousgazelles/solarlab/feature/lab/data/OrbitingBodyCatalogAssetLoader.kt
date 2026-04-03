package com.graciousgazelles.solarlab.feature.lab.data

import android.content.Context
import com.graciousgazelles.solarlab.core.simulation.CatalogBodyDefinition
import com.graciousgazelles.solarlab.core.simulation.OrbitingBodyCatalogParser

object OrbitingBodyCatalogAssetLoader {

    const val PLANETARY_MOONS_ASSET_PATH: String = "catalogs/planetary_moons_v1.tsv"
    const val CURATED_SMALL_BODIES_ASSET_PATH: String = "catalogs/small_bodies_curated_v1.tsv"

    fun loadIfAvailable(
        context: Context,
        assetPath: String,
    ): List<CatalogBodyDefinition> {
        val assetManager = context.assets
        val parent = assetPath.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = assetPath.substringAfterLast('/')
        val available = assetManager.list(parent)?.contains(fileName) == true
        if (!available) {
            return emptyList()
        }
        return runCatching {
            assetManager.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                OrbitingBodyCatalogParser.parse(reader.readText())
            }
        }.getOrElse { emptyList() }
    }
}
