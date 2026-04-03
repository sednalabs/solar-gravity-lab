package com.graciousgazelles.solarlab.app

import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import com.graciousgazelles.solarlab.app.databinding.DialogBodyEditorBinding
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.GravitationalRole

internal object BodyEditorDialogs {

    fun show(
        activity: MainActivity,
        draft: EditableBodyDraft,
        isNewBody: Boolean,
        onSave: (EditableBodyDraft) -> Unit,
        onDelete: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        val binding = DialogBodyEditorBinding.inflate(LayoutInflater.from(activity))
        val categories = BodyCategory.entries.toList()
        val roles = GravitationalRole.entries.toList()

        binding.spinnerBodyCategory.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            categories.map(::prettyCategoryLabel),
        )
        binding.spinnerBodyRole.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            roles.map(::prettyRoleLabel),
        )

        binding.editBodyName.setText(draft.name)
        binding.spinnerBodyCategory.setSelection(categories.indexOf(draft.category).coerceAtLeast(0))
        binding.spinnerBodyRole.setSelection(roles.indexOf(draft.gravitationalRole).coerceAtLeast(0))
        binding.editMassKg.setText(draft.massKg.toEditorString())
        binding.editRadiusM.setText(draft.radiusM.toEditorString())
        binding.editPositionX.setText(draft.positionM.x.toEditorString())
        binding.editPositionY.setText(draft.positionM.y.toEditorString())
        binding.editPositionZ.setText(draft.positionM.z.toEditorString())
        binding.editVelocityX.setText(draft.velocityMps.x.toEditorString())
        binding.editVelocityY.setText(draft.velocityMps.y.toEditorString())
        binding.editVelocityZ.setText(draft.velocityMps.z.toEditorString())
        binding.editColorHex.setText(draft.colorArgb.toUInt().toString(16).uppercase().padStart(8, '0'))
        binding.checkPlaceOnScene.isVisible = isNewBody
        binding.checkPlaceOnScene.isChecked = draft.placeOnSceneAfterSave
        binding.textBodyEditorError.text = ""

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (isNewBody) "Add object" else "Edit ${draft.name}")
            .setView(binding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (isNewBody) "Save" else "Apply", null)
            .apply {
                if (onDelete != null) {
                    setNeutralButton("Delete", null)
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseDraft(binding, categories, roles)
                if (parsed == null) {
                    return@setOnClickListener
                }
                onSave(parsed.copy(existingBodyId = draft.existingBodyId))
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                onDelete?.invoke()
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            onDismiss?.invoke()
        }

        dialog.show()
    }

    private fun parseDraft(
        binding: DialogBodyEditorBinding,
        categories: List<BodyCategory>,
        roles: List<GravitationalRole>,
    ): EditableBodyDraft? {
        fun fail(message: String): EditableBodyDraft? {
            binding.textBodyEditorError.text = message
            return null
        }

        val name = binding.editBodyName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) return fail("Name is required.")

        val massKg = binding.editMassKg.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Mass must be a valid number.")
        if (massKg < 0.0) return fail("Mass must be zero or positive.")

        val radiusM = binding.editRadiusM.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Radius must be a valid number.")
        if (radiusM < 0.0) return fail("Radius must be zero or positive.")

        val positionX = binding.editPositionX.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Position X must be a valid number.")
        val positionY = binding.editPositionY.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Position Y must be a valid number.")
        val positionZ = binding.editPositionZ.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Position Z must be a valid number.")

        val velocityX = binding.editVelocityX.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Velocity X must be a valid number.")
        val velocityY = binding.editVelocityY.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Velocity Y must be a valid number.")
        val velocityZ = binding.editVelocityZ.text?.toString()?.trim().orEmpty().toDoubleOrNull()
            ?: return fail("Velocity Z must be a valid number.")

        val colorArgb = parseColorArgb(binding.editColorHex.text?.toString().orEmpty())
            ?: return fail("Color must be 6 or 8 hex digits.")

        val category = categories.getOrNull(binding.spinnerBodyCategory.selectedItemPosition)
            ?: return fail("Choose a body category.")
        val role = roles.getOrNull(binding.spinnerBodyRole.selectedItemPosition)
            ?: return fail("Choose a gravitational role.")

        binding.textBodyEditorError.text = ""
        return EditableBodyDraft(
            name = name,
            category = category,
            gravitationalRole = role,
            massKg = massKg,
            radiusM = radiusM,
            positionM = Vector3d(positionX, positionY, positionZ),
            velocityMps = Vector3d(velocityX, velocityY, velocityZ),
            colorArgb = colorArgb,
            placeOnSceneAfterSave = binding.checkPlaceOnScene.isVisible && binding.checkPlaceOnScene.isChecked,
        )
    }

    private fun parseColorArgb(raw: String): Int? {
        val stripped = raw.trim().removePrefix("#")
        val normalized = when (stripped.length) {
            6 -> "#FF$stripped"
            8 -> "#$stripped"
            else -> return null
        }
        return runCatching { Color.parseColor(normalized) }.getOrNull()
    }

    private fun prettyCategoryLabel(category: BodyCategory): String = when (category) {
        BodyCategory.STAR -> "Star"
        BodyCategory.PLANET -> "Planet"
        BodyCategory.MOON -> "Moon"
        BodyCategory.DWARF_PLANET -> "Dwarf planet"
        BodyCategory.ASTEROID -> "Asteroid"
        BodyCategory.COMET -> "Comet"
        BodyCategory.TEST_OBJECT -> "Test object"
        BodyCategory.PROBE -> "Probe"
    }

    private fun prettyRoleLabel(role: GravitationalRole): String = when (role) {
        GravitationalRole.MASSIVE -> "Massive (mutual gravity)"
        GravitationalRole.TRACER -> "Tracer (passive)"
    }
}
