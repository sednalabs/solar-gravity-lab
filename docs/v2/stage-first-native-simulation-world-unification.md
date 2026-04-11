# Step 7: NativeSimulationWorld unification

## Goal

Finish the architectural move that steps 5 and 6 set up:

- Kotlin becomes the shell / control plane
- Native becomes the authoritative simulation data plane
- Sandbox and Runtime stop being split-brain world owners

## Target outcome

Both Sandbox and Runtime should talk to the same long-lived native world handle.
Kotlin should keep HUD, forms, sheets, search UX, lifecycle and Android integration.
The native world should own:

- active body arrays / authoritative world state
- stepping / substep planning
- command application
- checkpoints / rewind
- diagnostics
- timeline stepping
- divergence / provenance bookkeeping
- snapshot export for UI / persistence

## Concrete migration seam

Introduce a `NativeSimulationWorld` abstraction and make `LabSession` own an `AuthoritativeWorld` rather than directly owning the Kotlin simulation engine in the hot path.

Suggested Kotlin-facing contract:

```kotlin
interface NativeSimulationWorld : AutoCloseable {
    val worldId: Long

    fun initialize(
        snapshot: SimulationSnapshot,
        config: SimulationConfig,
        options: NativeWorldOptions = NativeWorldOptions(),
    )

    fun replaceAll(
        snapshot: SimulationSnapshot,
        config: SimulationConfig,
    )

    fun step(
        simulationSeconds: Double,
        maxSubstepSeconds: Double,
        requestDiagnostics: Boolean = true,
    ): NativeStepResult

    fun applyCommands(commands: List<WorldCommand>)

    fun snapshot(detail: SnapshotDetail = SnapshotDetail.FULL): SimulationSnapshot

    fun diagnostics(): SimulationDiagnostics

    fun createCheckpoint(label: String? = null): Long
    fun restoreCheckpoint(checkpointId: Long): Boolean
    fun dropCheckpoint(checkpointId: Long): Boolean

    fun backendSummary(): NativeBackendSummary

    override fun close()
}
```

Suggested command surface:

```kotlin
sealed interface WorldCommand {
    data class AddBody(val body: BodyState) : WorldCommand
    data class UpdateBody(val body: BodyState) : WorldCommand
    data class RemoveBody(val bodyId: String) : WorldCommand
    data class SetCollisionMode(val mode: CollisionMode) : WorldCommand
    data class SetTracerMutualGravity(val enabled: Boolean) : WorldCommand
    data class SetTimelineMode(
        val mode: TimelineMode,
        val provenanceLabel: String? = null,
        val provenanceSource: String? = null,
    ) : WorldCommand
}
```

## Sequencing

1. Introduce `AuthoritativeWorld` seam in Kotlin.
2. Add `NativeSimulationWorld` handle + JNI bridge.
3. Move Sandbox commands (add/edit/delete) onto native command batches.
4. Move checkpoints / rewind / diagnostics fully native.
5. Move timeline stepping + provenance / divergence tracking fully native.
6. Switch Runtime and Sandbox to the same native world implementation.
7. Keep Kotlin simulation as the reference/oracle path and test fallback, not the hot-path owner.

## Success condition

The app should no longer have one beautiful local sandbox world and one separate authoritative runtime world. There should be one authoritative world, rendered by the same immersive native stage, with Kotlin acting as shell/UI only.
