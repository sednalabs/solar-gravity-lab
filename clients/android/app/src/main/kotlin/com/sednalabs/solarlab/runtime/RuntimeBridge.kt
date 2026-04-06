package com.sednalabs.solarlab.runtime

import com.sednalabs.solarlab.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rust runtime boundary for Android.
 * 
 * --- Handle Ownership Rules ---
 * 1. Kotlin owns the lifecycle of the `activeSessionHandle`.
 * 2. Successful `connect()` or `createSession()` calls return an "owned" handle.
 * 3. The caller MUST ensure `destroySession()` is called via the transport when the 
 *    session is no longer needed or if the boundary connection fails.
 * 4. Failure to release handles results in native memory leaks in the Rust world.
 *
 * Kotlin owns orchestration and lifecycle semantics.
 * Native transport owns ABI calls into `engine/ffi` via a JNI shim.
 */
internal interface RuntimeBridge {
    // Streamed connection events from the runtime host.
    fun connect(): Flow<RuntimeSignal>

    // Synchronous refresh/query path for already-bound handles.
    suspend fun refresh(): List<RuntimeSignal>

    // Command path: apply intent and surface resulting state deltas.
    suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal>
}

internal class JniRuntimeBridge(
    private val transport: NativeRuntimeTransport = JniNativeRuntimeTransport,
    private val renderHostAdapter: RenderHostAdapter = NativeRenderHostAdapter(transport),
) : RuntimeBridge {
    // Serialize access to activeSessionHandle and avoid races between connect/refresh/apply.
    private val stateLock = Any()
    @Volatile
    private var activeSessionHandle: Long = 0L

    // Creates the native session and starts the periodic snapshot refresh loop.
    // Emitted signals are boundary-only; all rendering state remains host-owned.
    override fun connect(): Flow<RuntimeSignal> = callbackFlow {
        val loadOutcome = transport.ensureLibraryLoaded()
        if (loadOutcome is NativeLibraryLoadOutcome.Failure) {
            trySend(RuntimeSignal.Unavailable(loadOutcome.reason))
            close()
            return@callbackFlow
        }

        trySend(
            RuntimeSignal.Notice(
                message = "Native runtime library loaded",
                level = RuntimeNoticeLevel.Success,
            )
        )

        val createResult = runCatching {
            transport.createSession(
                scenarioId = DEFAULT_SCENARIO_ID,
                rootBranchId = DEFAULT_ROOT_BRANCH_ID,
            )
        }.getOrElse { error ->
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime session adapter is unavailable",
                    detail = error.message ?: error::class.java.simpleName
                )
            )
            close()
            return@callbackFlow
        }

        if (!createResult.result.isOk()) {
            if (createResult.handle != 0L) {
                transport.destroySession(createResult.handle)
            }
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime session create failed",
                    detail = "${createResult.result.describe()} (${createResult.result.context})"
                )
            )
            close()
            return@callbackFlow
        }

        val handle = createResult.handle
        if (handle == 0L) {
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime returned an empty session handle",
                    detail = "This indicates the JNI adapter did not provide a valid `SlRuntimeHandle`."
                )
            )
            close()
            return@callbackFlow
        }

        if (createResult.abiVersion != ABI_VERSION) {
            transport.destroySession(handle)
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime ABI mismatch",
                    detail = "expected=$ABI_VERSION, native=${createResult.abiVersion}"
                )
            )
            close()
            return@callbackFlow
        }

        synchronized(stateLock) {
            activeSessionHandle = handle
            renderHostAdapter.bindSession(handle)
        }

        trySend(RuntimeSignal.Connected(handle = handle))

        val runtimeInfoResult = runCatching {
            transport.runtimeInfo(handle)
        }.getOrElse { error ->
            trySend(
                RuntimeSignal.Notice(
                    message = "Runtime info unavailable: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
            awaitClose {
                releaseActiveSession(handle)
            }
            return@callbackFlow
        }

        if (runtimeInfoResult.result.isOk()) {
            trySend(
                RuntimeSignal.RuntimeInfoAvailable(
                    cpuBackendLabel = runtimeInfoResult.cpuBackendLabel(),
                    gpuBackendLabel = runtimeInfoResult.gpuBackendLabel(),
                )
            )
        } else {
            trySend(
                RuntimeSignal.Notice(
                    message = "Runtime info unavailable: ${runtimeInfoResult.result.describe()}",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
        }

        val initialSignals = refreshSignalsForHandle(handle, includeSummary = true)
        initialSignals.forEach { trySend(it) }

        if (extractBodyCountFrom(initialSignals) == 0L) {
            ensureStartupSeedApplied(handle).forEach { trySend(it) }
            refreshSignalsForHandle(handle, includeSummary = true).forEach { trySend(it) }
        }

        val refreshJob = launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val activeHandle = synchronized(stateLock) { activeSessionHandle }
                if (activeHandle == 0L) {
                    continue
                }
                refreshSignalsForHandle(activeHandle, includeSummary = true).forEach { trySend(it) }
            }
        }

        awaitClose {
            refreshJob.cancel()
            releaseActiveSession(handle)
        }
    }

    // Explicit pull refresh for currently bound session; reuses handle snapshot guard.
    override suspend fun refresh(): List<RuntimeSignal> {
        val handle = synchronized(stateLock) { activeSessionHandle }
        if (handle == 0L) {
            return listOf(
                RuntimeSignal.Notice(
                    message = "Refresh skipped: no active runtime session",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
        }

        return refreshSignalsForHandle(handle, includeSummary = true)
    }

    // Dispatches UI command into native runtime and returns resulting status + snapshot signals.
    override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> {
        val handle = synchronized(stateLock) { activeSessionHandle }
        if (handle == 0L) {
            return listOf(
                RuntimeSignal.Notice(
                    message = "Command skipped: no active runtime session",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
        }

        val commandResult = runCatching {
            transport.applyCommand(handle, command.toNativePayload())
        }.getOrElse { error ->
            return listOf(
                RuntimeSignal.Notice(
                    message = "Command failed: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Error,
                )
            )
        }

        if (!commandResult.result.isOk()) {
            return listOf(
                RuntimeSignal.Notice(
                    message = "Command failed: ${commandResult.result.describe()}",
                    level = RuntimeNoticeLevel.Error,
                )
            )
        }

        val signals = mutableListOf<RuntimeSignal>()
        signals += RuntimeSignal.CommandApplied(
            command = command,
            commandLabel = command.label,
            summary = commandResult,
        )
        signals += refreshSignalsForHandle(handle, includeSummary = false)
        return signals
    }

    // Collects one snapshot refresh bundle for one handle:
    // optional world-state summary plus render packet lease.
    private fun refreshSignalsForHandle(handle: Long, includeSummary: Boolean): List<RuntimeSignal> {
        val signals = mutableListOf<RuntimeSignal>()

        if (includeSummary) {
            val summary = runCatching {
                transport.refreshSession(handle)
            }.getOrElse { error ->
                signals += RuntimeSignal.Notice(
                    message = "Refresh unavailable: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Error,
                )
                return signals
            }

            if (summary.result.isOk()) {
                signals += RuntimeSignal.SnapshotUpdated(summary)
            } else {
                signals += RuntimeSignal.Notice(
                    message = "Refresh failed: ${summary.result.describe()}",
                    level = RuntimeNoticeLevel.Error,
                )
            }
        }

        synchronized(stateLock) {
            if (activeSessionHandle != handle) {
                return signals
            }
            // Render packets are refreshed only for the current active handle; stale handle
            // refresh is intentionally dropped to avoid cross-session packet aliasing.
            val refreshResult = runCatching {
                renderHostAdapter.refreshPacket()
            }.getOrElse { error ->
                signals += RuntimeSignal.RenderUnavailable(
                    reason = "Render export unavailable: ${error.message ?: error::class.java.simpleName}"
                )
                return signals
            }
            if (refreshResult.lease != null) {
                signals += RuntimeSignal.RenderPacketReady(refreshResult.lease)
            } else {
                signals += RuntimeSignal.RenderUnavailable(
                    reason = refreshResult.unavailableReason ?: "Render export unavailable"
                )
            }
        }

        return signals
    }

    private fun releaseActiveSession(expectedHandle: Long) {
        synchronized(stateLock) {
            if (activeSessionHandle != expectedHandle) {
                return
            }
            // Session teardown order is host-defined:
            // lease -> native transport release -> zero active handle.
            // Packet-backed ByteBuffer views are only valid while the native packet handle is alive.
            // Release packet leases before tearing down the owning runtime session.
            renderHostAdapter.releasePacket()
            transport.destroySession(expectedHandle)
            activeSessionHandle = 0L
        }
    }

    private fun extractBodyCountFrom(signals: List<RuntimeSignal>): Long {
        return signals
            .asSequence()
            .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
            .firstOrNull()
            ?.summary
            ?.bodyCount
            ?.toLong()
            ?: 0L
    }

    private fun ensureStartupSeedApplied(handle: Long): List<RuntimeSignal> {
        val signals = mutableListOf<RuntimeSignal>()
        val seedCommands = startupSeedCommands()
        for (command in seedCommands) {
            val commandResult = runCatching {
                transport.applyCommand(handle, command.toNativePayload())
            }.getOrElse { error ->
                signals += RuntimeSignal.Notice(
                    message = "Startup bootstrap command failed: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Error,
                )
                return signals
            }

            if (!commandResult.result.isOk()) {
                signals += RuntimeSignal.Notice(
                    message = "Startup bootstrap command rejected: ${commandResult.result.describe()}",
                    level = RuntimeNoticeLevel.Warning,
                )
                return signals
            }
        }

        if (signals.isEmpty()) {
            signals += RuntimeSignal.Notice(
                message = "Seeded default startup solar system (${seedCommands.size} bodies; " +
                    "${STARTUP_CURATED_SMALL_BODY_COUNT} curated small bodies, " +
                    "${STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT} belt tracers, " +
                    "${STARTUP_SYNTHETIC_OORT_CLOUD_COUNT} Oort tracers) for session $handle",
                level = RuntimeNoticeLevel.Info,
            )
        }

        return signals
    }

    private fun startupSeedCommands(): List<RuntimeCommand> {
        val sun = RuntimeCommand.SpawnBody(
            bodyId = "sun",
            bodyClass = RuntimeBodyClass.Star,
            positionX = 0.0,
            positionY = 0.0,
            positionZ = 0.0,
            velocityX = 0.0,
            velocityY = 0.0,
            velocityZ = 0.0,
            massKg = 1.988_47e30,
            radiusM = 6.9634e8,
        )
        val mercury = RuntimeCommand.SpawnBody(
            bodyId = "mercury",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = -1.946_172_635_585_372e10,
            positionY = -5.992_796_777_348_039e10,
            positionZ = -2.999_277_267_983_142e10,
            velocityX = 3.699_499_185_727_919e4,
            velocityY = -8_529.675_283_382_268,
            velocityZ = -8_393.121_143_467_224,
            massKg = 3.3011e23,
            radiusM = 2.4397e6,
        )
        val venus = RuntimeCommand.SpawnBody(
            bodyId = "venus",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = -1.074_564_940_521_906e11,
            positionY = -6.922_528_774_882_654e9,
            positionZ = 3.686_187_045_620_657e9,
            velocityX = 1_381.906_029_263_447,
            velocityY = -32_017.818_431_682_73,
            velocityZ = -14_491.835_473_268_0,
            massKg = 4.8675e24,
            radiusM = 6.0518e6,
        )
        val earth = RuntimeCommand.SpawnBody(
            bodyId = "earth",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = -2.649_903_367_743_05e10,
            positionY = 1.327_574_173_383_451e11,
            positionZ = 5.755_671_847_054_072e10,
            velocityX = -2.979_426_007_043_741e4,
            velocityY = -5_018.052_308_799_903,
            velocityZ = -2_175.393_802_830_554,
            massKg = 5.97237e24,
            radiusM = 6.3710e6,
        )
        val moon = moonStartupCommand(earth)
        val mars = RuntimeCommand.SpawnBody(
            bodyId = "mars",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = 2.080_481_406_418_42e11,
            positionY = 2.096_191_735_388_105e8,
            positionZ = -5.529_162_313_155_326e9,
            velocityX = 1_162.672_403_766_088,
            velocityY = 23_918.409_699_116_61,
            velocityZ = 10_939.171_916_766_48,
            massKg = 6.4171e23,
            radiusM = 3.3895e6,
        )
        val jupiter = RuntimeCommand.SpawnBody(
            bodyId = "jupiter",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = 5.985_676_246_570_645e11,
            positionY = 4.093_863_059_841_62e11,
            positionZ = 1.608_943_268_775_687e11,
            velocityX = -7_909.860_292_172_008,
            velocityY = 10_183.574_082_354_88,
            velocityZ = 4_557.755_393_988_428,
            massKg = 1.8982e27,
            radiusM = 6.9911e7,
        )
        val saturn = RuntimeCommand.SpawnBody(
            bodyId = "saturn",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = 9.583_853_589_157_217e11,
            positionY = 9.237_154_712_422_728e11,
            positionZ = 3.403_008_584_583_76e11,
            velocityX = -7_431.212_958_764_64,
            velocityY = 6_110.152_327_010_504,
            velocityZ = 2_842.799_239_481_524,
            massKg = 5.6834e26,
            radiusM = 5.8232e7,
        )
        val uranus = RuntimeCommand.SpawnBody(
            bodyId = "uranus",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = 2.158_974_819_528_798e12,
            positionY = -1.870_911_063_386_387e12,
            positionZ = -8.499_688_608_118_601e11,
            velocityX = 4_637.272_105_685_132,
            velocityY = 4_262.811_704_355_634,
            velocityZ = 1_801.372_818_270_055,
            massKg = 8.6810e25,
            radiusM = 2.5362e7,
        )
        val neptune = RuntimeCommand.SpawnBody(
            bodyId = "neptune",
            bodyClass = RuntimeBodyClass.Planet,
            positionX = 2.515_046_471_487_719e12,
            positionY = -3.437_774_106_197_624e12,
            positionZ = -1.469_713_518_152_847e12,
            velocityX = 4_465.275_177_950_522,
            velocityY = 2_888.286_551_585_958,
            velocityZ = 1_071.024_500_381_687,
            massKg = 1.02413e26,
            radiusM = 2.4622e7,
        )
        val pluto = RuntimeCommand.SpawnBody(
            bodyId = "pluto",
            bodyClass = RuntimeBodyClass.DwarfPlanet,
            positionX = -1.477_330_922_306_794e12,
            positionY = -4.185_578_139_004_337e12,
            positionZ = -8.607_382_312_063_003e11,
            velocityX = 5_259.850_276_851_352,
            velocityY = -1_939.761_452_556_408,
            velocityZ = -2_204.049_388_416_424,
            massKg = 1.303e22,
            radiusM = 1.1883e6,
        )
        val haumea = spawnOrbitingBodyAroundPrimary(
            bodyId = "haumea",
            bodyClass = RuntimeBodyClass.DwarfPlanet,
            primary = sun,
            massKg = 4.006e21,
            radiusM = 7.16e5,
            elements = startupOrbitalElements(
                semiMajorAxisAu = 43.13,
                eccentricity = 0.191,
                inclinationDeg = 28.19,
                ascendingNodeDeg = 122.0,
                periapsisDeg = 240.0,
                trueAnomalyDeg = 80.0,
            ),
        )
        val makemake = spawnOrbitingBodyAroundPrimary(
            bodyId = "makemake",
            bodyClass = RuntimeBodyClass.DwarfPlanet,
            primary = sun,
            massKg = 3.1e21,
            radiusM = 7.15e5,
            elements = startupOrbitalElements(
                semiMajorAxisAu = 45.79,
                eccentricity = 0.159,
                inclinationDeg = 28.96,
                ascendingNodeDeg = 79.6,
                periapsisDeg = 294.0,
                trueAnomalyDeg = 170.0,
            ),
        )
        val eris = spawnOrbitingBodyAroundPrimary(
            bodyId = "eris",
            bodyClass = RuntimeBodyClass.DwarfPlanet,
            primary = sun,
            massKg = 1.6466e22,
            radiusM = 1.163e6,
            elements = startupOrbitalElements(
                semiMajorAxisAu = 67.78,
                eccentricity = 0.44,
                inclinationDeg = 44.04,
                ascendingNodeDeg = 35.95,
                periapsisDeg = 151.4,
                trueAnomalyDeg = 260.0,
            ),
        )
        val ceres = RuntimeCommand.SpawnBody(
            bodyId = "ceres",
            bodyClass = RuntimeBodyClass.DwarfPlanet,
            positionX = -3.559_423_585_024_965e11,
            positionY = 8.163_123_942_918_420e10,
            positionZ = 1.108_857_536_222_865e11,
            velocityX = -6_205.936_548_273_125,
            velocityY = -17_046.568_817_332_89,
            velocityZ = -6_760.549_102_192_67,
            massKg = 9.3835e20,
            radiusM = 4.731e5,
        )
        val curatedSmallBodies = startupCuratedSmallBodyCommands(primary = sun)
        val syntheticAsteroidBelt = syntheticAsteroidBeltCommands(primary = sun)
        val syntheticOortCloud = syntheticOortCloudCommands(primary = sun)

        return buildList {
            add(sun)
            add(mercury)
            add(venus)
            add(earth)
            add(moon)
            add(mars)
            add(jupiter)
            add(saturn)
            add(uranus)
            add(neptune)
            add(pluto)
            add(haumea)
            add(makemake)
            add(eris)
            add(ceres)
            addAll(curatedSmallBodies)
            addAll(syntheticAsteroidBelt)
            addAll(syntheticOortCloud)
        }
    }

    private fun moonStartupCommand(earth: RuntimeCommand.SpawnBody): RuntimeCommand.SpawnBody {
        // Keep the Android bootstrap seed coherent with the legacy starter-moon propagation
        // until the Rust runtime owns a full cartesian seed import for host-relative moons.
        val moonState = stateVectorAroundPrimaryAtEpoch(
            primaryMassKg = earth.massKg,
            bodyMassKg = STARTUP_MOON_MASS_KG,
            orbit = StartupOrbitAtEpoch(
                epochJdTdb = STARTUP_SEED_JULIAN_DATE_TDB,
                semiMajorAxisM = 3.844e8,
                eccentricity = 0.0549,
                inclinationRad = 5.145.degreesToRadians(),
                longitudeOfAscendingNodeRad = 125.08.degreesToRadians(),
                argumentOfPeriapsisRad = 318.15.degreesToRadians(),
                meanAnomalyAtEpochRad = 135.27.degreesToRadians(),
            ),
            targetJulianDateTdb = STARTUP_SEED_JULIAN_DATE_TDB,
            gravitationalConstant = STARTUP_GRAVITATIONAL_CONSTANT_M3_PER_KG_S2,
        )

        return RuntimeCommand.SpawnBody(
            bodyId = "moon",
            bodyClass = RuntimeBodyClass.Moon,
            positionX = earth.positionX + moonState.positionX,
            positionY = earth.positionY + moonState.positionY,
            positionZ = earth.positionZ + moonState.positionZ,
            velocityX = earth.velocityX + moonState.velocityX,
            velocityY = earth.velocityY + moonState.velocityY,
            velocityZ = earth.velocityZ + moonState.velocityZ,
            massKg = STARTUP_MOON_MASS_KG,
            radiusM = STARTUP_MOON_RADIUS_M,
        )
    }

    private fun startupCuratedSmallBodyCommands(
        primary: RuntimeCommand.SpawnBody,
    ): List<RuntimeCommand.SpawnBody> = listOf(
        spawnOrbitingBodyAroundPrimary(
            bodyId = "vesta",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 2.59076e20,
            radiusM = 2.626e5,
            elements = startupOrbitalElements(2.361, 0.089, 7.14, 103.8, 150.9, 40.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "pallas",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 2.14e20,
            radiusM = 2.56e5,
            elements = startupOrbitalElements(2.773, 0.231, 34.84, 173.1, 310.2, 220.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "hygiea",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 8.32e19,
            radiusM = 2.17e5,
            elements = startupOrbitalElements(3.141, 0.117, 3.83, 283.2, 313.4, 120.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "psyche",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 2.3e19,
            radiusM = 1.13e5,
            elements = startupOrbitalElements(2.923, 0.140, 3.10, 150.0, 228.0, 280.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "eros",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 6.687e15,
            radiusM = 8_420.0,
            elements = startupOrbitalElements(1.458, 0.223, 10.83, 304.4, 178.7, 60.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "bennu",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 7.329e10,
            radiusM = 245.0,
            elements = startupOrbitalElements(1.1264, 0.2037, 6.03, 2.06, 66.22, 300.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "ryugu",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 4.5e11,
            radiusM = 448.0,
            elements = startupOrbitalElements(1.1896, 0.1902, 5.88, 251.45, 211.61, 170.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "itokawa",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 3.51e10,
            radiusM = 165.0,
            elements = startupOrbitalElements(1.324, 0.280, 1.62, 69.1, 162.8, 25.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "apophis",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 6.1e10,
            radiusM = 185.0,
            elements = startupOrbitalElements(0.9224, 0.1912, 3.34, 204.4, 126.4, 320.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "didymos",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 5.24e11,
            radiusM = 390.0,
            elements = startupOrbitalElements(1.644, 0.384, 3.41, 73.2, 319.6, 80.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "halley",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 2.2e14,
            radiusM = 5_500.0,
            elements = startupOrbitalElements(17.834, 0.967, 162.26, 58.42, 111.33, 38.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "encke",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 3.5e13,
            radiusM = 2_400.0,
            elements = startupOrbitalElements(2.215, 0.850, 11.78, 334.6, 186.5, 140.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "churyumov-gerasimenko",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 9.98e12,
            radiusM = 2_000.0,
            elements = startupOrbitalElements(3.463, 0.641, 7.04, 50.17, 12.78, 90.0),
        ),
        spawnOrbitingBodyAroundPrimary(
            bodyId = "wild-2",
            bodyClass = RuntimeBodyClass.SmallBody,
            primary = primary,
            massKg = 2.3e13,
            radiusM = 2_000.0,
            elements = startupOrbitalElements(3.447, 0.538, 3.24, 136.1, 41.0, 260.0),
        ),
    )

    private fun syntheticAsteroidBeltCommands(
        primary: RuntimeCommand.SpawnBody,
        count: Int = STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT,
        seed: Long = STARTUP_SYNTHETIC_ASTEROID_BELT_SEED,
    ): List<RuntimeCommand.SpawnBody> {
        val random = Random(seed)
        return List(count) { index ->
            spawnOrbitingBodyAroundPrimary(
                bodyId = "belt-$index",
                bodyClass = RuntimeBodyClass.Tracer,
                primary = primary,
                massKg = 0.0,
                radiusM = random.nextDouble(500.0, 50_000.0),
                elements = startupOrbitalElements(
                    semiMajorAxisAu = random.nextDouble(2.1, 3.3),
                    eccentricity = random.nextDouble(0.0, 0.18),
                    inclinationDeg = random.nextDouble(0.0, 18.0),
                    ascendingNodeDeg = random.nextDouble(0.0, 360.0),
                    periapsisDeg = random.nextDouble(0.0, 360.0),
                    trueAnomalyDeg = random.nextDouble(0.0, 360.0),
                ),
            )
        }
    }

    private fun syntheticOortCloudCommands(
        primary: RuntimeCommand.SpawnBody,
        count: Int = STARTUP_SYNTHETIC_OORT_CLOUD_COUNT,
        seed: Long = STARTUP_SYNTHETIC_OORT_CLOUD_SEED,
    ): List<RuntimeCommand.SpawnBody> {
        val random = Random(seed)
        return List(count) { index ->
            val logSemiMajorAxisAu = random.nextDouble(3.3, 5.0)
            val semiMajorAxisAu = 10.0.pow(logSemiMajorAxisAu)
            val inclinationDeg = Math.toDegrees(acos(random.nextDouble(-1.0, 1.0)))
            spawnOrbitingBodyAroundPrimary(
                bodyId = "oort-$index",
                bodyClass = RuntimeBodyClass.Tracer,
                primary = primary,
                massKg = 0.0,
                radiusM = random.nextDouble(1_000.0, 20_000.0),
                elements = startupOrbitalElements(
                    semiMajorAxisAu = semiMajorAxisAu,
                    eccentricity = random.nextDouble(0.85, 0.999),
                    inclinationDeg = inclinationDeg,
                    ascendingNodeDeg = random.nextDouble(0.0, 360.0),
                    periapsisDeg = random.nextDouble(0.0, 360.0),
                    trueAnomalyDeg = random.nextDouble(0.0, 360.0),
                ),
            )
        }
    }

    private fun spawnOrbitingBodyAroundPrimary(
        bodyId: String,
        bodyClass: RuntimeBodyClass,
        primary: RuntimeCommand.SpawnBody,
        massKg: Double,
        radiusM: Double,
        elements: StartupOrbitalElements,
    ): RuntimeCommand.SpawnBody {
        val state = stateVectorAroundPrimary(
            primaryMassKg = primary.massKg,
            bodyMassKg = massKg,
            elements = elements,
            gravitationalConstant = STARTUP_GRAVITATIONAL_CONSTANT_M3_PER_KG_S2,
        )

        return RuntimeCommand.SpawnBody(
            bodyId = bodyId,
            bodyClass = bodyClass,
            positionX = primary.positionX + state.positionX,
            positionY = primary.positionY + state.positionY,
            positionZ = primary.positionZ + state.positionZ,
            velocityX = primary.velocityX + state.velocityX,
            velocityY = primary.velocityY + state.velocityY,
            velocityZ = primary.velocityZ + state.velocityZ,
            massKg = massKg,
            radiusM = radiusM,
        )
    }

    private fun startupOrbitalElements(
        semiMajorAxisAu: Double,
        eccentricity: Double,
        inclinationDeg: Double,
        ascendingNodeDeg: Double,
        periapsisDeg: Double,
        trueAnomalyDeg: Double,
    ): StartupOrbitalElements = StartupOrbitalElements(
        semiMajorAxisM = semiMajorAxisAu * STARTUP_ASTRONOMICAL_UNIT_M,
        eccentricity = eccentricity,
        inclinationRad = inclinationDeg.degreesToRadians(),
        longitudeOfAscendingNodeRad = ascendingNodeDeg.degreesToRadians(),
        argumentOfPeriapsisRad = periapsisDeg.degreesToRadians(),
        trueAnomalyRad = trueAnomalyDeg.degreesToRadians(),
    )

    private fun stateVectorAroundPrimaryAtEpoch(
        primaryMassKg: Double,
        bodyMassKg: Double,
        orbit: StartupOrbitAtEpoch,
        targetJulianDateTdb: Double,
        gravitationalConstant: Double,
    ): StartupStateVector {
        val mu = gravitationalConstant * (primaryMassKg + bodyMassKg)
        val meanMotionRadPerSecond = sqrt(mu / (orbit.semiMajorAxisM * orbit.semiMajorAxisM * orbit.semiMajorAxisM))
        val deltaSeconds = (targetJulianDateTdb - orbit.epochJdTdb) * STARTUP_DAY_SECONDS
        val meanAnomaly = normalizeRadians(orbit.meanAnomalyAtEpochRad + (meanMotionRadPerSecond * deltaSeconds))
        val eccentricAnomaly = solveKeplerEquation(meanAnomalyRad = meanAnomaly, eccentricity = orbit.eccentricity)
        val trueAnomaly = 2.0 * atan2(
            sqrt(1.0 + orbit.eccentricity) * sin(eccentricAnomaly / 2.0),
            sqrt(1.0 - orbit.eccentricity) * cos(eccentricAnomaly / 2.0),
        )

        return stateVectorAroundPrimary(
            primaryMassKg = primaryMassKg,
            bodyMassKg = bodyMassKg,
            elements = StartupOrbitalElements(
                semiMajorAxisM = orbit.semiMajorAxisM,
                eccentricity = orbit.eccentricity,
                inclinationRad = orbit.inclinationRad,
                longitudeOfAscendingNodeRad = orbit.longitudeOfAscendingNodeRad,
                argumentOfPeriapsisRad = orbit.argumentOfPeriapsisRad,
                trueAnomalyRad = normalizeRadians(trueAnomaly),
            ),
            gravitationalConstant = gravitationalConstant,
        )
    }

    private fun stateVectorAroundPrimary(
        primaryMassKg: Double,
        bodyMassKg: Double,
        elements: StartupOrbitalElements,
        gravitationalConstant: Double,
    ): StartupStateVector {
        val mu = gravitationalConstant * (primaryMassKg + bodyMassKg)
        val p = elements.semiMajorAxisM * (1.0 - elements.eccentricity * elements.eccentricity)
        val cosNu = cos(elements.trueAnomalyRad)
        val sinNu = sin(elements.trueAnomalyRad)
        val radius = p / (1.0 + elements.eccentricity * cosNu)
        val speedFactor = sqrt(mu / p)

        val rotation = StartupRotation.from(elements)

        return StartupStateVector(
            positionX = rotation.transformX(radius * cosNu, radius * sinNu),
            positionY = rotation.transformY(radius * cosNu, radius * sinNu),
            positionZ = rotation.transformZ(radius * cosNu, radius * sinNu),
            velocityX = rotation.transformX(-speedFactor * sinNu, speedFactor * (elements.eccentricity + cosNu)),
            velocityY = rotation.transformY(-speedFactor * sinNu, speedFactor * (elements.eccentricity + cosNu)),
            velocityZ = rotation.transformZ(-speedFactor * sinNu, speedFactor * (elements.eccentricity + cosNu)),
        )
    }

    private fun solveKeplerEquation(
        meanAnomalyRad: Double,
        eccentricity: Double,
    ): Double {
        var eccentricAnomaly = if (eccentricity < 0.8) meanAnomalyRad else PI

        repeat(24) {
            val functionValue = eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - meanAnomalyRad
            val derivative = 1.0 - eccentricity * cos(eccentricAnomaly)
            val delta = functionValue / derivative
            eccentricAnomaly -= delta
            if (abs(delta) <= 1e-14) {
                return eccentricAnomaly
            }
        }

        return eccentricAnomaly
    }

    private fun normalizeRadians(angle: Double): Double {
        val wrapped = (angle + PI) % (2.0 * PI)
        return if (wrapped < 0.0) wrapped + PI else wrapped - PI
    }

    private fun Double.degreesToRadians(): Double = this * PI / 180.0

    private data class StartupOrbitAtEpoch(
        val epochJdTdb: Double,
        val semiMajorAxisM: Double,
        val eccentricity: Double,
        val inclinationRad: Double,
        val longitudeOfAscendingNodeRad: Double,
        val argumentOfPeriapsisRad: Double,
        val meanAnomalyAtEpochRad: Double,
    )

    private data class StartupOrbitalElements(
        val semiMajorAxisM: Double,
        val eccentricity: Double,
        val inclinationRad: Double,
        val longitudeOfAscendingNodeRad: Double,
        val argumentOfPeriapsisRad: Double,
        val trueAnomalyRad: Double,
    )

    private data class StartupStateVector(
        val positionX: Double,
        val positionY: Double,
        val positionZ: Double,
        val velocityX: Double,
        val velocityY: Double,
        val velocityZ: Double,
    )

    private data class StartupRotation(
        val rotation11: Double,
        val rotation12: Double,
        val rotation21: Double,
        val rotation22: Double,
        val rotation31: Double,
        val rotation32: Double,
    ) {
        fun transformX(x: Double, y: Double): Double = rotation11 * x + rotation12 * y
        fun transformY(x: Double, y: Double): Double = rotation21 * x + rotation22 * y
        fun transformZ(x: Double, y: Double): Double = rotation31 * x + rotation32 * y

        companion object {
            fun from(elements: StartupOrbitalElements): StartupRotation {
                val cosOmega = cos(elements.longitudeOfAscendingNodeRad)
                val sinOmega = sin(elements.longitudeOfAscendingNodeRad)
                val cosI = cos(elements.inclinationRad)
                val sinI = sin(elements.inclinationRad)
                val cosW = cos(elements.argumentOfPeriapsisRad)
                val sinW = sin(elements.argumentOfPeriapsisRad)

                return StartupRotation(
                    rotation11 = cosOmega * cosW - sinOmega * sinW * cosI,
                    rotation12 = -cosOmega * sinW - sinOmega * cosW * cosI,
                    rotation21 = sinOmega * cosW + cosOmega * sinW * cosI,
                    rotation22 = -sinOmega * sinW + cosOmega * cosW * cosI,
                    rotation31 = sinW * sinI,
                    rotation32 = cosW * sinI,
                )
            }
        }
    }

    private companion object {
        private const val ABI_VERSION = 2
        private const val DEFAULT_SCENARIO_ID = "sol-system"
        private const val DEFAULT_ROOT_BRANCH_ID = "main"
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val STARTUP_ASTRONOMICAL_UNIT_M = 1.495_978_707e11
        private const val STARTUP_SEED_JULIAN_DATE_TDB = 2_451_545.0
        private const val STARTUP_DAY_SECONDS = 86_400.0
        private const val STARTUP_GRAVITATIONAL_CONSTANT_M3_PER_KG_S2 = 6.67430e-11
        private const val STARTUP_MOON_MASS_KG = 7.342e22
        private const val STARTUP_MOON_RADIUS_M = 1.7374e6
        private const val STARTUP_CURATED_SMALL_BODY_COUNT = 14
        private const val STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT = 240
        private const val STARTUP_SYNTHETIC_OORT_CLOUD_COUNT = 96
        private const val STARTUP_SYNTHETIC_ASTEROID_BELT_SEED = 42L
        private const val STARTUP_SYNTHETIC_OORT_CLOUD_SEED = 43L
    }
}

internal sealed interface RuntimeSignal {
    data class Connected(val handle: Long) : RuntimeSignal
    data class RuntimeInfoAvailable(
        val cpuBackendLabel: String,
        val gpuBackendLabel: String,
    ) : RuntimeSignal
    data class Notice(
        val message: String,
        val level: RuntimeNoticeLevel = RuntimeNoticeLevel.Info,
    ) : RuntimeSignal
    data class SnapshotUpdated(val summary: NativeSnapshotSummaryResult) : RuntimeSignal
    data class CommandApplied(
        val command: RuntimeCommand,
        val commandLabel: String,
        val summary: NativeSnapshotSummaryResult,
    ) : RuntimeSignal
    data class RenderPacketReady(val lease: PacketLease) : RuntimeSignal
    data class RenderUnavailable(val reason: String) : RuntimeSignal
    data class Unavailable(val message: String, val detail: String? = null) : RuntimeSignal
}

internal enum class RuntimeNoticeLevel {
    Info,
    Success,
    Warning,
    Error,
}

sealed interface RuntimeCommand {
    val label: String

    data class AdvanceEpoch(val deltaSeconds: Double) : RuntimeCommand {
        override val label: String = "timeline.advance_epoch"
    }

    data object PausePlayback : RuntimeCommand {
        override val label: String = "playback.pause"
    }

    data object ResumePlayback : RuntimeCommand {
        override val label: String = "playback.resume"
    }

    data class SetPlaybackRate(val simSecondsPerRealSecond: Double) : RuntimeCommand {
        override val label: String = "playback.set_rate"
    }

    data class SetObserverMode(val mode: RuntimeObserverMode) : RuntimeCommand {
        override val label: String = "observer.set_mode"
    }

    data class FocusBody(val bodyId: String?) : RuntimeCommand {
        override val label: String = "observer.focus_body"
    }

    data class SpawnBody(
        val bodyId: String,
        val bodyClass: RuntimeBodyClass = RuntimeBodyClass.Planet,
        val positionX: Double = 0.0,
        val positionY: Double = 0.0,
        val positionZ: Double = 0.0,
        val velocityX: Double = 0.0,
        val velocityY: Double = 0.0,
        val velocityZ: Double = 0.0,
        val massKg: Double,
        val radiusM: Double,
    ) : RuntimeCommand {
        override val label: String = "body.spawn"
    }

    data class RemoveBody(val bodyId: String) : RuntimeCommand {
        override val label: String = "body.remove"
    }

    data class SetBodyKinematics(
        val bodyId: String,
        val positionX: Double,
        val positionY: Double,
        val positionZ: Double,
        val velocityX: Double,
        val velocityY: Double,
        val velocityZ: Double,
    ) : RuntimeCommand {
        override val label: String = "body.set_kinematics"
    }

    data class CreateCheckpoint(
        val checkpointId: String? = null,
        val checkpointLabel: String? = null,
    ) : RuntimeCommand {
        override val label: String = "branching.create_checkpoint"
    }

    data class CreateBranchFromCheckpoint(
        val checkpointId: String,
        val newBranchId: String? = null,
    ) : RuntimeCommand {
        override val label: String = "branching.create_branch_from_checkpoint"
    }
}

enum class RuntimeBodyClass(val nativeCode: Int) {
    Star(0),
    Planet(1),
    DwarfPlanet(2),
    Moon(3),
    SmallBody(4),
    Tracer(5),
    Spacecraft(6),
    Custom(7),
}

enum class RuntimeObserverMode(val nativeCode: Int) {
    Free(0),
    FollowSelected(1),
    FollowHost(2),
    SystemFrame(3),
}

internal data class NativeRuntimeCommandPayload(
    val kind: Int,
    val bodyIdUtf8: ByteArray? = null,
    val bodyClass: Int = NATIVE_BODY_CLASS_PLANET,
    val bodyPositionX: Double = 0.0,
    val bodyPositionY: Double = 0.0,
    val bodyPositionZ: Double = 0.0,
    val bodyVelocityX: Double = 0.0,
    val bodyVelocityY: Double = 0.0,
    val bodyVelocityZ: Double = 0.0,
    val bodyMassKg: Double = 0.0,
    val bodyRadiusM: Double = 0.0,
    val checkpointIdUtf8: ByteArray? = null,
    val checkpointLabelUtf8: ByteArray? = null,
    val newBranchIdUtf8: ByteArray? = null,
    val observerMode: Int = RuntimeObserverMode.Free.nativeCode,
    val deltaSeconds: Double = 0.0,
    val simSecondsPerRealSecond: Double = 0.0,
    val recordedAtUnixMs: Long = System.currentTimeMillis(),
)

private fun RuntimeCommand.toNativePayload(): NativeRuntimeCommandPayload = when (this) {
    is RuntimeCommand.AdvanceEpoch -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_ADVANCE_EPOCH,
        deltaSeconds = deltaSeconds,
    )

    RuntimeCommand.PausePlayback -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_PAUSE_PLAYBACK,
    )

    RuntimeCommand.ResumePlayback -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_RESUME_PLAYBACK,
    )

    is RuntimeCommand.SetPlaybackRate -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SET_PLAYBACK_RATE,
        simSecondsPerRealSecond = simSecondsPerRealSecond,
    )

    is RuntimeCommand.SetObserverMode -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SET_OBSERVER_MODE,
        observerMode = mode.nativeCode,
    )

    is RuntimeCommand.FocusBody -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_FOCUS_BODY,
        bodyIdUtf8 = bodyId?.toByteArray(StandardCharsets.UTF_8),
    )

    is RuntimeCommand.SpawnBody -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SPAWN_BODY,
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
        bodyClass = bodyClass.nativeCode,
        bodyPositionX = positionX,
        bodyPositionY = positionY,
        bodyPositionZ = positionZ,
        bodyVelocityX = velocityX,
        bodyVelocityY = velocityY,
        bodyVelocityZ = velocityZ,
        bodyMassKg = massKg,
        bodyRadiusM = radiusM,
    )

    is RuntimeCommand.RemoveBody -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_REMOVE_BODY,
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
    )

    is RuntimeCommand.SetBodyKinematics -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SET_BODY_KINEMATICS,
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
        bodyPositionX = positionX,
        bodyPositionY = positionY,
        bodyPositionZ = positionZ,
        bodyVelocityX = velocityX,
        bodyVelocityY = velocityY,
        bodyVelocityZ = velocityZ,
    )

    is RuntimeCommand.CreateCheckpoint -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_CREATE_CHECKPOINT,
        checkpointIdUtf8 = checkpointId?.toByteArray(StandardCharsets.UTF_8),
        checkpointLabelUtf8 = checkpointLabel?.toByteArray(StandardCharsets.UTF_8),
    )

    is RuntimeCommand.CreateBranchFromCheckpoint -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_CREATE_BRANCH_FROM_CHECKPOINT,
        checkpointIdUtf8 = checkpointId.toByteArray(StandardCharsets.UTF_8),
        newBranchIdUtf8 = newBranchId?.toByteArray(StandardCharsets.UTF_8),
    )
}

internal interface NativeRuntimeTransport {
    fun ensureLibraryLoaded(): NativeLibraryLoadOutcome

    fun createSession(
        scenarioId: String,
        rootBranchId: String,
    ): NativeCreateSessionResult

    fun runtimeInfo(handle: Long): NativeRuntimeInfoResult

    fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult

    fun refreshSession(handle: Long): NativeSnapshotSummaryResult

    fun applyCommand(handle: Long, command: NativeRuntimeCommandPayload): NativeSnapshotSummaryResult

    fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult?

    fun releaseVulkanScene(packetHandle: Long)

    fun destroySession(handle: Long)
}

internal sealed interface NativeLibraryLoadOutcome {
    data object Success : NativeLibraryLoadOutcome
    data class Failure(val reason: String) : NativeLibraryLoadOutcome
}

internal const val NATIVE_TIMELINE_SEMANTICS_BRANCHED_SANDBOX = 1
internal const val NATIVE_CPU_BACKEND_SIMD_ARM64 = 1
internal const val NATIVE_GPU_BACKEND_NONE = 0
internal const val NATIVE_GPU_BACKEND_VULKAN = 1
internal const val NATIVE_GPU_BACKEND_METAL = 2
internal const val NATIVE_GPU_BACKEND_WEBGPU_CLASS = 3
internal const val NATIVE_GPU_BACKEND_OPENCL = 4

internal fun preferredGpuBackendCode(preferredBackendRaw: String): Int {
    val normalized = preferredBackendRaw.trim()
        .lowercase(Locale.US)
        .replace(Regex("\\s+"), "")
    return when (normalized) {
        "", "none" -> NATIVE_GPU_BACKEND_NONE
        "vulkan" -> NATIVE_GPU_BACKEND_VULKAN
        "metal" -> NATIVE_GPU_BACKEND_METAL
        "webgpu", "webgpu-class", "webgpu_class" -> NATIVE_GPU_BACKEND_WEBGPU_CLASS
        "vulkan+opencl", "opencl+vulkan", "vulkan,opencl", "opencl,vulkan" -> NATIVE_GPU_BACKEND_OPENCL
        "opencl", "open-cl", "open_cl" -> NATIVE_GPU_BACKEND_OPENCL
        else -> NATIVE_GPU_BACKEND_NONE
    }
}

internal object JniNativeRuntimeTransport : NativeRuntimeTransport {
    private const val LIBRARY_NAME = "solarlab_v2"

    @Volatile
    private var loadAttempted: Boolean = false

    @Volatile
    private var loadFailure: String? = null

    override fun ensureLibraryLoaded(): NativeLibraryLoadOutcome {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    val failure = runCatching { System.loadLibrary(LIBRARY_NAME) }
                        .exceptionOrNull()
                    loadFailure = failure?.let { throwable ->
                        val summary = throwable.message?.takeIf { it.isNotBlank() }
                            ?: throwable::class.java.simpleName
                        "Unable to load native library '$LIBRARY_NAME': $summary"
                    }
                    loadAttempted = true
                }
            }
        }

        return loadFailure?.let(NativeLibraryLoadOutcome::Failure) ?: NativeLibraryLoadOutcome.Success
    }

    override fun createSession(scenarioId: String, rootBranchId: String): NativeCreateSessionResult {
        val scenarioBytes = scenarioId.toByteArray(StandardCharsets.UTF_8)
        val branchBytes = rootBranchId.toByteArray(StandardCharsets.UTF_8)

        return nativeCreateSession(
            scenarioIdUtf8 = scenarioBytes,
            rootBranchIdUtf8 = branchBytes,
            createdAtUnixMs = System.currentTimeMillis(),
            timelineSemantics = NATIVE_TIMELINE_SEMANTICS_BRANCHED_SANDBOX,
            liveUpdatesEnabled = true,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = preferredGpuBackendCode(BuildConfig.PREFERRED_GPU_BACKEND),
        )
    }

    override fun runtimeInfo(handle: Long): NativeRuntimeInfoResult = nativeRuntimeInfo(handle)

    override fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult = nativeSnapshotSummary(handle)

    override fun refreshSession(handle: Long): NativeSnapshotSummaryResult = nativeRefreshSession(handle)

    override fun applyCommand(
        handle: Long,
        command: NativeRuntimeCommandPayload,
    ): NativeSnapshotSummaryResult = nativeApplyCommand(
        handle = handle,
        kind = command.kind,
        bodyIdUtf8 = command.bodyIdUtf8,
        bodyClass = command.bodyClass,
        bodyPositionX = command.bodyPositionX,
        bodyPositionY = command.bodyPositionY,
        bodyPositionZ = command.bodyPositionZ,
        bodyVelocityX = command.bodyVelocityX,
        bodyVelocityY = command.bodyVelocityY,
        bodyVelocityZ = command.bodyVelocityZ,
        bodyMassKg = command.bodyMassKg,
        bodyRadiusM = command.bodyRadiusM,
        checkpointIdUtf8 = command.checkpointIdUtf8,
        checkpointLabelUtf8 = command.checkpointLabelUtf8,
        newBranchIdUtf8 = command.newBranchIdUtf8,
        observerMode = command.observerMode,
        deltaSeconds = command.deltaSeconds,
        simSecondsPerRealSecond = command.simSecondsPerRealSecond,
        recordedAtUnixMs = command.recordedAtUnixMs,
    )

    override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult? =
        nativeExportVulkanScene(handle)

    override fun releaseVulkanScene(packetHandle: Long) {
        if (packetHandle == 0L) return
        runCatching {
            nativeReleaseVulkanScene(packetHandle)
        }
    }

    override fun destroySession(handle: Long) {
        if (handle == 0L) return
        runCatching {
            nativeDestroySession(handle)
        }
    }

    private external fun nativeCreateSession(
        scenarioIdUtf8: ByteArray,
        rootBranchIdUtf8: ByteArray,
        createdAtUnixMs: Long,
        timelineSemantics: Int,
        liveUpdatesEnabled: Boolean,
        cpuBackend: Int,
        gpuBackend: Int,
    ): NativeCreateSessionResult

    private external fun nativeDestroySession(handle: Long): NativeResult

    private external fun nativeRuntimeInfo(handle: Long): NativeRuntimeInfoResult

    private external fun nativeSnapshotSummary(handle: Long): NativeSnapshotSummaryResult

    private external fun nativeRefreshSession(handle: Long): NativeSnapshotSummaryResult

    private external fun nativeApplyCommand(
        handle: Long,
        kind: Int,
        bodyIdUtf8: ByteArray?,
        bodyClass: Int,
        bodyPositionX: Double,
        bodyPositionY: Double,
        bodyPositionZ: Double,
        bodyVelocityX: Double,
        bodyVelocityY: Double,
        bodyVelocityZ: Double,
        bodyMassKg: Double,
        bodyRadiusM: Double,
        checkpointIdUtf8: ByteArray?,
        checkpointLabelUtf8: ByteArray?,
        newBranchIdUtf8: ByteArray?,
        observerMode: Int,
        deltaSeconds: Double,
        simSecondsPerRealSecond: Double,
        recordedAtUnixMs: Long,
    ): NativeSnapshotSummaryResult

    private external fun nativeExportVulkanScene(handle: Long): NativeVulkanScenePacketResult?

    private external fun nativeReleaseVulkanScene(packetHandle: Long): NativeResult

}

internal data class NativeResult(
    val code: Int,
    val context: String = "no context"
) {
    fun isOk(): Boolean = code == NATIVE_STATUS_OK

    fun describe(): String = when (code) {
        NATIVE_STATUS_OK -> "ok"
        NATIVE_STATUS_INVALID_ARGUMENT -> "invalid argument"
        NATIVE_STATUS_NOT_READY -> "not ready"
        NATIVE_STATUS_INTERNAL_ERROR -> "internal error"
        else -> "unknown($code)"
    }

    private companion object {
        private const val NATIVE_STATUS_OK = 0
        private const val NATIVE_STATUS_INVALID_ARGUMENT = 1
        private const val NATIVE_STATUS_NOT_READY = 2
        private const val NATIVE_STATUS_INTERNAL_ERROR = 3
    }
}

internal data class NativeCreateSessionResult(
    val result: NativeResult,
    val handle: Long,
    val abiVersion: Int,
    val cpuBackend: Int,
    val gpuBackend: Int,
)

internal data class NativeRuntimeInfoResult(
    val result: NativeResult,
    val abiVersion: Int,
    val cpuBackend: Int,
    val gpuBackend: Int,
) {
    fun cpuBackendLabel(): String = when (cpuBackend) {
        0 -> "reference-scalar"
        1 -> "simd-arm64"
        2 -> "simd-x64"
        else -> "unknown($cpuBackend)"
    }

    fun gpuBackendLabel(): String = when (gpuBackend) {
        NATIVE_GPU_BACKEND_NONE -> "none"
        NATIVE_GPU_BACKEND_VULKAN -> "vulkan"
        NATIVE_GPU_BACKEND_METAL -> "metal"
        NATIVE_GPU_BACKEND_WEBGPU_CLASS -> "webgpu-class"
        NATIVE_GPU_BACKEND_OPENCL -> "opencl"
        else -> "unknown($gpuBackend)"
    }
}

internal data class NativeSnapshotSummaryResult(
    val result: NativeResult,
    val scenarioId: String,
    val activeBranchId: String,
    val bodyCount: Int,
    val epochSeconds: Double,
    val paused: Boolean,
    val simSecondsPerRealSecond: Double,
    val observerMode: Int,
    val timelineSemantics: Int,
)

internal data class NativeVulkanScenePacketResult(
    val result: NativeResult,
    val packet: NativeVulkanScenePacket? = null,
)

internal data class NativeVulkanCameraPacket(
    val frameOriginX: Double,
    val frameOriginY: Double,
    val frameOriginZ: Double,
    val positionFromOriginX: Float,
    val positionFromOriginY: Float,
    val positionFromOriginZ: Float,
    val targetFromOriginX: Float,
    val targetFromOriginY: Float,
    val targetFromOriginZ: Float,
    val upX: Float,
    val upY: Float,
    val upZ: Float,
    val verticalFovDegrees: Float,
    val exposure: Float,
)

internal data class NativeRenderDiagnostics(
    val frameNumber: Long,
    val cpuExtractMs: Float,
    val gpuUploadMs: Float,
    val droppedFrames: Int,
)

internal data class NativeVulkanScenePacket(
    val packetHandle: Long,
    val sceneRevision: String,
    val epochSeconds: Double,
    val observerMode: Int,
    val timelineSemantics: Int,
    val camera: NativeVulkanCameraPacket,
    val bodyCount: Int,
    val tracerCount: Int,
    val trailSpanCount: Int,
    val trailVertexCount: Int,
    val directionalLightCount: Int,
    val diagnostics: NativeRenderDiagnostics,
    val provenanceSource: String?,
    val provenanceVersion: String?,
    val provenanceManifestId: String?,
    val provenanceManifestDigest: String?,
    val provenancePackageDigest: String?,
    val bodyInstances: ByteBuffer?,
    val tracerInstances: ByteBuffer?,
    val trailSpans: ByteBuffer?,
    val trailVertices: ByteBuffer?,
    val directionalLights: ByteBuffer?,
) {
    fun summaryLine(): String {
        val uploadBytes = listOf(
            bodyInstances?.capacity() ?: 0,
            tracerInstances?.capacity() ?: 0,
            trailSpans?.capacity() ?: 0,
            trailVertices?.capacity() ?: 0,
            directionalLights?.capacity() ?: 0,
        ).sum()
        return "bodies=$bodyCount, tracers=$tracerCount, trails=$trailSpanCount/$trailVertexCount, lights=$directionalLightCount, uploadBytes=$uploadBytes"
    }
}

private const val NATIVE_COMMAND_ADVANCE_EPOCH = 0
private const val NATIVE_COMMAND_PAUSE_PLAYBACK = 1
private const val NATIVE_COMMAND_RESUME_PLAYBACK = 2
private const val NATIVE_COMMAND_SET_PLAYBACK_RATE = 3
private const val NATIVE_COMMAND_SET_OBSERVER_MODE = 4
private const val NATIVE_COMMAND_FOCUS_BODY = 5
private const val NATIVE_COMMAND_SPAWN_BODY = 6
private const val NATIVE_COMMAND_REMOVE_BODY = 7
private const val NATIVE_COMMAND_SET_BODY_KINEMATICS = 8
private const val NATIVE_COMMAND_CREATE_CHECKPOINT = 9
private const val NATIVE_COMMAND_CREATE_BRANCH_FROM_CHECKPOINT = 10
private const val NATIVE_BODY_CLASS_STAR = 0
private const val NATIVE_BODY_CLASS_PLANET = 1
private const val NATIVE_BODY_CLASS_DWARF_PLANET = 2
private const val NATIVE_BODY_CLASS_MOON = 3
private const val NATIVE_BODY_CLASS_SMALL_BODY = 4
private const val NATIVE_BODY_CLASS_TRACER = 5
private const val NATIVE_BODY_CLASS_SPACECRAFT = 6
private const val NATIVE_BODY_CLASS_CUSTOM = 7
