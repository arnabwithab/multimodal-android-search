package com.filesearch.android

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.filesearch.android.ui.KindProgress
import com.filesearch.android.ui.SearchRowUi
import com.filesearch.android.ui.SearchScreenContent
import com.filesearch.android.ui.SettingsScreenContent
import com.filesearch.android.ui.StatusSheetContent
import com.filesearch.android.ui.defaultVoiceToggles
import org.junit.Rule
import org.junit.Test

/** Shared snapshot fixtures mirroring engine UiState shapes. */
internal object PaparazziFixtures {
    val strong =
        SearchRowUi(
            fileName = "sleep-study.pdf",
            kind = "pdf",
            excerpt = "where they talk about «sleep» cycles",
            locLabel = "p.5",
            thumbKey = "pdf:1",
        )
    val weak =
        SearchRowUi(
            fileName = "beach-day.jpg",
            kind = "image",
            excerpt = "a «sleep»y cat on the sand",
            locLabel = "",
            thumbKey = "img:2",
        )
    val audio =
        SearchRowUi(
            fileName = "lecture-03.mp3",
            kind = "audio",
            excerpt = "«sleep» and memory consolidation",
            locLabel = "02:31",
            thumbKey = "aud:3",
        )
}

/** F020 Paparazzi snapshots, night mode (one @Rule per class — Paparazzi limit). */
class SearchUiNightPaparazziTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT),
            theme = "android:Theme.Material.NoActionBar",
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
    fun statusSheetPaused() {
        paparazzi.snapshot {
            StatusSheetContent(
                progress = listOf(KindProgress("image", 120, 200)),
                remainingLabel = "≈3 min left",
                paused = true,
                pauseLabel = "charger-only, off charger",
                onPauseToggle = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun settingsWithError() {
        paparazzi.snapshot {
            SettingsScreenContent(
                folders = emptyList(),
                excludedTypes = emptyList(),
                chargerOnly = false,
                wifiOnlyModels = true,
                toggles = defaultVoiceToggles().copy(cloudStt = true),
                sttProviderLabel = "Whisper",
                ttsProviderLabel = "ElevenLabs",
                validationErrors = listOf("Pick at least one folder to watch."),
                wipeSummary = "Remove app data deletes models, index, logs, transcripts, keys.",
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
