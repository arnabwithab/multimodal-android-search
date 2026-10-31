package com.filesearch.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filesearch.android.ui.SearchRowUi
import com.filesearch.android.ui.SearchScreenContent
import com.filesearch.android.ui.SettingsScreenContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F020 instrumented skeleton (phone-only, NOT run on JVM — no adb here).
 * Covers: list renders, weak fold expands, settings toggles default OFF.
 * Run on device later via `:android:connectedAndroidTest` (make test-device).
 */
@RunWith(AndroidJUnit4::class)
class SearchUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val strong =
        SearchRowUi(
            fileName = "sleep-study.pdf",
            kind = "pdf",
            excerpt = "where they talk about «sleep» cycles",
            locLabel = "p.5",
            thumbKey = "pdf:1",
        )
    private val weak =
        SearchRowUi(
            fileName = "beach-day.jpg",
            kind = "image",
            excerpt = "a «sleep»y cat on the sand",
            locLabel = "",
            thumbKey = "img:2",
        )

    @Test
    fun listRenders() {
        compose.setContent {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = listOf(strong),
                weakCount = 1,
                expanded = false,
                onToggleExpanded = {},
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
        compose.onNodeWithText("sleep-study.pdf").assertIsDisplayed()
        compose.onNodeWithText("weaker matches (1)").assertIsDisplayed()
        // Image rows render no filename — the thumb tag proves the weak row is hidden.
        compose.onAllNodesWithTag("thumb_img:2").assertCountEquals(0)
    }

    @Test
    fun foldExpands() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            SearchScreenContent(
                query = "sleep",
                onQueryChange = {},
                visibleRows = if (expanded.value) listOf(strong, weak) else listOf(strong),
                weakCount = 1,
                expanded = expanded.value,
                onToggleExpanded = { expanded.value = true },
                listening = false,
                onMicClick = {},
                onSpeakClick = {},
                onOpenResult = {},
            )
        }
        compose.onNodeWithTag("weakFold").performClick()
        // Image rows ARE the thumbnail — no filename text renders.
        compose.onNodeWithTag("thumb_img:2").assertIsDisplayed()
    }

    @Test
    fun settingsTogglesDefaultOff() {
        compose.setContent {
            SettingsScreenContent(
                folders = listOf("Downloads"),
                excludedTypes = emptyList(),
                chargerOnly = false,
                wifiOnlyModels = true,
                toggles = VoiceToggles(cloudStt = false, cloudTts = false),
                sttProviderLabel = "Google STT",
                ttsProviderLabel = "Google TTS",
                validationErrors = emptyList(),
                wipeSummary = "wipes models, index, logs, transcripts, keys.",
                onPickFolder = {},
                onChargerOnlyChange = {},
                onWifiOnlyChange = {},
                onCloudSttChange = {},
                onCloudTtsChange = {},
                onWipe = {},
            )
        }
        // Both cloud toggles OFF on install (design §7); switches assert off.
        compose.onNodeWithTag("cloudSttSwitch").assertIsOff()
        compose.onNodeWithTag("cloudTtsSwitch").assertIsOff()
    }
}
