package com.sednalabs.solarlab.benchmark

import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sednalabs.solarlab.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Separate from the simulator: every native case runs in isolated scratch state. */
class BenchmarkActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: BenchmarkFileStore
    private lateinit var runner: BenchmarkRunner
    private val frames = BenchmarkFrameProbe()
    private var discovery by mutableStateOf<BenchmarkDiscovery?>(null)
    private var progress by mutableStateOf(BenchmarkProgress(phase = "Discovering", detail = "Reading this build's native capabilities…"))
    private var sessions by mutableStateOf<List<SavedBenchmarkSession>>(emptyList())
    private var message by mutableStateOf<String?>(null)
    private var exporting by mutableStateOf(false)
    private var tab by mutableStateOf("Run")
    private var hints by mutableStateOf(false)
    private var requestedMode by mutableStateOf(0)
    private var runJob: Job? = null
    private var priorRequestedOrientation: Int? = null
    private var pendingExportSession: String? = null
    private val saveArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val sessionId = pendingExportSession
        pendingExportSession = null
        if (uri != null && sessionId != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "w") ?: error("Could not open destination")
                    store.exportZip(File(store.root, sessionId), output)
                }
                message = "Report saved. Attach the ZIP when you want to analyse the results."
            } catch (error: Exception) { message = "Could not save report: ${error.message}" }
            finally { exporting = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.BENCHMARK_ENABLED) { finish(); return }
        pendingExportSession = savedInstanceState?.getString("pending_export_session")
        store = BenchmarkFileStore(File(filesDir, "benchmarks"))
        runner = BenchmarkRunner(applicationContext, store, frames)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xffa6d6ff), background = Color(0xff0b1220), surface = Color(0xff152237))) {
                Surface(modifier = Modifier.fillMaxSize()) { BenchmarkScreen() }
            }
        }
        scope.launch {
            try {
                discovery = runner.discover()
                progress = BenchmarkProgress(phase = if (discovery?.ready == true) "Ready" else "Native discovery incomplete",
                    detail = if (discovery?.ready == true) "Start with Quick comparison, then export the report." else "The native catalogue is unavailable. Saved sessions remain exportable.")
                refreshSessions()
            } catch (error: Exception) { message = "Discovery failed: ${error.message}" }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export_session", pendingExportSession)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) scope.launch {
            LiveResearchCapture.awaitClosed()
            refreshSessions()
        }
    }

    override fun onStop() {
        runJob?.cancel(CancellationException("activity_stopped; partial observations retained"))
        frames.stop()
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun start(plan: BenchmarkPlan, selectedCaseKey: String? = null) {
        val available = discovery ?: return
        if (runJob?.isActive == true || !available.ready) return
        message = null
        progress = BenchmarkProgress(running = true, phase = "Preparing", detail = "Creating a durable report and warming the selected kernels…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { preferredDisplayModeId = requestedMode }
        priorRequestedOrientation = requestedOrientation
        val orientationLockRequested = runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED }.isSuccess
        frames.start()
        runJob = scope.launch {
            try { runner.run(available, plan, BenchmarkOptions(hints, requestedMode, selectedCaseKey, orientationLockRequested)) { progress = it } }
            catch (_: CancellationException) { /* Runner commits the partial report before returning. */ }
            catch (error: Exception) { progress = progress.copy(running = false, phase = "Stopped", detail = error.message ?: "Run failed") }
            finally {
                frames.stop()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
                priorRequestedOrientation?.let { previous -> runCatching { requestedOrientation = previous } }
                priorRequestedOrientation = null
                progress = progress.copy(running = false)
                scope.launch { refreshSessions() }
            }
        }
    }

    private suspend fun refreshSessions() {
        sessions = withContext(Dispatchers.IO) {
            store.sessions().map { directory ->
                val manifest = runCatching { JSONObject(File(directory, "manifest.json").readText()) }.getOrNull()
                val summary = runCatching { JSONObject(File(directory, "summary.json").readText()) }.getOrNull()
                SavedBenchmarkSession(directory.name, manifest?.optString("plan", "Recovered report") ?: "Recovered report",
                    manifest?.optString("status", "interrupted") ?: "interrupted", manifest?.optString("started_utc", directory.name) ?: directory.name,
                    if (manifest?.optString("plan") == "LIVE_OBSERVATORY" && summary != null) {
                        "${summary.optLong("events_written")} observations · ${summary.optLong("queue_dropped_events")} queue drops"
                    } else summary?.optJSONObject("counts")?.let { "${it.optInt("PASS")} passed · ${it.optInt("FAIL")} failed · ${it.optInt("SKIP")} skipped" }
                        ?: "Partial observations available")
            }
        }
    }

    private fun save(session: SavedBenchmarkSession) {
        pendingExportSession = session.id
        saveArchive.launch("solar-lab-benchmark-${session.id}.zip")
    }

    @Composable
    private fun BenchmarkScreen() {
        LazyColumn(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.padding(top = 28.dp)) {
                    Text("SOLAR GRAVITY LAB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Device research lab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Compare the hardware and numerical paths on this phone. Reports stay here until you export them.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text("${android.os.Build.MODEL} · ${BuildConfig.BUILD_COMMIT.take(12)}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Run", "Sessions", "Experiments").forEach { name ->
                        if (name == tab) Button(onClick = { tab = name }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(name) }
                        else OutlinedButton(onClick = { tab = name }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(name) }
                    }
                }
            }
            if (message != null) item { Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary) }
            if (exporting) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing report…") }
            when (tab) {
                "Run" -> {
                    item { ProgressCard() }
                    item { ControlsCard() }
                    item {
                        Text("Keep this page open. Current orientation is held during a run where Android permits. Backgrounding or Activity recreation stops safely and saves partial data. Heat pauses count toward elapsed time.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Keep brightness, charging, case and room conditions consistent. Raw battery readings describe the whole device, not CPU or GPU power.", style = MaterialTheme.typography.bodySmall)
                    }
                    sessions.firstOrNull()?.let { session -> item { SavedSessionCard(session) } }
                }
                "Sessions" -> {
                    if (sessions.isEmpty()) item { Text("Completed, cancelled and recovered runs will appear here.") }
                    items(sessions, key = { it.id }) { SavedSessionCard(it) }
                }
                else -> {
                    item {
                        val cases = discovery?.cases.orEmpty()
                        Text("${cases.count { it.runnable }} runnable native cases · ${cases.count { !it.runnable }} explicitly unavailable", style = MaterialTheme.typography.titleMedium)
                        Text("Implementation status comes from this build. Use Compare this case to run one experiment with its reference and the full fixture/step schedule, up to 10 minutes.", style = MaterialTheme.typography.bodySmall)
                    }
                    val opportunities = discovery?.research?.optJSONArray("opportunities")
                    if (opportunities != null) items(opportunities.length()) { index ->
                        opportunities.optJSONObject(index)?.let { opportunity ->
                            CatalogueCard("${opportunity.opt("id")}. ${opportunity.optString("title")}", opportunity.optString("status"), opportunity.optString("reason"))
                        }
                    }
                    item { Text("Native case details", style = MaterialTheme.typography.titleLarge) }
                    items(discovery?.cases.orEmpty(), key = { it.key }) { case ->
                        CatalogueCard(case.label, case.status, case.reason, if (case.runnable) {
                            { tab = "Run"; start(BenchmarkPlan.FULL, case.key) }
                        } else null)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    @Composable
    private fun ProgressCard() {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xff1a3048)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(progress.phase, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text("${progress.elapsedSeconds / 60}:${(progress.elapsedSeconds % 60).toString().padStart(2, '0')}", fontFamily = FontFamily.Monospace)
                }
                Text(progress.detail, style = MaterialTheme.typography.bodyMedium)
                if (progress.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${progress.pairsCompleted}/${progress.pairsPlanned} comparisons · ${progress.pass} pass · ${progress.fail} fail · ${progress.skip} skip", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { runJob?.cancel(CancellationException("user_cancelled")) }) { Text("Stop and save partial run") }
                }
            }
        }
    }

    @Composable
    private fun ControlsCard() {
        val enabled = discovery?.ready == true && !progress.running && !exporting
        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Run a comparison", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { start(BenchmarkPlan.QUICK) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Quick comparison · up to 3 min") }
                OutlinedButton(onClick = { start(BenchmarkPlan.FULL) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Full investigation · up to 10 min") }
                OutlinedButton(onClick = { start(BenchmarkPlan.SUSTAINED) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Sustained stability · 15 min") }
                OutlinedButton(onClick = {
                    startActivity(Intent(this@BenchmarkActivity, com.sednalabs.solarlab.MainActivity::class.java)
                        .putExtra("open_observatory", true).putExtra("record_observatory", true))
                }, enabled = !progress.running && !exporting, modifier = Modifier.fillMaxWidth()) { Text("Record observatory · up to 30 min") }
                Text("Record observatory captures the real renderer while you explore. Back or backgrounding stops recording; export its session here.", style = MaterialTheme.typography.bodySmall)
                Text("Quick checks a subset. Full tries admitted cases, step counts and legal CPU placements within its time budget. Unrun work is labelled explicitly.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hints, onCheckedChange = { hints = it }, enabled = !progress.running)
                    Column { Text("Android performance hints"); Text("Dedicated benchmark thread", style = MaterialTheme.typography.bodySmall) }
                }
                val modes = display?.supportedModes.orEmpty().toList()
                val mode = modes.firstOrNull { it.modeId == requestedMode }
                OutlinedButton(onClick = {
                    val choices = listOf(0) + modes.map { it.modeId }
                    requestedMode = choices[(choices.indexOf(requestedMode) + 1) % choices.size]
                }, enabled = !progress.running) {
                    Text(if (mode == null) "Display: system choice" else "Display: ${mode.refreshRate.toInt()} Hz · ${mode.physicalWidth}×${mode.physicalHeight}")
                }
                Text("Android may override a display request. Actual modes are recorded. Experiments do not change your saved simulation.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun SavedSessionCard(session: SavedBenchmarkSession) {
        Card {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(session.plan.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                Text("${session.status} · ${session.started.take(19).replace('T', ' ')} UTC", style = MaterialTheme.typography.bodySmall)
                Text(session.summary, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { save(session) }, enabled = !progress.running && !exporting && session.status != "running") { Text("Save ZIP") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { share(session) }, enabled = !progress.running && !exporting && session.status != "running") { Text("Share ZIP") }
                }
            }
        }
    }

    @Composable
    private fun CatalogueCard(title: String, status: String, reason: String, onCompare: (() -> Unit)? = null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(status.replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall)
            if (onCompare != null) {
                OutlinedButton(onClick = onCompare, enabled = discovery?.ready == true && !progress.running && !exporting) {
                    Text("Compare this case")
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }

    private fun share(session: SavedBenchmarkSession) = scope.launch {
        exporting = true
        try {
            val archive = withContext(Dispatchers.IO) {
                val directory = File(cacheDir, "benchmark-exports").also { check(it.isDirectory || it.mkdirs()) }
                File(directory, "solar-lab-${session.id}.zip").also { file ->
                    file.outputStream().use { store.exportZip(File(store.root, session.id), it) }
                }
            }
            val uri = FileProvider.getUriForFile(this@BenchmarkActivity, "$packageName.benchmark.files", archive)
            val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Solar Gravity Lab device benchmark")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("Benchmark report", uri)
            startActivity(Intent.createChooser(intent, "Share benchmark report"))
        } catch (error: Exception) { message = "Could not share report: ${error.message}" }
        finally { exporting = false }
    }
}

private data class SavedBenchmarkSession(val id: String, val plan: String, val status: String, val started: String, val summary: String)
