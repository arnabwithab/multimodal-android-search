package com.filesearch.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
@OptIn(ExperimentalMaterial3Api::class)
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
    searching: Boolean = false,
) {
    FileSearchTheme {
        var active by remember { mutableStateOf(false) }
        Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            DockedSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { active = false },
                active = active,
                onActiveChange = { active = it },
                modifier = Modifier.fillMaxWidth().testTag("searchBar"),
                placeholder = { Text("Describe what you remember…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                        // WhatsApp-style circular mic: tonal filled circle, not a text button.
                        FilledTonalIconButton(
                            onClick = onMicClick,
                            modifier = Modifier.testTag("micButton"),
                            colors =
                                if (listening) {
                                    IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                } else {
                                    IconButtonDefaults.filledTonalIconButtonColors()
                                },
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = if (listening) "Listening…" else "Voice search",
                            )
                        }
                    }
                },
            ) {
                if (query.isBlank()) {
                    Text(
                        "Try a word, a place, or how it looked…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (searching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("searching"),
                )
            }
            if (query.isNotBlank() && !searching && visibleRows.isEmpty() && weakCount == 0) {
                // Honest empty state (design §6): never a random file.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                ) {
                    Icon(
                        Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(
                        "nothing stands out",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("emptyState"),
                    )
                    Text(
                        "No matches in your index yet — try different words.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp).testTag("resultList"),
            ) {
                itemsIndexed(visibleRows, key = { _, row -> row.thumbKey + row.fileName + row.locLabel + row.excerpt }) { i, row ->
                    ResultRow(
                        row = row,
                        onSpeakClick = { onSpeakClick(row) },
                        onOpenResult = { onOpenResult(row) },
                        modifier = Modifier.testTag("row_$i"),
                    )
                }
            }
            if (weakCount > 0) {
                OutlinedButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("weakFold"),
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (expanded) "hide weaker matches" else "weaker matches ($weakCount)")
                }
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            // Thumb placeholder: tonal tile keyed by kind; real covers load on device later.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        row.kind.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.locLabel.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                row.locLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // Kind badge.
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        row.kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                // Engine-marked «match» excerpt, shown verbatim.
                Text(
                    row.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column {
                IconButton(onClick = onSpeakClick) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read excerpt aloud")
                }
                IconButton(onClick = onOpenResult) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open result")
                }
            }
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
    searching: Boolean = false,
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
        searching = searching,
        modifier = modifier,
    )
}
