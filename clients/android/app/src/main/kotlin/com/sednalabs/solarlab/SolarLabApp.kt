package com.sednalabs.solarlab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.sednalabs.solarlab.runtime.RuntimeFacade

/**
 * Composable shell for v2 runtime rendering.
 *
 * It is intentionally UI-only: all mutation commands, session lifecycle, and snapshot
 * interpretation sit behind `RuntimeFacade`.
 */
@Composable
fun SolarLabApp(runtimeFacade: RuntimeFacade) {
    // Single source of truth for the shell; collectAsState keeps recomposition tied to
    // boundary-delivered signals without local cache duplication.
    val uiState by runtimeFacade.uiState.collectAsState()

    // Start handshake once per facade identity; this ties session ownership to the caller
    // that owns the facade instance.
    LaunchedEffect(runtimeFacade) {
        runtimeFacade.startSession()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Solar Lab v2",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = uiState.statusLine,
                    style = MaterialTheme.typography.bodyLarge
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 240.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    if (uiState.renderFrame != null) {
                        // AndroidView is used only as a host for the SurfaceView renderer;
                        // it receives immutable frame snapshots from state and never executes
                        // scene interpretation itself.
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                VulkanPacketRenderSurfaceView(context = context)
                            },
                            update = { view ->
                                view.submitFrame(uiState.renderFrame)
                            }
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color(0xFF111827),
                        ) {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                uiState.detailLine?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                uiState.sessionHandle?.let { handle ->
                    Text(
                        text = "Session handle: $handle",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                uiState.renderPacketSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
