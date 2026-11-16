package com.sednalabs.solarlab.runtime

import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExperimentFacadeTest {
    private class Bridge(
        private val refreshSignals: () -> List<RuntimeSignal> = { emptyList() },
    ) : RuntimeBridge {
        var failImport = false
        var imported: ByteArray? = null
        var validated: ByteArray? = null
        override suspend fun validateSavedSession(bytes: ByteArray): String {
            check(!failImport) { "Unsupported version" }
            validated = bytes
            return "lab.earth-moon"
        }
        override fun connect(): Flow<RuntimeSignal> = flowOf(RuntimeSignal.Connected(1L))
        override suspend fun refresh(): List<RuntimeSignal> = refreshSignals()
        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> = emptyList()
        override suspend fun loadScenario(scenarioId: String): List<RuntimeSignal> = emptyList()
        override suspend fun exportSavedSession() = SavedRuntimeSession("lab.earth-moon", byteArrayOf(1, 2, 3))
        override suspend fun importSavedSession(bytes: ByteArray): List<RuntimeSignal> {
            check(!failImport) { "Unsupported version" }
            imported = bytes
            return listOf(RuntimeSignal.Connected(2L))
        }
    }
    private fun facade(bridge: Bridge, store: ExperimentStore) = BridgeBackedRuntimeFacade(
        bridge = bridge, developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()),
        boundaryDispatcher = Dispatchers.Default, experimentStore = store,
    )

    private class FailingListStore(private val backing: ExperimentStore) : ExperimentStore by backing {
        @Volatile var failList = false

        override fun list(): List<SavedExperimentInfo> {
            if (failList) throw IOException("Catalogue storage is temporarily unavailable")
            return backing.list()
        }
    }

    @Test
    fun freshFacadeListsAndReopensSavedNativeBytes() = runBlocking {
        val root = Files.createTempDirectory("facade-experiment-test").toFile()
        try {
            val first = facade(Bridge(), FileExperimentStore(root))
            first.startSession()
            first.saveExperiment("First orbit")
            val info = first.savedExperiments.value.single()
            assertEquals("Saved First orbit", first.uiState.value.noticeLine)
            val bridge = Bridge()
            val reopened = facade(bridge, FileExperimentStore(root))
            reopened.startSession()
            assertEquals(info, reopened.savedExperiments.value.single())
            reopened.loadExperiment(info.id)
            assertArrayEquals(byteArrayOf(1, 2, 3), bridge.imported)
            assertEquals(2L, reopened.uiState.value.sessionHandle)
            assertNull(reopened.uiState.value.pendingActionLabel)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedNativeRestorePreservesLiveSessionAndReportsFailure() = runBlocking {
        val root = Files.createTempDirectory("facade-experiment-test").toFile()
        try {
            val bridge = Bridge()
            val facade = facade(bridge, FileExperimentStore(root))
            facade.startSession()
            facade.saveExperiment("Keep this")
            bridge.failImport = true
            facade.loadExperiment(facade.savedExperiments.value.single().id)
            assertEquals(1L, facade.uiState.value.sessionHandle)
            assertEquals(SessionConnectionState.Active, facade.uiState.value.connectionState)
            assertTrue(facade.uiState.value.noticeLine.orEmpty().contains("Could not open"))
            assertNull(facade.uiState.value.pendingActionLabel)
        } finally { root.deleteRecursively() }
    }
    @Test
    fun documentRoundTripCreatesNewCopyWithoutOpeningOrOverwritingLiveWorld() = runBlocking {
        val root = Files.createTempDirectory("facade-document-test").toFile()
        try {
            val bridge = Bridge()
            val store = FileExperimentStore(root)
            val facade = facade(bridge, store)
            facade.startSession()
            facade.saveExperiment("Keep my branches")
            val original = facade.savedExperiments.value.single()
            val output = ByteArrayOutputStream()
            facade.exportExperiment(original.id) { output }
            assertEquals("Exported Keep my branches", facade.uiState.value.noticeLine)
            facade.importExperiment { ByteArrayInputStream(output.toByteArray()) }
            val copies = facade.savedExperiments.value
            assertEquals(2, copies.size)
            assertEquals(2, copies.map { it.id }.toSet().size)
            assertTrue(copies.any { it.id == original.id })
            assertArrayEquals(byteArrayOf(1, 2, 3), bridge.validated)
            assertNull(bridge.imported)
            assertEquals(1L, facade.uiState.value.sessionHandle)
            val imported = copies.single { it.id != original.id }
            assertArrayEquals(store.read(original.id).bytes, store.read(imported.id).bytes)
            facade.deleteExperiment(imported.id)
            assertEquals(listOf(original), facade.savedExperiments.value)
            assertEquals(1L, facade.uiState.value.sessionHandle)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectedNativeDocumentAndMismatchedIdentityLeaveCatalogueAndWorldUnchanged() = runBlocking {
        val root = Files.createTempDirectory("facade-rejected-document").toFile()
        try {
            val bridge = Bridge()
            val facade = facade(bridge, FileExperimentStore(root))
            facade.startSession(); facade.saveExperiment("Keep this")
            val original = facade.savedExperiments.value.single()
            val bytes = ByteArrayOutputStream().also { stream ->
                facade.exportExperiment(original.id) { stream }
            }.toByteArray()
            bridge.failImport = true
            facade.importExperiment { ByteArrayInputStream(bytes) }
            assertEquals(listOf(original), facade.savedExperiments.value)
            assertEquals(ShellNoticeTone.Caution, facade.uiState.value.noticeTone)
            bridge.failImport = false
            val altered = ExperimentDocumentCodec.read(ByteArrayInputStream(bytes)).let {
                it.copy(info = it.info.copy(scenarioId = "other.scene"))
            }
            val mismatch = ByteArrayOutputStream().also { ExperimentDocumentCodec.write(it, altered) }.toByteArray()
            facade.importExperiment { ByteArrayInputStream(mismatch) }
            assertEquals(listOf(original), facade.savedExperiments.value)
            assertEquals(1L, facade.uiState.value.sessionHandle)
            assertNull(bridge.imported)
            assertNull(facade.uiState.value.pendingActionLabel)
            assertEquals(ShellNoticeTone.Caution, facade.uiState.value.noticeTone)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun providerCloseFailureIsNotReportedAsSuccessfulExport() = runBlocking {
        val root = Files.createTempDirectory("facade-export-failure").toFile()
        try {
            val facade = facade(Bridge(), FileExperimentStore(root))
            facade.startSession(); facade.saveExperiment("Safe local copy")
            val saved = facade.savedExperiments.value.single()
            facade.exportExperiment(saved.id) {
                object : ByteArrayOutputStream() {
                    override fun close() { throw IOException("Provider failed to commit") }
                }
            }
            assertEquals(ShellNoticeTone.Caution, facade.uiState.value.noticeTone)
            assertTrue(facade.uiState.value.noticeLine.orEmpty().startsWith("Export did not finish"))
            assertEquals(listOf(saved), facade.savedExperiments.value)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun exportContinuesWhenWaitingActivityIsCancelled() = runBlocking {
        val root = Files.createTempDirectory("facade-retained-export").toFile()
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val facade = facade(Bridge(), FileExperimentStore(root))
            facade.startSession(); facade.saveExperiment("Across rotation")
            val saved = facade.savedExperiments.value.single()
            val destination = ByteArrayOutputStream()
            val activity = launch {
                facade.exportExperiment(saved.id) {
                    opened.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    destination
                }
            }
            assertTrue(withContext(Dispatchers.IO) { opened.await(3, TimeUnit.SECONDS) })
            activity.cancelAndJoin()
            release.countDown()
            withTimeout(3_000) {
                while (facade.uiState.value.pendingActionLabel != null) delay(10)
            }
            assertEquals("Exported Across rotation", facade.uiState.value.noticeLine)
            assertArrayEquals(byteArrayOf(1, 2, 3), ExperimentDocumentCodec.read(
                ByteArrayInputStream(destination.toByteArray())).bytes)
        } finally { release.countDown(); root.deleteRecursively() }
    }

    @Test
    fun routineSnapshotAndRenderUpdatesPreserveTransferBusyStateAndResult() = runBlocking {
        val root = Files.createTempDirectory("facade-transfer-status").toFile()
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            var revision = 0L
            val facade = facade(Bridge { routineSignals(++revision) }, FileExperimentStore(root))
            facade.startSession()
            facade.saveExperiment("Across refreshes")
            val saved = facade.savedExperiments.value.single()
            val destination = ByteArrayOutputStream()
            val export = launch {
                facade.exportExperiment(saved.id) {
                    opened.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    destination
                }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { opened.await(5, TimeUnit.SECONDS) })
                val busy = facade.experimentStatus.value
                assertEquals("Exporting experiment", busy.busyLabel)
                assertNull(busy.notice)

                facade.refresh()
                assertEquals(1L, facade.uiState.value.snapshot?.revision)
                assertEquals("routine packet 1", facade.uiState.value.renderPacketSummary)
                assertNotNull(facade.uiState.value.renderFrame)
                assertEquals(busy, facade.experimentStatus.value)
            } finally {
                release.countDown()
                withTimeout(5_000) { export.join() }
            }

            val completed = facade.experimentStatus.value
            assertNull(completed.busyLabel)
            assertEquals("Exported Across refreshes", completed.notice)
            assertEquals(ShellNoticeTone.Positive, completed.tone)
            facade.refresh()
            assertEquals(2L, facade.uiState.value.snapshot?.revision)
            assertEquals("routine packet 2", facade.uiState.value.renderPacketSummary)
            assertEquals(completed, facade.experimentStatus.value)
            assertArrayEquals(byteArrayOf(1, 2, 3), ExperimentDocumentCodec.read(
                ByteArrayInputStream(destination.toByteArray())).bytes)
        } finally { release.countDown(); root.deleteRecursively() }
    }

    @Test
    fun committedSaveReportsCatalogueFailureUntilPublicRefreshRecovers() = runBlocking {
        val root = Files.createTempDirectory("facade-save-list-failure").toFile()
        try {
            val backing = FileExperimentStore(root)
            val store = FailingListStore(backing)
            val facade = facade(Bridge(), store)
            facade.startSession()
            store.failList = true

            facade.saveExperiment("Committed orbit")
            val committed = backing.list().single()
            assertEquals("Committed orbit", committed.name)
            assertArrayEquals(byteArrayOf(1, 2, 3), backing.read(committed.id).bytes)
            assertTrue(facade.savedExperiments.value.isEmpty())
            assertEquals("Saved Committed orbit; the saved list is currently unavailable.",
                facade.experimentStatus.value.notice)
            assertEquals(ShellNoticeTone.Caution, facade.experimentStatus.value.tone)
            assertNull(facade.experimentStatus.value.busyLabel)

            facade.refreshExperimentCatalogue()
            assertEquals(ShellNoticeTone.Caution, facade.experimentStatus.value.tone)
            assertTrue(facade.savedExperiments.value.isEmpty())
            store.failList = false
            facade.refreshExperimentCatalogue()
            assertEquals(listOf(committed), facade.savedExperiments.value)
            assertNull(facade.experimentStatus.value.notice)
            assertEquals(ShellNoticeTone.Neutral, facade.experimentStatus.value.tone)
            assertNull(facade.experimentStatus.value.busyLabel)
            assertEquals(listOf(committed), backing.list())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun committedImportKeepsCatalogueCautionThroughRenderRefreshAndCanRecover() = runBlocking {
        val root = Files.createTempDirectory("facade-import-list-failure").toFile()
        try {
            val backing = FileExperimentStore(root)
            val original = backing.save("Portable orbit", SavedRuntimeSession("lab.earth-moon", byteArrayOf(1, 2, 3)))
            val document = ByteArrayOutputStream().also {
                ExperimentDocumentCodec.write(it, backing.read(original.id))
            }.toByteArray()
            val store = FailingListStore(backing)
            val bridge = Bridge { routineSignals(1L) }
            val facade = facade(bridge, store)
            facade.startSession()
            store.failList = true

            facade.importExperiment { ByteArrayInputStream(document) }
            val committed = backing.list()
            assertEquals(2, committed.size)
            assertEquals(2, committed.map { it.id }.toSet().size)
            assertEquals(listOf(original), facade.savedExperiments.value)
            assertArrayEquals(byteArrayOf(1, 2, 3), bridge.validated)
            assertNull(bridge.imported)
            assertEquals(1L, facade.uiState.value.sessionHandle)
            val caution = facade.experimentStatus.value
            assertEquals("Imported Portable orbit; the saved list is currently unavailable.", caution.notice)
            assertEquals(ShellNoticeTone.Caution, caution.tone)
            assertNull(caution.busyLabel)
            facade.refresh()
            assertEquals("routine packet 1", facade.uiState.value.renderPacketSummary)
            assertEquals(caution, facade.experimentStatus.value)

            store.failList = false
            facade.refreshExperimentCatalogue()
            assertEquals(committed, facade.savedExperiments.value)
            assertNull(facade.experimentStatus.value.notice)
            assertEquals(ShellNoticeTone.Neutral, facade.experimentStatus.value.tone)
            assertNull(facade.experimentStatus.value.busyLabel)
            assertEquals(committed, backing.list())
        } finally { root.deleteRecursively() }
    }

    private fun routineSignals(revision: Long): List<RuntimeSignal> = listOf(
        RuntimeSignal.SnapshotUpdated(
            NativeSnapshotSummaryResult(
                result = NativeResult(code = 0), scenarioId = "lab.earth-moon", activeBranchId = "main",
                bodyCount = 0, epochSeconds = revision.toDouble(), paused = true,
                simSecondsPerRealSecond = 1.0, observerMode = RuntimeObserverMode.SystemFrame.nativeCode,
                timelineSemantics = 1, revision = revision,
            ),
            sessionHandle = 1L,
        ),
        RuntimeSignal.RenderPacketReady(
            lease = PacketLease(
                packetHandle = revision,
                packet = NativeVulkanScenePacket(
                    packetHandle = revision, sceneRevision = "v$revision", epochSeconds = revision.toDouble(),
                    observerMode = 0, timelineSemantics = 1,
                    camera = NativeVulkanCameraPacket(
                        frameOriginX = 0.0, frameOriginY = 0.0, frameOriginZ = 0.0,
                        positionFromOriginX = 0f, positionFromOriginY = 0f, positionFromOriginZ = 10f,
                        targetFromOriginX = 0f, targetFromOriginY = 0f, targetFromOriginZ = 0f,
                        upX = 0f, upY = 1f, upZ = 0f, verticalFovDegrees = 60f, exposure = 1f,
                    ),
                    bodyCount = 0, tracerCount = 0, trailSpanCount = 0, trailVertexCount = 0, directionalLightCount = 0,
                    diagnostics = NativeRenderDiagnostics(frameNumber = revision, cpuExtractMs = 0f, gpuUploadMs = 0f, droppedFrames = 0),
                    provenanceSource = null, provenanceVersion = null, provenanceManifestId = null,
                    provenanceManifestDigest = null, provenancePackageDigest = null,
                    bodyInstances = null, tracerInstances = null, trailSpans = null, trailVertices = null, directionalLights = null,
                ),
                summaryLineValue = "routine packet $revision",
                releaseAction = {},
            ),
            revision = revision,
            sessionHandle = 1L,
        ),
    )
}
