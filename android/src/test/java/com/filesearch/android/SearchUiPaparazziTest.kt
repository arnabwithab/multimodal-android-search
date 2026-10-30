package com.filesearch.android

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.filesearch.android.ui.KindProgress
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
