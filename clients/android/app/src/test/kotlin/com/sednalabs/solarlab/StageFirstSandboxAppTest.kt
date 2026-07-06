package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.feature.lab.render.PlacementGesturePhase
import com.graciousgazelles.solarlab.feature.lab.render.PlacementGestureUpdate
import com.graciousgazelles.solarlab.render.core.TraceLayerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageFirstSandboxAppTest {
    @Test
    fun stageChromeModeToggleRestoresCollapsedHudFromMinimalMode() {
        assertEquals(StageChromeMode.COLLAPSED, StageChromeMode.MINIMAL.toggle())
        assertEquals(StageChromeMode.EXPANDED, StageChromeMode.COLLAPSED.toggle())
        assertEquals(StageChromeMode.COLLAPSED, StageChromeMode.EXPANDED.toggle())
        assertEquals(StageChromeMode.COLLAPSED, stageChromeModeFromName("missing"))
    }

    @Test
    fun objectCommitReturnsToCompactStageChrome() {
        assertEquals(StageChromeMode.COLLAPSED, stageChromeModeAfterObjectCommit(StageChromeMode.MINIMAL))
        assertEquals(StageChromeMode.COLLAPSED, stageChromeModeAfterObjectCommit(StageChromeMode.COLLAPSED))
        assertEquals(StageChromeMode.COLLAPSED, stageChromeModeAfterObjectCommit(StageChromeMode.EXPANDED))
    }

    @Test
    fun expandedStageDeckMaxHeightFractionKeepsPhoneStageDominant() {
        assertEquals(0.30f, expandedStageDeckMaxHeightFraction(compactLayout = true))
        assertEquals(0.34f, expandedStageDeckMaxHeightFraction(compactLayout = false))
        assertEquals(
            0.26f,
            expandedStageDeckMaxHeightFraction(compactLayout = true, authoringActive = true),
        )
        assertEquals(
            0.30f,
            expandedStageDeckMaxHeightFraction(compactLayout = false, authoringActive = true),
        )
    }

    @Test
    fun traceLayerModeNextCyclesFocusAllOff() {
        assertEquals(TraceLayerMode.ALL, TraceLayerMode.FOCUS.next())
        assertEquals(TraceLayerMode.OFF, TraceLayerMode.ALL.next())
        assertEquals(TraceLayerMode.FOCUS, TraceLayerMode.OFF.next())
        assertEquals(TraceLayerMode.FOCUS, traceLayerModeFromName("missing"))
    }

    @Test
    fun traceLayerButtonLabelKeepsCompactHudShort() {
        assertEquals("Trace", traceLayerButtonLabel(TraceLayerMode.FOCUS, compact = true))
        assertEquals("Traces: All", traceLayerButtonLabel(TraceLayerMode.ALL, compact = false))
        assertEquals("Off", traceLayerButtonLabel(TraceLayerMode.OFF, compact = true))
    }

    @Test
    fun buildIdleMissionTrajectoryDetail_namesSceneBodyCount() {
        assertEquals(
            "Tracking 39 bodies with live fly paths. " +
                "Tap a luminous body to focus, or open Immersive for the accelerated runtime view.",
            buildIdleMissionTrajectoryDetail(39),
        )
    }

    @Test
    fun buildIdleMissionTrajectoryDetail_handlesSceneWarmup() {
        assertEquals(
            "Acquiring ephemeris scene and live fly paths. " +
                "Tap a luminous body to focus, or open Immersive for the accelerated runtime view.",
            buildIdleMissionTrajectoryDetail(null),
        )
    }

    @Test
    fun compactStageBackendHudStatusText_removesRendererPacketTelemetry() {
        assertEquals(
            "Vulkan SPIR-V + compute compaction active. Wide orbit 63° / yaw -34°",
            compactStageBackendHudStatusText(
                "Vulkan SPIR-V graphics pipelines + compute compaction active. " +
                    "Wide orbit 63° / yaw -34° · rev=80 A=39/AI=39 TN=268 TM=12 TF=20 " +
                    "TL=118/57 bytes=1728384 paths=[sprite,sprite,cheap-point,density-point,thin-line] " +
                    "compute=[TM:1/src=state/vis=-,TF:1/src=state/cap=81600/tiles=10200]"
            ),
        )
    }

    @Test
    fun compactStageBackendHudStatusText_boundsUnknownLongStatus() {
        val compacted = compactStageBackendHudStatusText("Renderer " + "packet ".repeat(80))

        assertTrue(compacted.endsWith("... [truncated]"))
        assertTrue(compacted.length <= 135)
    }

    @Test
    fun compactStageBackendHudStatusText_usesStableFallbackForBlankStatus() {
        assertEquals(
            "Preparing immersive Vulkan stage.",
            compactStageBackendHudStatusText(" \n\t "),
        )
    }

    @Test
    fun compactStageTimelineLabel_removesDenseTimelineDetailForCollapsedChrome() {
        assertEquals(
            "Catalog timeline",
            compactStageTimelineLabel("Catalog timeline | JD(TDB) 2451550.15640\nSpeed 6 h/s • Step 6 h"),
        )
        assertEquals("Trajectory stage", compactStageTimelineLabel(" \n\t "))
    }

    @Test
    fun bodyEditorCopyTreatsStagedAdjustmentAsPreviewRefinement() {
        assertEquals(
            "Refine staged object",
            bodyEditorTitle(
                isNewBody = true,
                isPlacementAdjustment = true,
                draftName = "Draft probe",
            ),
        )
        assertEquals(
            "Tune the staged ghost and launch values before committing it to the simulation.",
            bodyEditorDetail(
                isNewBody = true,
                isPlacementAdjustment = true,
            ),
        )
        assertEquals(
            "Update preview",
            bodyEditorPrimaryActionLabel(
                isNewBody = true,
                isPlacementAdjustment = true,
                placeOnSceneAfterSave = true,
            ),
        )
    }

    @Test
    fun bodyEditorCopyKeepsNewObjectAndExistingBodyLabelsDistinct() {
        assertEquals(
            "Add object",
            bodyEditorTitle(
                isNewBody = true,
                isPlacementAdjustment = false,
                draftName = "Draft probe",
            ),
        )
        assertEquals(
            "Edit Earth",
            bodyEditorTitle(
                isNewBody = false,
                isPlacementAdjustment = false,
                draftName = "Earth",
            ),
        )
        assertEquals(
            "Stage placement",
            bodyEditorPrimaryActionLabel(
                isNewBody = true,
                isPlacementAdjustment = false,
                placeOnSceneAfterSave = true,
            ),
        )
        assertEquals(
            "Add at coordinates",
            bodyEditorPrimaryActionLabel(
                isNewBody = true,
                isPlacementAdjustment = false,
                placeOnSceneAfterSave = false,
            ),
        )
        assertEquals(
            "Apply",
            bodyEditorPrimaryActionLabel(
                isNewBody = false,
                isPlacementAdjustment = false,
                placeOnSceneAfterSave = false,
            ),
        )
    }

    @Test
    fun placementSessionStagesGestureWithoutCommittingBody() {
        val session = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault().copy(
                positionM = Vector3d(0.0, 0.0, 12.0),
                velocityMps = Vector3d(1.0, 2.0, 3.0),
            ),
            bodyId = "custom:test",
        )

        val startWorldPosition = Vector3d(100.0, 200.0, 12.0)
        val endWorldPosition = startWorldPosition.copy(x = startWorldPosition.x + 2.0)
        val staged = session.applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Ended,
                startWorldPositionM = startWorldPosition,
                endWorldPositionM = endWorldPosition,
                gestureDistancePx = PLACEMENT_DRAG_THRESHOLD_PX + 1f,
            ),
        )

        assertTrue(staged.canCommit)
        assertEquals(Vector3d(100.0, 200.0, 12.0), staged.stagedPositionM)
        // Gesture drag defines planar staged velocity as (end - start) / lookahead;
        // z remains from the draft velocity.
        val expectedVelocityX = (endWorldPosition.x - startWorldPosition.x) / PLACEMENT_DRAG_LOOKAHEAD_SECONDS
        val expectedVelocityY = (endWorldPosition.y - startWorldPosition.y) / PLACEMENT_DRAG_LOOKAHEAD_SECONDS
        assertEquals(expectedVelocityX, staged.stagedVelocityMps.x, 0.0)
        assertEquals(expectedVelocityY, staged.stagedVelocityMps.y, 0.0)
        assertEquals(3.0, staged.stagedVelocityMps.z, 0.0)

        val committed = staged.toBodyState()
        assertEquals("custom:test", committed.id)
        assertEquals(staged.stagedPositionM, committed.positionM)
        assertEquals(staged.stagedVelocityMps, committed.velocityMps)
    }

    @Test
    fun placementSessionShowsDragPreviewButRequiresEndedGestureBeforeCommit() {
        val dragging = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault(),
            bodyId = "custom:test",
        ).applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Changed,
                startWorldPositionM = Vector3d(10.0, 20.0, 0.0),
                endWorldPositionM = Vector3d(11.0, 20.0, 0.0),
                gestureDistancePx = PLACEMENT_DRAG_THRESHOLD_PX + 1f,
            ),
        )

        assertFalse(dragging.canCommit)
        assertNotNull(dragging.toPlacementPreview(massiveBodies = emptyList()))

        val cancelled = dragging.applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Cancelled,
                startWorldPositionM = dragging.stagedPositionM,
                endWorldPositionM = dragging.stagedPositionM,
                gestureDistancePx = 0f,
            ),
        )
        assertFalse(cancelled.canCommit)
        assertNull(cancelled.toPlacementPreview(massiveBodies = emptyList()))
    }

    @Test
    fun placementSessionAdjustmentBeforeStageDoesNotBypassStageTap() {
        val adjusted = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault(),
            bodyId = "custom:test",
        ).withDraftValues(
            EditableBodyDraft.newDefault().copy(
                positionM = Vector3d(5.0, 6.0, 7.0),
                velocityMps = Vector3d(8.0, 9.0, 10.0),
            ),
        )

        assertFalse(adjusted.canCommit)
        assertEquals(Vector3d(5.0, 6.0, 7.0), adjusted.stagedPositionM)
        assertEquals(Vector3d(8.0, 9.0, 10.0), adjusted.stagedVelocityMps)
        assertNull(adjusted.toPlacementPreview(massiveBodies = emptyList()))
    }

    @Test
    fun placementSessionAdjustmentAfterStageKeepsExactValuesCommittable() {
        val staged = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault(),
            bodyId = "custom:test",
        ).applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Ended,
                startWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                endWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                gestureDistancePx = 0f,
            ),
        )

        val adjusted = staged.withDraftValues(
            staged.draft.copy(
                positionM = Vector3d(10.0, 20.0, 30.0),
                velocityMps = Vector3d(40.0, 50.0, 60.0),
            ),
        )

        assertTrue(adjusted.canCommit)
        assertEquals(Vector3d(10.0, 20.0, 30.0), adjusted.stagedPositionM)
        assertEquals(Vector3d(40.0, 50.0, 60.0), adjusted.stagedVelocityMps)
        assertEquals(Vector3d(10.0, 20.0, 30.0), adjusted.toBodyState().positionM)
        assertEquals(Vector3d(40.0, 50.0, 60.0), adjusted.toBodyState().velocityMps)
    }

    @Test
    fun placementSessionRepositionClearsCommitReadinessButKeepsDraft() {
        val staged = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault(),
            bodyId = "custom:test",
        ).applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Ended,
                startWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                endWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                gestureDistancePx = 0f,
            ),
        )

        val repositioning = staged.reposition()

        assertFalse(repositioning.canCommit)
        assertEquals("custom:test", repositioning.bodyId)
        assertEquals(staged.draft, repositioning.draft)
    }

    @Test
    fun placementSessionSaveableMapRestoresRoundTrip() {
        val staged = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault().copy(
                name = "Draft probe",
                existingHostBodyId = "earth",
                positionM = Vector3d(1.0, 2.0, 3.0),
                velocityMps = Vector3d(4.0, 5.0, 6.0),
            ),
            bodyId = "custom:test",
        ).applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Ended,
                startWorldPositionM = Vector3d(10.0, 20.0, 3.0),
                endWorldPositionM = Vector3d(11.0, 20.0, 3.0),
                gestureDistancePx = PLACEMENT_DRAG_THRESHOLD_PX + 1f,
            ),
        )

        val restored = BodyPlacementSession.restore(staged.toSaveableMap())

        assertEquals(staged, restored)
        assertTrue(restored?.canCommit == true)
    }

    @Test
    fun placementSessionRestoreRejectsUnknownSaveableVersion() {
        val payload = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault(),
            bodyId = "custom:test",
        ).toSaveableMap().toMutableMap()

        payload["version"] = 999

        assertNull(BodyPlacementSession.restore(payload))
    }

    @Test
    fun draftTrajectoryProjectorBendsPreviewTowardMassiveBody() {
        val attractor = body(
            id = "sun",
            massKg = 1.989e30,
            positionM = Vector3d.ZERO,
        )

        val projected = DraftTrajectoryProjector.project(
            startPositionM = Vector3d(149_597_870_700.0, 0.0, 0.0),
            startVelocityMps = Vector3d(0.0, 29_780.0, 0.0),
            massiveBodies = listOf(attractor),
            horizonSeconds = 86_400.0,
            sampleCount = 4,
        )

        assertEquals(Vector3d(149_597_870_700.0, 0.0, 0.0), projected.first())
        assertEquals(4, projected.size)
        val inwardDisplacementM = projected.first().x - projected.last().x
        assertTrue("Forecast should advance along the velocity vector", projected.last().y > 0.0)
        assertTrue("Solar gravity should bend the preview inward", projected.last().x < projected.first().x)
        assertTrue(
            "Solar gravity should produce a meaningful inward deflection over one day",
            inwardDisplacementM > 1_000_000.0,
        )
    }

    @Test
    fun draftTrajectoryProjectorScalesDefaultHorizonForLowOrbit() {
        val earth = body(
            id = "earth",
            massKg = 5.972e24,
            positionM = Vector3d.ZERO,
        )

        val horizonSeconds = DraftTrajectoryProjector.forecastHorizonSeconds(
            startPositionM = Vector3d(6_778_000.0, 0.0, 0.0),
            massiveBodies = listOf(earth),
        )

        assertTrue(horizonSeconds >= MIN_PLACEMENT_FORECAST_HORIZON_SECONDS)
        assertTrue(horizonSeconds < PhysicalConstants.DAY_SECONDS)
    }

    @Test
    fun draftTrajectoryProjectorCapsDefaultHorizonForSystemScalePlacement() {
        val sun = body(
            id = "sun",
            massKg = 1.989e30,
            positionM = Vector3d.ZERO,
        )

        val horizonSeconds = DraftTrajectoryProjector.forecastHorizonSeconds(
            startPositionM = Vector3d(149_597_870_700.0, 0.0, 0.0),
            massiveBodies = listOf(sun),
        )

        assertEquals(MAX_PLACEMENT_FORECAST_HORIZON_SECONDS, horizonSeconds, 0.0)
    }

    @Test
    fun placementPreviewIsVisualOnlyUntilCommit() {
        val staged = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault(),
            bodyId = "custom:test",
        ).applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Ended,
                startWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                endWorldPositionM = Vector3d(2.0, 2.0, 0.0),
                gestureDistancePx = PLACEMENT_DRAG_THRESHOLD_PX + 1f,
            ),
        )

        val preview = staged.toPlacementPreview(massiveBodies = emptyList())

        assertNotNull(preview)
        assertEquals("custom:test", preview?.bodyId)
        assertEquals(Vector3d(1.0, 2.0, 0.0), preview?.positionM)
        assertEquals(staged.draft.massKg, preview?.sourceMassKg ?: 0.0, 0.0)
        assertEquals(PLACEMENT_FORECAST_SAMPLE_COUNT, preview?.forecastPointsM?.size)
    }

    @Test
    fun tracerPlacementPreviewDoesNotAdvertiseSourceMass() {
        val staged = BodyPlacementSession.fromDraft(
            draft = EditableBodyDraft.newDefault().copy(
                gravitationalRole = GravitationalRole.TRACER,
                massKg = 1.0e18,
            ),
            bodyId = "custom:tracer",
        ).applyGesture(
            PlacementGestureUpdate(
                phase = PlacementGesturePhase.Ended,
                startWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                endWorldPositionM = Vector3d(1.0, 2.0, 0.0),
                gestureDistancePx = 0f,
            ),
        )

        val preview = staged.toPlacementPreview(massiveBodies = emptyList())

        assertNotNull(preview)
        assertEquals(0.0, preview?.sourceMassKg ?: 1.0, 0.0)
    }

    private fun body(
        id: String,
        massKg: Double,
        positionM: Vector3d,
    ): BodyState = BodyState(
        id = id,
        name = id,
        category = BodyCategory.STAR,
        gravitationalRole = GravitationalRole.MASSIVE,
        massKg = massKg,
        radiusM = 1.0,
        densityKgPerM3 = BodyFactory.densityFromMassAndRadius(massKg, 1.0),
        positionM = positionM,
        velocityMps = Vector3d.ZERO,
        colorArgb = 0xFFFFFFFF.toInt(),
    )
}
