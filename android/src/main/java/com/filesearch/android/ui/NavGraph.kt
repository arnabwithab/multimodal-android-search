package com.filesearch.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * F020 minimal 3-screen host (design §4: Search / Indexing status / Settings).
 * Material3 bottom bar — no navigation library for three static tabs.
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
    FileSearchTheme {
        var tab by rememberSaveable { mutableStateOf(0) }
        Column(modifier = modifier) {
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> search()
                    1 -> status()
                    else -> settings()
                }
            }
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                    label = { Text("Status") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        }
    }
}
