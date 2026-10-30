package com.filesearch.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * F020 UI polish theme: stock M3 light/dark palettes picked by system night
 * mode, so dark is a deliberate dark scheme — never an inverted light one.
 * Each screen wraps itself so Paparazzi snapshots (which call screens
 * directly, no activity theme) render the right palette in both modes.
 */
@Composable
fun FileSearchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
