package com.filesearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * F020 minimal 3-screen host (design §4: Search / Indexing status / Settings).
 * Hand-rolled tab switch — no navigation library for three static tabs.
 * Engine/host state (UiState, MeasuredCosts, PowerPolicy) is mapped to screen
 * params by the caller; this only switches. Status renders as a tab here and
 * as a ModalBottomSheet on device (same StatusSheetContent either way).
 */
@Composable
fun FileSearchApp(
    search: @Composable () -> Unit,
    status: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            TabButton("Search", selected = tab == 0, onClick = { tab = 0 })
            TabButton("Status", selected = tab == 1, onClick = { tab = 1 })
            TabButton("Settings", selected = tab == 2, onClick = { tab = 2 })
        }
        when (tab) {
            0 -> search()
            1 -> status()
            else -> settings()
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !selected,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(label)
    }
}
