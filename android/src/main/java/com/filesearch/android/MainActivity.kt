package com.filesearch.android

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.filesearch.android.ui.FileSearchApp
import com.filesearch.android.ui.KindProgress
import com.filesearch.android.ui.SearchRowUi
import com.filesearch.android.ui.SearchScreen
import com.filesearch.android.ui.SettingsScreenContent
import com.filesearch.android.ui.StatusSheetContent
import java.io.File
import kotlin.concurrent.thread

// v1 shell: bench mode (--ez bench true, see scripts/bench-device.sh) keeps the
// legacy TextView path; normal launch hosts the F020 3-tab FileSearchApp.
// Thin host: hoists UI state + maps it to screen params, forwards callbacks to
// existing engine classes (VoiceService, PowerPolicy, IndexWorker). No
// embed/decode/query/DB logic here — that lives in engine/workers.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("bench", false)) {
            val view = TextView(this)
            view.text = "Benchmark running (one query)…"
            setContentView(view)
            thread(name = "bench", isDaemon = false) {
                val result = try {
                    EmbeddingBench.run(this)
                } catch (t: Throwable) {
                    Log.e(EmbeddingBench.TAG, "bench failed", t)
                    null
                }
                if (result != null) {
                    File(filesDir, EmbeddingBench.RESULT_NAME).writeText(result.toString(2))
                    Log.i(EmbeddingBench.TAG, "RESULT " + result.toString())
                }
                runOnUiThread {
                    view.text = if (result != null) {
                        val mods = result.optJSONObject("modalities")
                        fun short(m: String): String {
                            val o = mods?.optJSONObject(m) ?: return "$m=?"
                            return if (o.optBoolean("ok")) {
                                "$m=${o.optString("backend")}/${o.optLong("inferMs")}ms"
                            } else {
                                "$m=FAIL"
                            }
                        }
                        "text: ${short("text")} image: ${short("image")} audio: ${short("audio")} " +
                            "rss=${result.optLong("rssKb")}kB"
                    } else {
                        "Benchmark FAILED (see logcat)"
                    }
                }
            }
            return
        }
        setContent { HostApp() }
    }

    @Composable
    private fun HostApp() {
        val voice = remember { VoiceService(this) }
        var query by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var chargerOnly by remember { mutableStateOf(false) }
        var wifiOnlyModels by remember { mutableStateOf(true) }
        var toggles by remember { mutableStateOf(VoiceService.defaultToggles()) }
        var userPaused by remember { mutableStateOf(false) }
        // shortcut: plain display names only — real SAF picker + persisted permissions land later.
        var folders by remember { mutableStateOf(listOf("Downloads", "Pictures", "Documents")) }
        val costs = remember { MeasuredCosts(emptyMap()) }

        val pauseCheck = remember(chargerOnly) { IndexWorker.devicePauseCheck(this, chargerOnly) }
        val deviceReason = pauseCheck()
        val paused = userPaused || deviceReason != PauseReason.OK
        val pauseLabel = when {
            userPaused -> "paused by you"
            deviceReason != PauseReason.OK -> deviceReason.name
            else -> null
        }

        FileSearchApp(
            search = {
                SearchScreen(
                    voice = voice,
                    query = query,
                    onQueryChange = { query = it },
                    // shortcut: empty until live IndexStore wiring lands (shared-module extract unblocks the :app import).
                    visibleRows = emptyList<SearchRowUi>(),
                    weakCount = 0,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onHeard = { query = it },
                    onOpenResult = {}, // shortcut: no-op until result-open wiring lands.
                )
            },
            status = {
                StatusSheetContent(
                    // shortcut: live IndexWorker progress wiring lands with the status WorkManager observer.
                    progress = emptyList<KindProgress>(),
                    remainingLabel = formatRemaining(costs.estimateRemainingMs(emptyMap())),
                    paused = paused,
                    pauseLabel = pauseLabel,
                    onPauseToggle = { userPaused = !userPaused },
                    onDismiss = {}, // shortcut: no-op until sheet-dismiss wiring lands.
                )
            },
            settings = {
                SettingsScreenContent(
                    folders = folders,
                    excludedTypes = emptyList(),
                    chargerOnly = chargerOnly,
                    wifiOnlyModels = wifiOnlyModels,
                    toggles = toggles,
                    sttProviderLabel = "Google STT",
                    ttsProviderLabel = "Google TTS",
                    // shortcut: live SettingsState.validate wiring lands with the shared-module extract.
                    validationErrors = emptyList(),
                    wipeSummary = "Remove app data deletes downloaded models, the search index, " +
                        "logs, cloud transcripts, and stored voice keys.",
                    onPickFolder = {}, // shortcut: no-op until SAF picker wiring lands.
                    onChargerOnlyChange = { chargerOnly = it },
                    onWifiOnlyChange = { wifiOnlyModels = it },
                    onCloudSttChange = { toggles = toggles.copy(cloudStt = it) },
                    onCloudTtsChange = { toggles = toggles.copy(cloudTts = it) },
                    // shortcut: keys-only wipe for now — live index/model/log deletion lands with wiring.
                    onWipe = { runCatching { voice.clearKeys() } },
                )
            },
        )
    }
}

// shortcut: hand-mirrored from app/UiState.kt formatRemaining — unify into a shared module when the build allows it.
private fun formatRemaining(ms: Long): String =
    when {
        ms <= 0 -> "finishing…"
        ms < 60_000 -> "≈${ms / 1_000} sec left"
        ms < 3_600_000 -> "≈${ms / 60_000} min left"
        else -> "≈${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min left"
    }
