package com.filesearch.android

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.filesearch.android.ui.KindProgress
import com.filesearch.android.ui.PdfPreviewData
import com.filesearch.android.ui.SearchScreenContent
import com.filesearch.android.ui.SettingsScreenContent
import com.filesearch.android.ui.StatusSheetContent
import com.filesearch.android.ui.defaultVoiceToggles
import org.junit.Rule
import org.junit.Test

/**
 * F020 Paparazzi snapshots, light mode (JVM, no device). Night mode lives in
 * SearchUiNightPaparazziTest — Paparazzi allows one @Rule per class.
 * Fixtures mirror engine UiState shapes (strong/weak fold, «marked» excerpts,
 * p.5 / 02:31 loc labels).
 */
class SearchUiPaparazziTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_6,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun searchList() {
        paparazzi.snapshot {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(PaparazziFixtures.strong, PaparazziFixtures.audio),
                weakCount = 0,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
    }

    @Test
    fun searchFoldedWeak() {
        paparazzi.snapshot {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(PaparazziFixtures.strong),
                weakCount = 1,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
    }

    @Test
    fun searchExpandedWeak() {
        paparazzi.snapshot {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(PaparazziFixtures.strong, PaparazziFixtures.weak),
                weakCount = 1,
                expanded = true,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
    }

    @Test
    fun searchListening() {
        paparazzi.snapshot {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(PaparazziFixtures.strong, PaparazziFixtures.audio),
                weakCount = 0,
                expanded = false,
                onToggleExpanded = {},
                listening = true,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
    }

    @Test
    fun searchEmpty() {
        paparazzi.snapshot {
            SearchScreenContent(
                query = "zzz",
                onQueryChange = {},
                visibleRows = emptyList(),
                weakCount = 0,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
    }

    @Test
    fun searchImageThumbExpanded() {
        val image = PaparazziFixtures.weak.copy(fileUri = "content://media/external/images/media/2")
        paparazzi.snapshot {
            SearchScreenContent(
                query = "beach",
                onQueryChange = {},
                visibleRows = listOf(image),
                weakCount = 0,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
                expandedKey = image.thumbKey,
                onPreviewToggle = {},
            )
        }
    }

    @Test
    fun searchPdfPreviewPane() {
        val pdf = PaparazziFixtures.strong
        paparazzi.snapshot {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(pdf),
                weakCount = 0,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
                expandedKey = pdf.thumbKey,
                onPreviewToggle = {},
                pdfPreviews =
                    mapOf(
                        pdf.thumbKey to
                            PdfPreviewData(
                                text = "where they talk about «sleep» cycles — page 5 of 12, " +
                                    "full text layer from the indexed file",
                            ),
                    ),
            )
        }
    }

    @Test
    fun searchAudioPlaying() {
        val audio = PaparazziFixtures.audio.copy(fileUri = "content://media/external/audio/media/3")
        paparazzi.snapshot {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(audio),
                weakCount = 0,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
                expandedKey = audio.thumbKey,
                onPreviewToggle = {},
                audioPlayingKey = audio.thumbKey,
                audioIsPlaying = true,
                audioProgress = 0.4f,
                onAudioToggle = {},
            )
        }
    }

    @Test
    fun statusSheet() {
        paparazzi.snapshot {
            StatusSheetContent(
                progress =
                    listOf(
                        KindProgress("image", 120, 200),
                        KindProgress("pdf", 30, 45),
                        KindProgress("audio", 5, 60),
                    ),
                remainingLabel = "≈3 min left",
                paused = false,
                pauseLabel = null,
                onPauseToggle = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun settings() {
        paparazzi.snapshot {
            SettingsScreenContent(
                folders = listOf("Downloads", "Pictures"),
                excludedTypes = listOf("video"),
                chargerOnly = true,
                wifiOnlyModels = true,
                toggles = defaultVoiceToggles(),
                sttProviderLabel = "Google STT",
                ttsProviderLabel = "Google TTS",
                validationErrors = emptyList(),
                wipeSummary =
                    "Remove app data deletes downloaded models, the search index, " +
                        "logs, cloud transcripts, and stored voice keys.",
                onPickFolder = {},
                onChargerOnlyChange = {},
                onWifiOnlyChange = {},
                onCloudSttChange = {},
                onCloudTtsChange = {},
                onWipe = {},
            )
        }
    }
}
