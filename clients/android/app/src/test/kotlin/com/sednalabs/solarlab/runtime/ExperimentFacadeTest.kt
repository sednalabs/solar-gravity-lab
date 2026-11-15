package com.sednalabs.solarlab.runtime

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExperimentFacadeTest {
    private class Bridge : RuntimeBridge {
        var failImport = false
        var imported: ByteArray? = null
        override fun connect(): Flow<RuntimeSignal> = flowOf(RuntimeSignal.Connected(1L))
        override suspend fun refresh(): List<RuntimeSignal> = emptyList()
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
}
