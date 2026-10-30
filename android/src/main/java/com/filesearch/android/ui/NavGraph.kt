package com.filesearch.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
    // Controlled-tab seam (every-button audit): when [tab] is non-null the
    // host owns navigation (e.g. Status "Back to Search" sets tab 0);
    // otherwise the bar keeps its own internal tab (Paparazzi/tests).
    tab: Int? = null,
    onTabChange: ((Int) -> Unit)? = null,
) {
    FileSearchTheme {
        var internalTab by rememberSaveable { mutableStateOf(0) }
        val selected = tab ?: internalTab
        fun select(i: Int) {
            if (onTabChange != null) onTabChange(i) else internalTab = i
        }
        // Stock M3: one Scaffold owns the bottom bar and its insets; each tab
        // screen is plain content (its own Scaffold/TopAppBar when it needs a
        // heading). Previously a hand-rolled Column(statusBarsPadding) here.
        Scaffold(
            modifier = modifier,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selected == 0,
                        onClick = { select(0) },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = selected == 1,
                        onClick = { select(1) },
                        icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                        label = { Text("Status") },
                    )
                    NavigationBarItem(
                        selected = selected == 2,
                        onClick = { select(2) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (selected) {
                    0 -> search()
                    1 -> status()
                    else -> settings()
                }
            }
        }
    }
}
