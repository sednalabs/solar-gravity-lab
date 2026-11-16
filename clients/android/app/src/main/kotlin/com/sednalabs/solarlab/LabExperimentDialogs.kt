package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.ExperimentStatus
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeScenarioPack
import com.sednalabs.solarlab.runtime.SavedExperimentInfo
import com.sednalabs.solarlab.runtime.ShellNoticeTone
import com.sednalabs.solarlab.runtime.ShellUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Captured authoritative values. Editors pause first and keep this reference frame fixed. */
internal data class LabBodyEditorSession(
    val body: RuntimeBodyState,
    val earth: RuntimeBodyState?,
    val creating: Boolean,
    val existingBodyIds: Set<String>,
    val referenceAxes: String = "scenario axes",
)

internal data class LabBodyDraft(
    val name: String,
    val massKg: String,
    val radiusM: String,
    val sourceGravity: Boolean,
    val positionKm: List<String>,
    val velocityKmPerSecond: List<String>,
)

internal fun LabBodyEditorSession.initialDraft(): LabBodyDraft {
    val reference = earth?.takeUnless { it.bodyId == body.bodyId }
    fun number(value: Double) = value.toString()
    return LabBodyDraft(
        name = body.bodyId,
        massKg = number(body.massKg),
        radiusM = number(body.radiusM),
        sourceGravity = body.sourceMassKg > 0.0,
        positionKm = listOf(
            number((body.positionX - (reference?.positionX ?: 0.0)) / 1_000.0),
            number((body.positionY - (reference?.positionY ?: 0.0)) / 1_000.0),
            number((body.positionZ - (reference?.positionZ ?: 0.0)) / 1_000.0),
        ),
        velocityKmPerSecond = listOf(
            number((body.velocityX - (reference?.velocityX ?: 0.0)) / 1_000.0),
            number((body.velocityY - (reference?.velocityY ?: 0.0)) / 1_000.0),
            number((body.velocityZ - (reference?.velocityZ ?: 0.0)) / 1_000.0),
        ),
    )
}

internal fun labDraftValidation(draft: LabBodyDraft, session: LabBodyEditorSession): String? {
    val values = draft.positionKm + draft.velocityKmPerSecond
    if (draft.positionKm.size != 3 || draft.velocityKmPerSecond.size != 3 || values.any { it.toDoubleOrNull()?.isFinite() != true }) {
        return "Enter six finite numbers for position and velocity. Scientific notation is supported."
    }
    if (values.any { !(it.toDouble() * 1_000.0).isFinite() }) {
        return "A coordinate is too large to represent in metres. Use a smaller value."
    }
    if (session.creating) {
        if (!draft.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) {
            return "Use 1–64 letters, numbers, dots, underscores or hyphens for the probe name."
        }
        if (session.existingBodyIds.any { it.equals(draft.name, ignoreCase = true) }) {
            return "A body already has this name. Choose another."
        }
    }
    val mass = draft.massKg.toDoubleOrNull()
    val radius = draft.radiusM.toDoubleOrNull()
    if (mass == null || !mass.isFinite() || mass < 0.0 || radius == null || !radius.isFinite() || radius <= 0.0) {
        return "Mass must be finite and non-negative; radius must be finite and positive."
    }
    if (draft.sourceGravity && mass == 0.0) return "An attracting probe needs a positive mass."
    return null
}

internal fun labDraftCommand(draft: LabBodyDraft, session: LabBodyEditorSession): RuntimeCommand? {
    if (labDraftValidation(draft, session) != null) return null
    val reference = session.earth?.takeUnless { it.bodyId == session.body.bodyId }
    val initial = session.initialDraft()
    val p = draft.positionKm.map { it.toDouble() * 1_000.0 }
    val v = draft.velocityKmPerSecond.map { it.toDouble() * 1_000.0 }
    // Untouched fields retain their exact f64 bits instead of taking a display-unit round trip.
    val x = if (draft.positionKm[0] == initial.positionKm[0]) session.body.positionX else p[0] + (reference?.positionX ?: 0.0)
    val y = if (draft.positionKm[1] == initial.positionKm[1]) session.body.positionY else p[1] + (reference?.positionY ?: 0.0)
    val z = if (draft.positionKm[2] == initial.positionKm[2]) session.body.positionZ else p[2] + (reference?.positionZ ?: 0.0)
    val vx = if (draft.velocityKmPerSecond[0] == initial.velocityKmPerSecond[0]) session.body.velocityX else v[0] + (reference?.velocityX ?: 0.0)
    val vy = if (draft.velocityKmPerSecond[1] == initial.velocityKmPerSecond[1]) session.body.velocityY else v[1] + (reference?.velocityY ?: 0.0)
    val vz = if (draft.velocityKmPerSecond[2] == initial.velocityKmPerSecond[2]) session.body.velocityZ else v[2] + (reference?.velocityZ ?: 0.0)
    if (listOf(x, y, z, vx, vy, vz).any { !it.isFinite() }) return null
    return if (session.creating) {
        val mass = draft.massKg.toDouble()
        RuntimeCommand.SpawnBody(
            bodyId = draft.name, bodyClass = RuntimeBodyClass.Spacecraft,
            positionX = x, positionY = y, positionZ = z,
            velocityX = vx, velocityY = vy, velocityZ = vz,
            massKg = mass, radiusM = draft.radiusM.toDouble(),
            sourceMassKg = if (draft.sourceGravity) mass else 0.0,
        )
    } else {
        // Kinematics-only edits preserve an explicitly authored source/physical
        // mass ratio, rather than silently normalising existing saved worlds.
        val mass = draft.massKg.toDouble()
        val source = if (draft.massKg == initial.massKg && draft.sourceGravity == initial.sourceGravity)
            session.body.sourceMassKg else if (draft.sourceGravity) mass else 0.0
        RuntimeCommand.SetBodyProperties(session.body.bodyId, mass, source,
            draft.radiusM.toDouble(), x, y, z, vx, vy, vz)
    }
}

