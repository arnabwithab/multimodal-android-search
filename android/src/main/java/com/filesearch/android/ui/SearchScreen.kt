package com.filesearch.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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

/** Display-ready row: locLabel/excerpt still arrive engine-formatted (engine «match»
 * marking + TTS/open plumbing untouched) but rows render per-kind with no text
 * clutter — excerpt is never shown. */
data class SearchRowUi(
    val fileName: String,
    val kind: String,
    val excerpt: String,
    val locLabel: String,
    val thumbKey: String,
    /** Indexed file URI (MediaStore content URI or file path) for thumbs/previews. */
    val fileUri: String = "",
)

/**
 * Pure presentational search list (Paparazzi-safe: no permissions, no services).
 * [visibleRows] is engine-folded; [expanded] only flips the fold button label.
 * Inline preview: tapping a thumbnail sets [expandedKey] to that row's
 * thumbKey (stays in the Search tab — never navigates); audio rows play
 * in-tab via [audioPlayingKey]/[audioIsPlaying]/[audioProgress] owned by the
 * host. [pdfPreviews] injects PDF first-page state for snapshots; on device
 * the row loads it from [SearchRowUi.fileUri].
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
    expandedKey: String? = null,
    onPreviewToggle: (SearchRowUi) -> Unit = {},
    audioPlayingKey: String? = null,
    audioIsPlaying: Boolean = false,
    audioProgress: Float = 0f,
    onAudioToggle: (SearchRowUi) -> Unit = {},
    pdfPreviews: Map<String, PdfPreviewData> = emptyMap(),
) {
    FileSearchTheme {
        var active by remember { mutableStateOf(false) }
        // Stock Scaffold + TopAppBar: Surface supplies the content color (bare
        // Texts rendered black-on-dark without it) and the bar keeps a visible
        // heading above the search field. Search bar + mic is the one action.
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text("FileSearch") }) },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
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
                modifier = Modifier.weight(1f).padding(top = 12.dp).testTag("resultList"),
            ) {
                itemsIndexed(visibleRows, key = { _, row -> row.thumbKey + row.fileName + row.locLabel + row.excerpt }) { i, row ->
                    ResultRow(
                        row = row,
                        isExpanded = expandedKey == row.thumbKey,
                        onThumbClick = { onPreviewToggle(row) },
                        audioIsPlaying = audioPlayingKey == row.thumbKey && audioIsPlaying,
                        audioProgress = if (audioPlayingKey == row.thumbKey) audioProgress else 0f,
                        onAudioToggle = { onAudioToggle(row) },
                        pdfPreview = pdfPreviews[row.thumbKey],
                        onSpeakClick = { onSpeakClick(row) },
                        onOpenResult = { onOpenResult(row) },
                        modifier = Modifier.testTag("row_$i"),
                    )
                }
            }
            if (weakCount > 0) {
                // Secondary fold control: text button so the search bar stays
                // the screen's one primary action.
                TextButton(
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
}

/**
 * Per-kind minimal row (user verdict — no text clutter anywhere, no excerpt):
 * image/video rows ARE the thumbnail (full-bleed, tap expands the preview in
 * place); audio rows ARE the player (play/pause + audio icon + progress, no
 * filename); pdf/text rows are icon + filename only (pdf tap expands its
 * preview pane). Weak-fold/match-count behaviour is engine-side, unchanged.
 * [onSpeakClick]/[onOpenResult] are secondary actions inside the expanded
 * preview (never row clutter): "Read aloud" + "Open file" sit below
 * MediaPreview(...) only when expanded.
 */
@Composable
private fun ResultRow(
    row: SearchRowUi,
    onSpeakClick: () -> Unit,
    onOpenResult: () -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    onThumbClick: () -> Unit = {},
    audioIsPlaying: Boolean = false,
    audioProgress: Float = 0f,
    onAudioToggle: () -> Unit = {},
    pdfPreview: PdfPreviewData? = null,
) {
    // Stock Card defaults: M3 picks container/shape, content color inherited.
    // Device loaders stay unconditional (hook-stability: never call a
    // remember/LaunchedEffect composable from a conditional branch); the
    // injected snapshot [pdfPreview] still wins when present.
    Card(modifier = modifier.fillMaxWidth()) {
        val loadedThumb = rememberImageThumb(row.fileUri)
        val loadedPdf = rememberPdfFirstPage(row.fileUri)
        when (row.kind) {
            "image", "video" -> {
                // Gallery feel: the image IS the row — no filename, no badge,
                // no loc. Tapping expands the preview inline, never navigates.
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("thumb_${row.thumbKey}").clickable(role = Role.Button, onClick = onThumbClick),
                ) {
                    if (loadedThumb != null) {
                        Image(
                            loadedThumb,
                            contentDescription = row.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        )
                    } else {
                        // Deterministic loading tile (device decodes off-Main;
                        // Paparazzi/inspection returns null -> letter tile).
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        ) {
                            ThumbTile(kind = row.kind, thumb = null, contentDescription = null)
                        }
                    }
                }
            }
            "audio" -> {
                // The row IS the player — play/pause + audio icon + progress.
                AudioInlineRow(
                    fileName = row.fileName,
                    isPlaying = audioIsPlaying,
                    progress = audioProgress,
                    onToggle = onAudioToggle,
                    modifier = Modifier.padding(12.dp),
                )
            }
            else -> {
                // Filename + icon ONLY. Only pdf expands (preview pane stays);
                // other kinds have no preview to show.
                val expandable = row.kind == "pdf"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                        .testTag("thumb_${row.thumbKey}")
                        .then(if (expandable) Modifier.clickable(role = Role.Button, onClick = onThumbClick) else Modifier),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        row.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Inline expansion: preview lives inside the row, in the Search tab.
        // Audio needs none — the row already is the player. Text has no pane.
        if (isExpanded && (row.kind == "image" || row.kind == "video" || row.kind == "pdf")) {
            val pdf = pdfPreview ?: loadedPdf
            MediaPreview(
                row = row,
                imageThumb =
                    if (row.kind == "image" || row.kind == "video") {
                        loadedThumb
                    } else {
                        null
                    },
                pdf = pdf,
                audioIsPlaying = audioIsPlaying,
                audioProgress = audioProgress,
                onAudioToggle = onAudioToggle,
                modifier = Modifier.testTag("preview_${row.thumbKey}"),
            )
            // Secondary actions: stranded speak/open callbacks re-surfaced
            // here (never row clutter) — stock TextButtons, host-owned.
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                TextButton(onClick = onSpeakClick, modifier = Modifier.testTag("speak_${row.thumbKey}")) {
                    Text("Read aloud")
                }
                TextButton(onClick = onOpenResult, modifier = Modifier.testTag("open_${row.thumbKey}")) {
                    Text("Open file")
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
    expandedKey: String? = null,
    onPreviewToggle: (SearchRowUi) -> Unit = {},
    audioPlayingKey: String? = null,
    audioIsPlaying: Boolean = false,
    audioProgress: Float = 0f,
    onAudioToggle: (SearchRowUi) -> Unit = {},
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
        expandedKey = expandedKey,
        onPreviewToggle = onPreviewToggle,
        audioPlayingKey = audioPlayingKey,
        audioIsPlaying = audioIsPlaying,
        audioProgress = audioProgress,
        onAudioToggle = onAudioToggle,
    )
}
