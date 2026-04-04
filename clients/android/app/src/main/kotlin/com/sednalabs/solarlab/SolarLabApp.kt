package com.sednalabs.solarlab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sednalabs.solarlab.runtime.RuntimeFacade

@Composable
fun SolarLabApp(runtimeFacade: RuntimeFacade) {
    val uiState by runtimeFacade.uiState.collectAsState()

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