/** A circular two-body authoring seed; the committed object is integrated by the real runtime. */
internal fun labProbeSeed(earth: RuntimeBodyState, existingIds: Set<String>, altitudeM: Double = 800_000.0): RuntimeBodyState {
    val name = generateSequence(1) { it + 1 }.map { "probe-$it" }
        .first { candidate -> existingIds.none { it.equals(candidate, ignoreCase = true) } }
    val distance = earth.radiusM + altitudeM
    val speed = sqrt(6.67430e-11 * earth.sourceMassKg / distance)
    return RuntimeBodyState(
        bodyId = name, bodyClass = RuntimeBodyClass.Spacecraft,
        massKg = 1_000.0, sourceMassKg = 0.0, radiusM = 2.0,
        positionX = earth.positionX + distance, positionY = earth.positionY, positionZ = earth.positionZ,
        velocityX = earth.velocityX, velocityY = earth.velocityY + speed, velocityZ = earth.velocityZ,
    )
}

@Composable
internal fun LabBodyInspectorDialog(
    body: RuntimeBodyState?,
    bodyName: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LabTags.INSPECTOR),
        title = { Text(bodyName) },
        confirmButton = { TextButton(onClick = onEdit, enabled = body != null,
            modifier = Modifier.testTag(LabTags.EDIT)) { Text("Edit mass & motion") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The simulation is paused while you inspect this body.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (body == null) {
                    Text("Reading the current state…")
                } else {
                    LabFact("Radius", formatLabDistance(body.radiusM))
                    LabFact("Mass", String.format(Locale.US, "%.6g kg", body.massKg))
                    LabFact("Gravity", if (body.sourceMassKg > 0.0) "Attracts other bodies" else "Feels gravity; does not perturb other bodies")
                    LabFact("Speed in simulation frame", String.format(Locale.US, "%,.3f km/s", sqrt(
                        body.velocityX * body.velocityX + body.velocityY * body.velocityY + body.velocityZ * body.velocityZ,
                    ) / 1_000.0))
                    HorizontalDivider()
                    Text("Position and velocity are read directly from the simulation. Visual size and the camera do not change the forces.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
internal fun LabBodyEditorDialog(
    session: LabBodyEditorSession,
    committing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCommit: (RuntimeCommand) -> Unit,
) {
    var draft by remember(session) { mutableStateOf(session.initialDraft()) }
    val validation = labDraftValidation(draft, session)
    val reference = session.earth?.takeUnless { it.bodyId == session.body.bodyId }
    val command = labDraftCommand(draft, session)
    AlertDialog(
        onDismissRequest = { if (!committing) onDismiss() },
        modifier = Modifier.testTag(LabTags.EDITOR),
        title = { Text(if (session.creating) "Add a probe" else "Edit ${session.body.bodyId.replaceFirstChar { it.titlecase() }}") },
        confirmButton = {
            Button(onClick = { command?.let(onCommit) }, enabled = !committing && command != null,
                modifier = Modifier.testTag(if (session.creating) LabTags.PROBE_COMMIT else LabTags.EDIT_COMMIT)) {
                Text(if (committing) "Applying…" else if (session.creating) "Create probe" else "Apply changes")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !committing) { Text("Cancel") } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (session.creating) "Start with a circular orbit 800 km above Earth. Adjust its initial state, then commit it to the paused simulation."
                    else "Change the initial state of this experiment. The simulation stays paused until you press Run.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (session.creating) {
                    session.earth?.let { earth ->
                        Text("ORBIT PRESET", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("Low · 800 km" to 800_000.0, "High · 20,000 km" to 20_000_000.0,
                                "Distant · 36,000 km" to 36_000_000.0).forEach { (label, altitude) ->
                                LabTextAction(label, {
                                    val seed = labProbeSeed(earth, session.existingBodyIds, altitude)
                                    draft = session.copy(body = seed).initialDraft().copy(
                                        name = draft.name, massKg = draft.massKg, radiusM = draft.radiusM,
                                        sourceGravity = draft.sourceGravity,
                                    )
                                }, enabled = !committing)
                            }
                        }
                    }
                    OutlinedTextField(value = draft.name, onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("Probe name") }, singleLine = true, enabled = !committing,
                        modifier = Modifier.fillMaxWidth().testTag(SolarLabTestTags.SPAWN_BODY_ID_FIELD))
                }
                Text(if (reference != null) "RELATIVE TO EARTH" else "SIMULATION REFERENCE FRAME",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text("X, Y and Z follow the ${session.referenceAxes}; they do not rotate with Earth. " +
                    "Position and velocity use the same fixed reference frame.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabVectorFields("Position · km", "position", draft.positionKm, !committing) { axis, value ->
                    draft = draft.copy(positionKm = draft.positionKm.toMutableList().also { it[axis] = value })
                }
                LabVectorFields("Velocity · km/s", "velocity", draft.velocityKmPerSecond, !committing) { axis, value ->
                    draft = draft.copy(velocityKmPerSecond = draft.velocityKmPerSecond.toMutableList().also { it[axis] = value })
                }
                run {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = draft.massKg, onValueChange = { draft = draft.copy(massKg = it) },
                            modifier = Modifier.weight(1f).testTag(SolarLabTestTags.SPAWN_BODY_MASS_FIELD),
                            label = { Text("Mass · kg") }, singleLine = true, enabled = !committing)
                        OutlinedTextField(value = draft.radiusM, onValueChange = { draft = draft.copy(radiusM = it) },
                            modifier = Modifier.weight(1f).testTag(SolarLabTestTags.SPAWN_BODY_RADIUS_FIELD),
                            label = { Text("Radius · m") }, singleLine = true, enabled = !committing)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft.sourceGravity, onCheckedChange = { draft = draft.copy(sourceGravity = it) }, enabled = !committing,
                            modifier = Modifier.testTag("lab.edit.gravity"))
                        Column {
                            Text("Attracts other bodies", style = MaterialTheme.typography.bodyMedium)
                            Text(if (draft.sourceGravity) "On: this mass changes other bodies’ motion." else "Off: mass is recorded, but other bodies are not perturbed.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("The circular seed is a two-body approximation. The running experiment also includes the other bodies and numerical integration error.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                (error ?: validation)?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

@Composable
private fun LabVectorFields(
    label: String,
    kind: String,
    values: List<String>,
    enabled: Boolean,
    onChange: (Int, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        // Full-width inputs keep signed scientific notation readable on a phone keyboard.
        listOf("x", "y", "z").forEachIndexed { index, axis ->
            OutlinedTextField(value = values[index], onValueChange = { onChange(index, it) },
                modifier = Modifier.fillMaxWidth().testTag(LabTags.component(kind, axis)),
                label = { Text(axis.uppercase(Locale.US)) }, singleLine = true, enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

internal object LabExperimentTags {
    const val IMPORT = "lab.saved.import"
    const val REMOVE_CONFIRM = "lab.saved.remove.confirm"
    fun export(id: String) = "lab.saved.export.$id"
    fun remove(id: String) = "lab.saved.remove.$id"
}

private fun experimentSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
    else -> "$bytes bytes"
}

@Composable
internal fun LabSavedExperimentsDialog(
    experiments: List<SavedExperimentInfo>,
    uiState: ShellUiState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onLoad: (String) -> Unit,
    onImport: () -> Unit = {},
    onExport: (SavedExperimentInfo) -> Unit = {},
    onDelete: (String) -> Unit = {},
    experimentStatus: ExperimentStatus = ExperimentStatus(
        busyLabel = uiState.pendingActionLabel, notice = uiState.noticeLine, tone = uiState.noticeTone,
    ),
) {
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("Earth–Moon experiment") }
    val busy = experimentStatus.busyLabel != null || uiState.pendingActionLabel != null || uiState.manualAdvance != null
    val validName = name.trim().let { it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LabTags.SAVED_DIALOG),
        title = { Text("Your experiments") },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (busy) "Back to lab" else "Done") } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Keep a moment, try another idea, come back. Every saved experiment opens paused.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Experiment name") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(LabTags.SAVE_NAME))
                if (!validName) Text("Use a name of 1–80 characters.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onSave(name.trim()) }, enabled = !busy && validName,
                    modifier = Modifier.fillMaxWidth().testTag(LabTags.SAVE_COMMIT)) {
                    Text("Save current experiment")
                }
                experimentStatus.busyLabel?.let { label ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                experimentStatus.notice?.takeIf {
                    experimentStatus.tone == ShellNoticeTone.Positive || experimentStatus.tone == ShellNoticeTone.Critical || experimentStatus.tone == ShellNoticeTone.Caution
                }?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.bodySmall,
                        color = if (experimentStatus.tone == ShellNoticeTone.Critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ON THIS DEVICE", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${experiments.size} of 256 copies · ${experimentSize(experiments.sumOf { it.sizeBytes })}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LabTextAction("Import", onImport, tag = LabExperimentTags.IMPORT, enabled = !busy,
                        description = "Import an experiment document")
                }
                Text("Export a saved copy to keep it outside the app or open it on another device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (experiments.isEmpty()) {
                    Text("Your first experiment starts here.", style = MaterialTheme.typography.bodyMedium)
                }
                experiments.forEach { experiment ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(experiment.name, style = MaterialTheme.typography.titleSmall)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(experiment.updatedAtEpochMillis)) + " · " + experimentSize(experiment.sizeBytes),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            experiment.problem?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                LabTextAction("Open", { onLoad(experiment.id) }, tag = LabTags.load(experiment.id),
                                    enabled = !busy && experiment.problem == null)
                                LabTextAction("Export", { onExport(experiment) }, tag = LabExperimentTags.export(experiment.id),
                                    enabled = !busy && experiment.problem == null)
                                LabTextAction("Remove", { removingId = experiment.id }, tag = LabExperimentTags.remove(experiment.id),
                                    enabled = !busy, description = "Remove saved copy of ${experiment.name}")
                            }
                        }
                    }
                }
            }
        },
    )
    experiments.firstOrNull { it.id == removingId }?.let { removing ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("Remove this saved copy?") },
            text = { Text("“${removing.name}” will be removed from this device. Your live experiment and exported documents remain available.") },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Keep it") } },
            confirmButton = {
                TextButton(onClick = { removingId = null; onDelete(removing.id) }, enabled = !busy,
                    modifier = Modifier.testTag(LabExperimentTags.REMOVE_CONFIRM)) { Text("Remove copy") }
            },
        )
    }
}

