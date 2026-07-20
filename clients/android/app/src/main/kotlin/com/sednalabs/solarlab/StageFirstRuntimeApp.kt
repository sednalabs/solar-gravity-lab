package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.sednalabs.solarlab.runtime.RuntimeFacade
import kotlinx.coroutines.flow.Flow

private val StageOverlayPanel = Color(0xE6070D18)
private val StageControlRail = Color(0xEA07101A)
private val StageOverlayStroke = Color(0x5C76F7FF)
private val StagePrimaryAction = Color(0xFF17344A)
private val StageSecondaryAction = Color(0xFF0E1B29)
private val StageSurfaceText = Color(0xFFF4FBFF)
private const val COMPACT_EXPANDED_STAGE_DECK_MAX_FRACTION = 0.30f
private const val WIDE_EXPANDED_STAGE_DECK_MAX_FRACTION = 0.34f

internal val StageActionMinimumTouchTarget = 48.dp

internal fun expandedStageDeckMaxHeightFraction(compactLayout: Boolean): Float =
    if (compactLayout) {
        COMPACT_EXPANDED_STAGE_DECK_MAX_FRACTION
    } else {
        WIDE_EXPANDED_STAGE_DECK_MAX_FRACTION
    }

internal data class PendingSemanticAction(
    val token: Long,
    val action: SolarLabSemanticAction,
)

/**
 * The stage-first Android entry point. Every visible stage is backed by the
 * same Rust runtime; Compose owns only lifecycle, controls, accessibility, and
 * presentation state.
 */
@Composable
internal fun StageFirstRuntimeApp(
    runtimeFacade: RuntimeFacade,
    ensureRuntimeStarted: () -> Unit,
    semanticActions: Flow<SolarLabSemanticAction> = SolarLabSemanticActionBridge.commands,
    runtimeMountedState: MutableState<Boolean>? = null,
) {
    var nextSemanticToken by remember { mutableStateOf(0L) }
    var pendingSemanticAction by remember { mutableStateOf<PendingSemanticAction?>(null) }

    LaunchedEffect(semanticActions) {
        semanticActions.collect { action ->
            nextSemanticToken += 1L
            pendingSemanticAction = PendingSemanticAction(
                token = nextSemanticToken,
                action = action,
            )
            SolarLabSemanticActionBridge.clearPendingReplay()
        }
    }

    StageFirstRuntimeExperience(
        runtimeFacade = runtimeFacade,
        ensureRuntimeStarted = ensureRuntimeStarted,
        pendingSemanticAction = pendingSemanticAction,
        runtimeMountedState = runtimeMountedState,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun StageFloatingActionRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun StageControlRail(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillMaxWidth: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = StageControlRail,
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        border = BorderStroke(1.dp, StageOverlayStroke),
    ) {
        val widthModifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
        val railModifier = widthModifier.padding(
            horizontal = if (compact) 4.dp else 10.dp,
            vertical = if (compact) 7.dp else 10.dp,
        )
        if (compact) {
            FlowRow(
                modifier = railModifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        } else {
            Row(
                modifier = railModifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun StagePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = StageOverlayPanel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, StageOverlayStroke),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
internal fun StageActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    secondary: Boolean = false,
    dense: Boolean = false,
) {
    val container = when {
        emphasized -> MaterialTheme.colorScheme.primaryContainer
        secondary -> StageSecondaryAction
        else -> StagePrimaryAction
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        StageSurfaceText
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = StageActionMinimumTouchTarget)
            .sizeIn(minWidth = if (dense) StageActionMinimumTouchTarget else 88.dp)
            .semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        shape = RoundedCornerShape(if (dense) 13.dp else 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
            disabledContainerColor = container.copy(alpha = 0.45f),
            disabledContentColor = contentColor.copy(alpha = 0.55f),
        ),
        contentPadding = PaddingValues(
            horizontal = if (dense) 6.dp else 16.dp,
            vertical = 0.dp,
        ),
    ) {
        Text(
            text = label,
            style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun formatDistance(distanceM: Double): String = when {
    distanceM >= 0.01 * PhysicalConstants.ASTRONOMICAL_UNIT_M ->
        "%.2f AU".format(distanceM / PhysicalConstants.ASTRONOMICAL_UNIT_M)

    distanceM >= 1_000_000.0 -> "%.0f km".format(distanceM / 1_000.0)
    else -> "%.0f m".format(distanceM)
}
