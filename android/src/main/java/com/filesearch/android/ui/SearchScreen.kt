package com.filesearch.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.filesearch.android.VoiceService

/**
 * F020 thin Compose binding over engine UiState (design §4.1/§6, arch §5).
 *
 * :android cannot depend on :app (see android/build.gradle.kts header), so the
 * row/holdback shapes below mirror app::ResultRow / app::SearchUiState
 * field-for-field; the host maps engine state 1:1 (query, engine-folded
 * visibleRows, weakCount, expanded) and forwards callbacks. Zero embed/decode/
 * query logic here — all ranking, folding (strongCount) and «match» marking
 * happen in engine; rows arrive display-ready.
 *
 * // shortcut: hand-mirrored from app/UiState.kt — unify into a shared module
 * // when the build allows it (same story as android/PowerPolicy.kt).
 */

/** Display-ready row: excerpt already «marked», locLabel already formatted by engine. */
data class SearchRowUi(
    val fileName: String,
    val kind: String,
    val excerpt: String,
    val locLabel: String,
    val thumbKey: String,
)

/**
 * Pure presentational search list (Paparazzi-safe: no permissions, no services).
 * [visibleRows] is engine-folded; [expanded] only flips the fold button label.
 */
@Composable
fun SearchScreenContent(
    query: String,
    onQueryChange: (String) -> Unit,
    visibleRows: List<SearchRowUi>,
    weakCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    listening: Boolean,
    onMicClick: () -> Unit,
    onSpeakClick: (SearchRowUi) -> Unit,
    onOpenResult: (SearchRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Describe what you remember…") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("searchBar"),
            )
            Button(onClick = onMicClick, modifier = Modifier.padding(start = 8.dp).testTag("micButton")) {
                Text(if (listening) "…" else "Mic")
            }
        }
        if (query.isNotBlank() && visibleRows.isEmpty() && weakCount == 0) {
            // Honest empty state (design §6): never a random file.
            Text("nothing stands out", modifier = Modifier.padding(top = 24.dp).testTag("emptyState"))
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp).testTag("resultList"),
        ) {
            itemsIndexed(visibleRows, key = { _, row -> row.thumbKey + row.fileName }) { i, row ->
                ResultRow(
                    row = row,
                    onSpeakClick = { onSpeakClick(row) },
                    onOpenResult = { onOpenResult(row) },
                    modifier = Modifier.testTag("row_$i"),
                )
            }
        }
        if (weakCount > 0) {
            Button(
                onClick = onToggleExpanded,
                modifier = Modifier.padding(top = 8.dp).testTag("weakFold"),
            ) {
                Text(if (expanded) "hide weaker matches" else "weaker matches ($weakCount)")
            }
        }
    }
}

@Composable
private fun ResultRow(
    row: SearchRowUi,
    onSpeakClick: () -> Unit,
    onOpenResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Thumb covers load on device later; thumbKey reserves the lookup.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) {
            Text(row.kind.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (row.locLabel.isNotEmpty()) {
                    Text(row.locLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(row.kind, style = MaterialTheme.typography.labelSmall)
            Text(row.excerpt, style = MaterialTheme.typography.bodyMedium)
        }
        Column {
            Button(onClick = onSpeakClick) { Text("Speak") }
            Button(onClick = onOpenResult) { Text("Open") }
        }
    }
}

/**
 * Voice-wired entry point: RECORD_AUDIO runtime permission is requested BEFORE
 * VoiceService.startListening (review-flagged gap); TTS reads the excerpt aloud.
 * The host owns query state + engine search; this only bridges mic/speaker.
 */
@Composable
fun SearchScreen(
    voice: VoiceService,
    query: String,
    onQueryChange: (String) -> Unit,
    visibleRows: List<SearchRowUi>,
    weakCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onHeard: (String) -> Unit,
    onOpenResult: (SearchRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    fun listen(): Boolean =
        voice.startListening(
            onResult = { heard ->
                listening = false
                onHeard(heard)
            },
            onError = { listening = false },
        )
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listening = listen()
        }
    SearchScreenContent(
        query = query,
        onQueryChange = onQueryChange,
        visibleRows = visibleRows,
        weakCount = weakCount,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        listening = listening,
        onMicClick = {
            listening =
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    listen()
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    false
                }
        },
        // Guillemets are visual marks; strip so TTS does not read them aloud.
        onSpeakClick = { row -> voice.speak(row.excerpt.replace("«", "").replace("»", "")) },
        onOpenResult = onOpenResult,
        modifier = modifier,
    )
}
