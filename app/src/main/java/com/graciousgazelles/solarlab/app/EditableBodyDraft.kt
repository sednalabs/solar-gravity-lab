package com.graciousgazelles.solarlab.app

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import java.util.Locale
import java.util.UUID

data class EditableBodyDraft(
    val existingBodyId: String? = null,
    val name: String,
    val category: BodyCategory,
    val gravitationalRole: GravitationalRole,
    val massKg: Double,
    val radiusM: Double,
    val positionM: Vector3d,
    val velocityMps: Vector3d,
    val colorArgb: Int,
    val placeOnSceneAfterSave: Boolean,
) {
    fun toBodyState(
        positionOverrideM: Vector3d? = null,
        velocityOverrideMps: Vector3d? = null,
    ): BodyState {
        val resolvedRadius = radiusM.coerceAtLeast(0.0)
        val resolvedMass = massKg.coerceAtLeast(0.0)
        val resolvedDensity = BodyFactory.densityFromMassAndRadius(
            massKg = resolvedMass,
            radiusM = resolvedRadius,
        )
        return BodyState(
            id = existingBodyId ?: "custom:${UUID.randomUUID()}",
            name = name.trim(),
            category = category,
            gravitationalRole = gravitationalRole,
            massKg = resolvedMass,
            radiusM = resolvedRadius,
            densityKgPerM3 = resolvedDensity,
            positionM = positionOverrideM ?: positionM,
            velocityMps = velocityOverrideMps ?: velocityMps,
            colorArgb = colorArgb,
        )
    }

    companion object {
        data class AddBodyPreset(
            val key: String,
            val labelResId: Int,
            val draft: EditableBodyDraft,
        )

        fun newDefault(): EditableBodyDraft = EditableBodyDraft(
            name = "Custom Object",
            category = BodyCategory.TEST_OBJECT,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.0e18,
            radiusM = 1.0e5,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFA726.toInt(),
            placeOnSceneAfterSave = true,
        )

        fun addBodyPresets(): List<AddBodyPreset> = listOf(
            AddBodyPreset(
                key = "custom",
                labelResId = R.string.add_body_preset_custom,
                draft = newDefault(),
            ),
            AddBodyPreset(
                key = "rocky_planet",
                labelResId = R.string.add_body_preset_rocky_planet,
                draft = EditableBodyDraft(
                    name = "Rocky Planet",
                    category = BodyCategory.PLANET,
                    gravitationalRole = GravitationalRole.MASSIVE,
                    massKg = 5.97e24,
                    radiusM = 6.37e6,
                    positionM = Vector3d(1.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0, 0.0),
                    velocityMps = Vector3d(0.0, 29_780.0, 0.0),
                    colorArgb = 0xFF5E92F3.toInt(),
                    placeOnSceneAfterSave = true,
                ),
            ),
            AddBodyPreset(
                key = "gas_giant",
                labelResId = R.string.add_body_preset_gas_giant,
                draft = EditableBodyDraft(
                    name = "Gas Giant",
                    category = BodyCategory.PLANET,
                    gravitationalRole = GravitationalRole.MASSIVE,
                    massKg = 1.90e27,
                    radiusM = 6.99e7,
                    positionM = Vector3d(5.2 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0, 0.0),
                    velocityMps = Vector3d(0.0, 13_100.0, 0.0),
                    colorArgb = 0xFFC58F4A.toInt(),
                    placeOnSceneAfterSave = true,
                ),
            ),
            AddBodyPreset(
                key = "moonlet",
                labelResId = R.string.add_body_preset_moonlet,
                draft = EditableBodyDraft(
                    name = "Moonlet",
                    category = BodyCategory.MOON,
                    gravitationalRole = GravitationalRole.MASSIVE,
                    massKg = 7.35e22,
                    radiusM = 1.74e6,
                    positionM = Vector3d(4.0e8, 0.0, 0.0),
                    velocityMps = Vector3d(0.0, 1_022.0, 0.0),
                    colorArgb = 0xFFD9DCE3.toInt(),
                    placeOnSceneAfterSave = true,
                ),
            ),
            AddBodyPreset(
                key = "probe_tracer",
                labelResId = R.string.add_body_preset_probe_tracer,
                draft = EditableBodyDraft(
                    name = "Probe",
                    category = BodyCategory.PROBE,
                    gravitationalRole = GravitationalRole.TRACER,
                    massKg = 1_200.0,
                    radiusM = 2.0,
                    positionM = Vector3d(0.0, 0.0, 0.0),
                    velocityMps = Vector3d(0.0, 9_800.0, 0.0),
                    colorArgb = 0xFF90CAF9.toInt(),
                    placeOnSceneAfterSave = true,
                ),
            ),
        )

        fun fromBodyState(body: BodyState): EditableBodyDraft = EditableBodyDraft(
            existingBodyId = body.id,
            name = body.name,
            category = body.category,
            gravitationalRole = body.gravitationalRole,
            massKg = body.massKg,
            radiusM = body.radiusM,
            positionM = body.positionM,
            velocityMps = body.velocityMps,
            colorArgb = body.colorArgb,
            placeOnSceneAfterSave = false,
        )
    }
}

internal fun Double.toEditorString(): String = when {
    this == 0.0 -> "0"
    kotlin.math.abs(this) in 1.0e-3..1.0e4 -> String.format(Locale.US, "%.6f", this).trimEnd('0').trimEnd('.')
    else -> String.format(Locale.US, "%.6e", this)
}
