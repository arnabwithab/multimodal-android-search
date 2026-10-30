package com.filesearch.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.filesearch.android.app.formatRemaining
import com.filesearch.android.ui.FileSearchApp
import com.filesearch.android.ui.KindProgress
import com.filesearch.android.ui.SearchRowUi
import com.filesearch.android.ui.SearchScreen
import com.filesearch.android.ui.SettingsScreenContent
import com.filesearch.android.ui.StatusSheetContent
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

// v1 shell: bench mode (--ez bench true, see scripts/bench-device.sh) keeps the
// legacy TextView path; normal launch hosts the F020 3-tab FileSearchApp.
// Thin host: hoists UI state + maps it to screen params, forwards callbacks to
// existing engine classes (VoiceService, PowerPolicy, IndexWorker). No
// embed/decode/query/DB logic here — that lives in engine/workers.
class MainActivity : ComponentActivity() {
    /** Media-readiness for the index-now corpus (null = not asked yet). */
    private val mediaReady = MutableStateFlow<Boolean?>(null)

    private fun mediaPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private val mediaAsk =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            mediaReady.value = granted
        }

    /** Enqueues one index-now run (shared by the button, auto-start, and the indexnow seam). */
    private fun requestIndexNow() {
        val req =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(IndexWorker.IN_MODE, IndexWorker.MODE_INDEX_NOW)
                        .putInt(IndexWorker.IN_MAX_FILES, INDEX_NOW_CAP)
                        .build(),
                )
                .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            IndexWorker.UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

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
        if (checkSelfPermission(mediaPerm()) == PackageManager.PERMISSION_GRANTED) {
            mediaReady.value = true
        } else {
            mediaAsk.launch(mediaPerm())
        }
        // Verification seam (like bench mode): headless runs enqueue via adb.
        Log.i(TAG, "onCreate indexnow=" + intent.getBooleanExtra("indexnow", false))
        if (intent.getBooleanExtra("indexnow", false)) requestIndexNow()
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
        // F022: live index wiring (progress + Index-now live here; the button moves
        // into StatusSheetContent as an onIndexNow callback when the sibling wires it).
        var indexDone by remember { mutableStateOf(0) }
        var indexTotal by remember { mutableStateOf(0) }
        var indexState by remember { mutableStateOf("not started") }

        fun enqueueIndexNow() {
            requestIndexNow()
            indexState = "starting…"
        }

        // Auto-start once on first launch (SharedPreferences flag), gated on
        // media permission; the button re-runs any time.
        var mediaOk by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) {
            mediaReady.collect { mediaOk = it }
        }
        LaunchedEffect(mediaOk) {
            if (mediaOk == true) {
                val prefs = getSharedPreferences(PREFS_F022, MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_AUTOSTART_DONE, false)) {
                    prefs.edit().putBoolean(KEY_AUTOSTART_DONE, true).apply()
                    enqueueIndexNow()
                }
            } else if (mediaOk == false) {
                indexState = "needs Photos access — grant in Settings, then Index now"
            }
        }

        // Poll the unique work (no new deps: WorkManager LiveData needs lifecycle-runtime-compose).
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    val infos =
                        withContext(Dispatchers.IO) {
                            WorkManager.getInstance(this@MainActivity)
                                .getWorkInfosForUniqueWork(IndexWorker.UNIQUE_WORK).get()
                        }
                    val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: infos.lastOrNull()
                    if (info != null) {
                        val prog = info.progress
                        val out = info.outputData
                        when (info.state) {
                            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                                indexDone = prog.getInt(IndexWorker.PROG_DONE, 0)
                                indexTotal = prog.getInt(IndexWorker.PROG_TOTAL, 0)
                                indexState = if (indexTotal > 0) "indexing…" else "starting…"
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                indexDone = out.getInt(IndexWorker.OUT_INDEXED, indexDone)
                                indexTotal = out.getInt(IndexWorker.OUT_TOTAL, indexTotal)
                                val pause = out.getString(IndexWorker.OUT_PAUSE_REASON)
                                indexState = if (pause != null && pause != PauseReason.OK.name) "paused: $pause" else "done"
                            }
                            WorkInfo.State.FAILED -> {
                                indexState = "failed: ${(out.getString(IndexWorker.OUT_ERROR) ?: "unknown").take(120)}"
                            }
                            WorkInfo.State.CANCELLED -> {
                                indexState = "cancelled"
                            }
                        }
                        if (info.state.isFinished) break
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "index poll: ${t.javaClass.simpleName}")
                }
                delay(1000)
            }
        }

        val kindProgress =
            if (indexTotal > 0) listOf(KindProgress(kind = "image", done = indexDone.toLong(), total = indexTotal.toLong())) else emptyList()
        val remainingLabel =
            when {
                indexTotal <= 0 && indexState == "not started" -> "never indexed — tap Index now"
                indexState == "indexing…" || indexState == "starting…" -> "indexing $indexDone/$indexTotal…"
                // Selected-photos grant with nothing visible: say so, don't print "finishing…".
                indexState == "done" && indexTotal == 0 -> "no photos found — allow full Photos access, then Index now"
                indexState == "done" -> formatRemaining(0)
                else -> indexState
            }

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
                    // shortcut: empty until live search wiring reads DeviceIndexStore (F023+).
                    visibleRows = emptyList<SearchRowUi>(),
                    weakCount = 0,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onHeard = { query = it },
                    onOpenResult = {}, // shortcut: no-op until result-open wiring lands.
                )
            },
            status = {
                // F022: Index-now lives here until the sibling adds the StatusSheet onIndexNow callback.
                Column {
                    Button(onClick = { enqueueIndexNow() }, modifier = Modifier.testTag("indexNowButton")) {
                        Text("Index now")
                    }
                    Text(indexState, modifier = Modifier.padding(top = 4.dp))
                    StatusSheetContent(
                        progress = kindProgress,
                        remainingLabel = remainingLabel,
                        paused = paused,
                        pauseLabel = pauseLabel,
                        onPauseToggle = { userPaused = !userPaused },
                        onDismiss = {}, // shortcut: no-op until sheet-dismiss wiring lands.
                    )
                }
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
                    // shortcut: live SettingsState.validate wiring is F023+ (engine is importable now).
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

// F022 constants: first-launch auto-start flag + first-corpus cap (Pictures only, newest first).
private const val PREFS_F022 = "f022"
private const val KEY_AUTOSTART_DONE = "autostart_done"
private const val INDEX_NOW_CAP = 50
private const val TAG = "MainActivity"
