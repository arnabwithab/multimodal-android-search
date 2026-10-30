package com.filesearch.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filesearch.android.ui.FileSearchApp
import com.filesearch.android.ui.SettingsScreenContent
import com.filesearch.android.ui.StatusSheetContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every-button audit: each interactive element either does its job or its
 * label promises only what it does. No silent no-ops.
 */
@RunWith(AndroidJUnit4::class)
class ButtonAuditTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun closeButtonIsHonestBackToSearch() {
        var dismissed = false
        compose.setContent {
            StatusSheetContent(
                progress = emptyList(),
                remainingLabel = "finishing…",
                paused = false,
                pauseLabel = null,
                onPauseToggle = {},
                onDismiss = { dismissed = true },
            )
        }
        // Close lives on a tab, not a sheet — the label must say where it goes.
        compose.onNodeWithText("Back to Search").assertIsDisplayed()
        compose.onNodeWithText("Back to Search").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun pauseResumeToggleFiresCallback() {
        var toggles = 0
        compose.setContent {
            StatusSheetContent(
                progress = emptyList(),
                remainingLabel = "indexing 1/2…",
                paused = false,
                pauseLabel = null,
                onPauseToggle = { toggles++ },
                onDismiss = {},
            )
        }
        compose.onNodeWithTag("pauseButton").performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun fileSearchAppControlledTabNavigates() {
        val tab = mutableStateOf(1)
        compose.setContent {
            FileSearchApp(
                search = { },
                status = { },
                settings = { },
                tab = tab.value,
                onTabChange = { tab.value = it },
            )
        }
        // Controlled from outside: host drives tab, bar reports taps back.
        compose.onNodeWithText("Search").performClick()
        assertEquals(0, tab.value)
    }

    @Test
    fun validationErrorsShowLoudly() {
        compose.setContent {
            SettingsScreenContent(
                folders = emptyList(),
                excludedTypes = emptyList(),
                chargerOnly = false,
                wifiOnlyModels = true,
                toggles = VoiceToggles(cloudStt = false, cloudTts = false),
                sttProviderLabel = "Google STT",
                ttsProviderLabel = "Google TTS",
                validationErrors = listOf("Cloud transcription needs a key (voice_cloud_stt_api_key)."),
                wipeSummary = "wipes models, index, logs, transcripts, keys.",
                onPickFolder = {},
                onChargerOnlyChange = {},
                onWifiOnlyChange = {},
                onCloudSttChange = {},
                onCloudTtsChange = {},
                onWipe = {},
            )
        }
        compose.onNodeWithTag("validationErrors").assertIsDisplayed()
        compose.onNodeWithText("Cloud transcription needs a key (voice_cloud_stt_api_key).").assertIsDisplayed()
    }

    @Test
    fun purgeTranscriptRowsKeepsPhotoSegments() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(DeviceIndexStore.DB_NAME)
        val store = DeviceIndexStore(ctx)
        try {
            val photo = store.upsertFile("/pics/a.jpg", "image", 10L, 1L)
            store.addSegment(photo, "image", "image", "a photo", ByteArray(4), "a.jpg", "a photo", "a.jpg")
            val audio = store.upsertFile("/audio/b.m4a", "audio", 20L, 2L)
            store.addSegment(audio, "chunk", "audio", "hello world", ByteArray(4), "b.m4a", "hello world", "b.m4a")
            assertEquals(1, store.purgeTranscriptRows())
            assertTrue(store.matchTerms("hello").isEmpty())
            assertEquals(listOf(1L), store.matchTerms("photo").map { it })
            assertEquals(2, store.countFiles())
        } finally {
            store.close()
            ctx.deleteDatabase(DeviceIndexStore.DB_NAME)
        }
    }
}
