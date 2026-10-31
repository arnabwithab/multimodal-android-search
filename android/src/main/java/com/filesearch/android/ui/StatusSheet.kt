package com.filesearch.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
 *
 * Stock M3 Scaffold + TopAppBar: the Scaffold's Surface supplies the content
 * color (a bare Column left heading text black-on-dark); Pause stays the one
 * primary action, Close is text.
 *
 * The Index-now button lives here (not above the sheet) so it and the
 * progress rows can't disagree: while [indexing] it is disabled and shows a
 * spinner + "Indexing…" instead of a separate tap target; the per-kind rows
 * plus [remainingLabel] already carry the exact counts, so no standalone
 * status line is rendered. When idle it is a normal "Index now" button.
 */

/** One per-kind progress line; engine supplies done/total counts. */
data class KindProgress(
    val kind: String,
    val done: Long,
    val total: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheetContent(
    progress: List<KindProgress>,
    remainingLabel: String,
    paused: Boolean,
    pauseLabel: String?,
    onPauseToggle: () -> Unit,
    onDismiss: () -> Unit,
    indexing: Boolean = false,
    onIndexNow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    FileSearchTheme {
        Scaffold(
            // Wrap height (no fillMaxSize): this renders as tab content on
            // device, so full-height would push siblings off-screen; width
            // fill keeps the sheet edge-to-edge.
            modifier = modifier.fillMaxWidth().testTag("statusSheet"),
            topBar = { TopAppBar(title = { Text("Indexing status") }) },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                // The button IS the running state: disabled + spinner while
                // indexing, plain "Index now" when idle. Counts live in the
                // rows + remainingLabel below — no separate status line.
                Button(
                    onClick = onIndexNow,
                    enabled = !indexing,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("indexNowButton"),
                ) {
                    if (indexing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Indexing…")
                    } else {
                        Text("Index now")
                    }
                }
                progress.forEach { line ->
                    val fraction =
                        if (line.total <= 0) 0f else (line.done.toFloat() / line.total).coerceIn(0f, 1f)
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                line.kind,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${line.done}/${line.total}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                // Honest ETA straight from the engine estimate.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(remainingLabel, style = MaterialTheme.typography.bodyMedium)
                }
                if (paused && pauseLabel != null) {
                    // Paused state needs a signal color (design §4.2): stock
                    // M3 tertiary roles, content color inherited, no custom shape.
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Paused: $pauseLabel", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    FilledTonalButton(
                        onClick = onPauseToggle,
                        modifier = Modifier.testTag("pauseButton"),
                    ) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (paused) "Resume" else "Pause")
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Back to Search")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Offline — nothing leaves the phone except model download and opt-in voice.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
