package com.sednalabs.solarlab

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val STAGE_CAMERA_COACH_PORTRAIT_MAX_HEIGHT_FRACTION = 0.20f

private const val STAGE_CAMERA_PREFERENCES = "stage_camera_guidance"
private const val STAGE_CAMERA_COACH_SEEN = "stage_camera_coach_seen_v1"

internal fun shouldShowStageCameraCoach(context: Context): Boolean =
    !context.getSharedPreferences(STAGE_CAMERA_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(STAGE_CAMERA_COACH_SEEN, false)

internal fun markStageCameraCoachSeen(context: Context) {
    context.getSharedPreferences(STAGE_CAMERA_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(STAGE_CAMERA_COACH_SEEN, true)
        .apply()
}

@Composable
internal fun StageCameraCoach(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coachDescription = stringResource(R.string.stage_camera_coach_description)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val maxPanelHeight = maxHeight * if (portrait) {
            STAGE_CAMERA_COACH_PORTRAIT_MAX_HEIGHT_FRACTION
        } else {
            0.38f
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 520.dp)
                .heightIn(max = maxPanelHeight)
                .testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH)
                .semantics { contentDescription = coachDescription },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.stage_camera_coach_orbit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stage_camera_coach_pan_zoom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stage_camera_coach_selection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StageActionButton(
                    label = stringResource(R.string.stage_camera_coach_dismiss),
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON),
                    dense = true,
                )
            }
        }
    }
}
