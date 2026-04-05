package com.graciousgazelles.solarlab.app

import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AdapterView
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
        val addBodyPresets = EditableBodyDraft.addBodyPresets()

        binding.spinnerBodyCategory.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            categories.map(activity::prettyCategoryLabel),
        )
        binding.spinnerBodyRole.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            roles.map { activity.prettyRoleLabel(it, includeRoleHints = true) },
        )
        binding.spinnerBodyPreset.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            addBodyPresets.map { activity.getString(it.labelResId) },
        )

        applyDraftToForm(
            binding = binding,
            draft = draft,
            categories = categories,
            roles = roles,
            preservePlacementChoice = false,
        )
        val initialPresetIndex = addBodyPresets.indexOfFirst { preset ->
            preset.draft.name == draft.name &&
                preset.draft.category == draft.category &&
                preset.draft.gravitationalRole == draft.gravitationalRole &&
                preset.draft.massKg == draft.massKg &&
                preset.draft.radiusM == draft.radiusM
        }.coerceAtLeast(0)
        binding.spinnerBodyPreset.setSelection(initialPresetIndex, false)
        binding.checkPlaceOnScene.isVisible = isNewBody
        binding.checkPlaceOnScene.isChecked = draft.placeOnSceneAfterSave
        binding.groupBodyPreset.isVisible = isNewBody
        binding.textBodyEditorError.text = ""

        if (isNewBody) {
            var presetReady = false
            binding.spinnerBodyPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val preset = addBodyPresets.getOrNull(position)?.draft ?: return
                    if (!presetReady) {
                        presetReady = true
                        return
                    }
                    applyDraftToForm(
                        binding = binding,
                        draft = preset,
                        categories = categories,
                        roles = roles,
                        preservePlacementChoice = true,
                    )
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

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

        val massKg = parseValidatedDouble(
            rawValue = binding.editMassKg.text?.toString(),
            fieldLabel = "Mass",
            allowNegative = false,
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null

        val radiusM = parseValidatedDouble(
            rawValue = binding.editRadiusM.text?.toString(),
            fieldLabel = "Radius",
            allowNegative = false,
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null

        val positionX = parseValidatedDouble(
            rawValue = binding.editPositionX.text?.toString(),
            fieldLabel = "Position X",
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null
        val positionY = parseValidatedDouble(
            rawValue = binding.editPositionY.text?.toString(),
            fieldLabel = "Position Y",
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null
        val positionZ = parseValidatedDouble(
            rawValue = binding.editPositionZ.text?.toString(),
            fieldLabel = "Position Z",
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null

        val velocityX = parseValidatedDouble(
            rawValue = binding.editVelocityX.text?.toString(),
            fieldLabel = "Velocity X",
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null
        val velocityY = parseValidatedDouble(
            rawValue = binding.editVelocityY.text?.toString(),
            fieldLabel = "Velocity Y",
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null
        val velocityZ = parseValidatedDouble(
            rawValue = binding.editVelocityZ.text?.toString(),
            fieldLabel = "Velocity Z",
            onError = { binding.textBodyEditorError.text = it },
        ) ?: return null

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

    private fun applyDraftToForm(
        binding: DialogBodyEditorBinding,
        draft: EditableBodyDraft,
        categories: List<BodyCategory>,
        roles: List<GravitationalRole>,
        preservePlacementChoice: Boolean,
    ) {
        val shouldPlaceOnScene = if (preservePlacementChoice) {
            binding.checkPlaceOnScene.isChecked
        } else {
            draft.placeOnSceneAfterSave
        }
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
        binding.checkPlaceOnScene.isChecked = shouldPlaceOnScene
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

    private fun parseValidatedDouble(
        rawValue: String?,
        fieldLabel: String,
        allowNegative: Boolean = true,
        onError: (String) -> Unit,
    ): Double? {
        val parsed = rawValue?.trim().orEmpty().toDoubleOrNull()
            ?: run {
                onError("$fieldLabel must be a valid number.")
                return null
            }
        if (!allowNegative && parsed < 0.0) {
            onError("$fieldLabel must be zero or positive.")
            return null
        }
        return parsed
    }
}

internal fun Context.prettyCategoryLabel(category: BodyCategory): String = when (category) {
    BodyCategory.STAR -> getString(R.string.category_star)
    BodyCategory.PLANET -> getString(R.string.category_planet)
    BodyCategory.MOON -> getString(R.string.category_moon)
    BodyCategory.DWARF_PLANET -> getString(R.string.category_dwarf_planet)
    BodyCategory.ASTEROID -> getString(R.string.category_asteroid)
    BodyCategory.COMET -> getString(R.string.category_comet)
    BodyCategory.TEST_OBJECT -> getString(R.string.category_test_object)
    BodyCategory.PROBE -> getString(R.string.category_probe)
}

internal fun Context.prettyRoleLabel(role: GravitationalRole, includeRoleHints: Boolean = false): String = when (role) {
    GravitationalRole.MASSIVE -> if (includeRoleHints) {
        "Massive (mutual gravity)"
    } else {
        getString(R.string.role_massive_body)
    }
    GravitationalRole.TRACER -> if (includeRoleHints) {
        "Tracer (passive)"
    } else {
        getString(R.string.role_tracer)
    }
}