@Composable
internal fun LabScienceDialog(uiState: ShellUiState, scenarioPack: RuntimeScenarioPack, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The physics behind the lab") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Back to the lab") } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabScienceSection("The model", scenarioPack.modelSummary
                    ?: "Positions and velocities evolve under three-dimensional Newtonian gravity. The simulation uses double precision and a leapfrog integrator. Visual appearance does not change physical mass or radius.")
                LabScienceSection("Initial conditions", scenarioPack.provenance?.let { provenance ->
                    "$provenance. Reference epoch: ${scenarioPack.referenceEpoch ?: "recorded by the scene"}. These reference vectors seed the simulation; subsequent motion is computed by this model and will diverge from the full ephemeris."
                } ?: "This built-in scene uses approximate initial conditions. It is an experiment, not a live astronomical ephemeris.")
                LabScienceSection("What you see", buildString {
                    if (scenarioPack.scenarioId == "lab.earth-moon") {
                        append("The overview travels with Earth. Trails show motion relative to Earth using matching recorded or predicted Earth positions. With a close probe, the prediction focuses on its next orbit rather than a long, sparsely sampled path. ")
                    }
                    append("Surface orientation and atmospheric light are illustrative. Small objects have a minimum screen size so you can find them. The time display is elapsed simulation time from the scenario epoch.")
                })
                LabScienceSection("Surface imagery", "Earth uses NASA's Blue Marble map, credited to NASA/GSFC, Reto Stöckli and NASA's Earth Observatory. The Moon uses NASA's CGI Moon Kit from the LRO Camera and Laser Altimeter teams. These offline colour maps show surface features, not live weather or a terrain simulation. Other bodies use procedural surfaces. NASA has not reviewed or endorsed this app.")
                LabScienceSection("Your experiment", "Editing a body changes its initial position or velocity. A probe feels gravity by default without attracting the other bodies; enable Exert gravity to give it gravitational source mass.")
                LabScienceSection("Limits", "This model does not include relativistic corrections, tides, atmospheric drag or a resolved collision/debris simulation. Large time speeds can be limited to protect numerical accuracy. A reference seed does not make the subsequent simplified propagation a DE441 solution.")
                uiState.renderStatus.provenanceSource?.takeIf(String::isNotBlank)?.let {
                    LabScienceSection("Source recorded by this scene", it)
                }
            }
        },
    )
}

@Composable
private fun LabScienceSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
