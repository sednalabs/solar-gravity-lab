package com.graciousgazelles.solarlab.feature.lab.data

import android.content.Context
import com.graciousgazelles.solarlab.core.simulation.CartesianSeedBundle
import com.graciousgazelles.solarlab.core.simulation.CartesianSeedBundleParser
import com.graciousgazelles.solarlab.core.simulation.CartesianSeedBundleValidator

object CartesianSeedBundleAssetLoader {

    const val DEFAULT_ASSET_PATH: String = "ephemeris/solarlab_horizons_seed_bundle_v1.tsv"

    fun loadIfAvailable(
        context: Context,
        assetPath: String = DEFAULT_ASSET_PATH,
    ): CartesianSeedBundle? {
        val assetManager = context.assets
        val available = assetManager.list("ephemeris")?.contains(assetPath.substringAfterLast('/')) == true
        if (!available) {
            return null
        }

        return runCatching {
            assetManager.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                val bundle = CartesianSeedBundleParser.parse(reader.readText())
                val validation = CartesianSeedBundleValidator.validate(bundle)
                if (!validation.isUsable) {
                    null
                } else {
                    bundle
                }
            }
        }.getOrNull()
    }
}
