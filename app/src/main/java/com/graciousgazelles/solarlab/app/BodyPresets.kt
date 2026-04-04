package com.graciousgazelles.solarlab.app

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.DensityPreset
import com.graciousgazelles.solarlab.core.model.GravitationalRole

internal data class BodyPresetChoice(
    val label: String,
    val description: String,
    val createDraft: () -> EditableBodyDraft,
)

internal object BodyPresets {

    fun options(): List<BodyPresetChoice> = listOf(
        BodyPresetChoice(
            label = "Custom object",
            description = "Start from the editable default values.",
            createDraft = { EditableBodyDraft.newDefault() },
        ),
        BodyPresetChoice(
            label = "Earth-like planet",
            description = "Rocky massive body with Earth-scale mass and density.",
            createDraft = {
                draft(
                    name = "Earth-like Planet",
                    category = BodyCategory.PLANET,
                    role = GravitationalRole.MASSIVE,
                    massKg = 5.97237e24,
                    densityKgPerM3 = 5_514.0,
                    colorArgb = 0xFF5DA9FF.toInt(),
                )
            },
        ),
        BodyPresetChoice(
            label = "Moon-like body",
            description = "Small rocky massive body for host-capture or impact tests.",
            createDraft = {
                draft(
                    name = "Moon-like Body",
                    category = BodyCategory.MOON,
                    role = GravitationalRole.MASSIVE,
                    massKg = 7.342e22,
                    densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
                    colorArgb = 0xFFCFCFE8.toInt(),
                )
            },
        ),
        BodyPresetChoice(
            label = "Gas giant",
            description = "Large diffuse massive body for broad orbital work.",
            createDraft = {
                draft(
                    name = "Gas Giant",
                    category = BodyCategory.PLANET,
                    role = GravitationalRole.MASSIVE,
                    massKg = 1.8982e27,
                    densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
                    colorArgb = 0xFFD1A16A.toInt(),
                )
            },
        ),
        BodyPresetChoice(
            label = "Supermassive anchor",
            description = "Very heavy object for strong capture and collision tests.",
            createDraft = {
                draft(
                    name = "Supermassive Anchor",
                    category = BodyCategory.STAR,
                    role = GravitationalRole.MASSIVE,
                    massKg = 1.0e30,
                    densityKgPerM3 = DensityPreset.METALLIC_KG_PER_M3,
                    colorArgb = 0xFFFFD166.toInt(),
                )
            },
        ),
        BodyPresetChoice(
            label = "Probe",
            description = "Light tracer for navigation, visibility, and path testing.",
            createDraft = {
                draft(
                    name = "Probe",
                    category = BodyCategory.PROBE,
                    role = GravitationalRole.TRACER,
                    massKg = 1.0e4,
                    densityKgPerM3 = DensityPreset.METALLIC_KG_PER_M3,
                    colorArgb = 0xFF7DDEFF.toInt(),
                )
            },
        ),
        BodyPresetChoice(
            label = "Dense asteroid",
            description = "Small tracer body for close approach and impact testing.",
            createDraft = {
                draft(
                    name = "Dense Asteroid",
                    category = BodyCategory.ASTEROID,
                    role = GravitationalRole.TRACER,
                    massKg = 1.0e15,
                    densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
                    colorArgb = 0xFFB7B7D7.toInt(),
                )
            },
        ),
        BodyPresetChoice(
            label = "Comet",
            description = "Icy tracer with a fast, visible approach vector.",
            createDraft = {
                draft(
                    name = "Comet",
                    category = BodyCategory.COMET,
                    role = GravitationalRole.TRACER,
                    massKg = 1.0e13,
                    densityKgPerM3 = DensityPreset.ICY_KG_PER_M3,
                    colorArgb = 0xFF9AD9FF.toInt(),
                )
            },
        ),
    )

    private fun draft(
        name: String,
        category: BodyCategory,
        role: GravitationalRole,
        massKg: Double,
        densityKgPerM3: Double,
        colorArgb: Int,
        positionM: Vector3d = Vector3d.ZERO,
        velocityMps: Vector3d = Vector3d.ZERO,
    ): EditableBodyDraft {
        val radiusM = BodyFactory.radiusFromMassAndDensity(
            massKg = massKg,
            densityKgPerM3 = densityKgPerM3,
        )
        return EditableBodyDraft(
            name = name,
            category = category,
            gravitationalRole = role,
            massKg = massKg,
            radiusM = radiusM,
            positionM = positionM,
            velocityMps = velocityMps,
            colorArgb = colorArgb,
            placeOnSceneAfterSave = true,
        )
    }
}
