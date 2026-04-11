package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class EditableBodyDraft(
    val existingBodyId: String? = null,
    val existingHostBodyId: String? = null,
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
            hostBodyId = existingHostBodyId,
        )
    }

    companion object {
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

        fun fromBodyState(body: BodyState): EditableBodyDraft = EditableBodyDraft(
            existingBodyId = body.id,
            existingHostBodyId = body.hostBodyId,
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
    abs(this) in 1.0e-3..1.0e4 -> String.format(Locale.US, "%.6f", this).trimEnd('0').trimEnd('.')
    else -> String.format(Locale.US, "%.6e", this)
}
