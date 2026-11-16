package com.sednalabs.solarlab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import com.graciousgazelles.solarlab.render.core.CameraState
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import com.sednalabs.solarlab.runtime.ProbeCommitResult
import com.sednalabs.solarlab.runtime.ProbePreviewResult
import com.sednalabs.solarlab.runtime.RuntimeBodyState

/** Short-lived authoring state. A draft cannot outlive its authoritative session. */
internal class LabInteractionState(val operationScope: CoroutineScope? = null) {
    var boundSessionHandle = 0L
    var appliedCommitUiId: String? = null
    var savedCamera: CameraState? = null
    var savedCameraReferenceId: String? = null
    var savedCameraReferencePosition: Vector3d? = null
    var opening by mutableStateOf(false)
    var openingGeneration = 0L
    var viewport by mutableStateOf<LabViewportState?>(null)
    var plane by mutableStateOf<LabAuthoringPlane?>(null)
    var referenceBodies by mutableStateOf<Map<String, RuntimeBodyState>>(emptyMap())
    var draft by mutableStateOf<LabProbeDraft?>(null)
    var draftVersion by mutableStateOf(0L)
    var preview by mutableStateOf<ProbePreviewResult?>(null)
    var previewing by mutableStateOf(false)
    var committing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lastCommit by mutableStateOf<ProbeCommitResult?>(null)
    var undoing by mutableStateOf(false)
    var beforeGesture: LabProbeDraft? = null
    var beforePlacementCamera: CameraState? = null
    var beforePlacementView = LabViewMode.THREE_DIMENSIONAL
    var beforePlacementSelection: String? = null
    var beforePlacementObserver = ObserverMode.FREE
    var feedback by mutableStateOf<String?>(null)
    var selectedFacts by mutableStateOf<String?>(null)

    fun changeDraft(next: LabProbeDraft) {
        if (next == draft) return
        draft = next
        draftVersion += 1
        preview = null
        previewing = false
        error = null
    }

    fun clearDraft() {
        opening = false
        openingGeneration += 1
        draft = null
        draftVersion += 1
        preview = null
        previewing = false
        committing = false
        error = null
        beforeGesture = null
    }
}

/** Drafts survive orientation changes but never a replacement of the authoritative world. */
internal class LabInteractionViewModel : ViewModel() {
    val state = LabInteractionState(viewModelScope)
}
