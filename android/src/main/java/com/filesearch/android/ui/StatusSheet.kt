package com.filesearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * F020 indexing-status sheet content (design §4.2: per-kind progress, pause,
 * honest estimate, offline note). Pure presentational — the host feeds
 * [remainingLabel] from engine MeasuredCosts + formatRemaining and [paused] /
 * [pauseLabel] from PowerPolicy.shouldPause. The host wraps this in a
 * ModalBottomSheet on device; kept a plain Column so Paparazzi stays JVM-safe.
 */

/** One per-kind progress line; engine supplies done/total counts. */
data class KindProgress(
    val kind: String,
    val done: Long,
    val total: Long,
)

@Composable
fun StatusSheetContent(
    progress: List<KindProgress>,
    remainingLabel: String,
    paused: Boolean,
    pauseLabel: String?,
    onPauseToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp).testTag("statusSheet")) {
        Text("Indexing status", style = MaterialTheme.typography.titleLarge)
        progress.forEach { line ->
            Text(
                "${line.kind}: ${line.done}/${line.total}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(remainingLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (paused && pauseLabel != null) {
            Text("Paused: $pauseLabel", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = onPauseToggle, modifier = Modifier.testTag("pauseButton")) {
                Text(if (paused) "Resume" else "Pause")
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                Text("Close")
            }
        }
        Text(
            "Offline — nothing leaves the phone except model download and opt-in voice.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
