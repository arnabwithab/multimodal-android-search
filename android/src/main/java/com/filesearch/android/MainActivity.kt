package com.filesearch.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.filesearch.android.app.formatRemaining
import com.filesearch.android.app.SettingsState
import com.filesearch.android.engine.Embedder
import com.filesearch.android.ui.FileSearchApp
import com.filesearch.android.ui.SearchRowUi
import com.filesearch.android.ui.progressFraction
import com.filesearch.android.ui.SearchScreen
import com.filesearch.android.ui.SettingsScreenContent
import com.filesearch.android.ui.StatusSheetContent
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    private fun requestIndexNow(chargerOnly: Boolean = false) {
        val req =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(IndexWorker.IN_MODE, IndexWorker.MODE_INDEX_NOW)
                        .putBoolean(IndexWorker.IN_CHARGER_ONLY, chargerOnly)
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
        // Draw behind system bars (transparent) — FileSearchApp pads below the
        // status bar itself; without this the content overlaps opaque chrome.
        enableEdgeToEdge()
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
        if (intent.getBooleanExtra("indexnow", false)) {
            requestIndexNow(getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).getBoolean(KEY_CHARGER, false))
        }
        setContent { HostApp() }
    }

    @Composable
    private fun HostApp() {
        val settingsPrefs = remember { getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE) }
        val voice = remember { VoiceService(this) }
        var query by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var chargerOnly by remember { mutableStateOf(settingsPrefs.getBoolean(KEY_CHARGER, false)) }
        var wifiOnlyModels by remember { mutableStateOf(settingsPrefs.getBoolean(KEY_WIFI, true)) }
        var toggles by remember {
            mutableStateOf(
                VoiceToggles(
                    cloudStt = settingsPrefs.getBoolean(KEY_STT, false),
                    cloudTts = settingsPrefs.getBoolean(KEY_TTS, false),
                ),
            )
        }
        var userPaused by remember { mutableStateOf(false) }
        var tab by rememberSaveable { mutableStateOf(0) }
        var cloudKeyError by remember { mutableStateOf<String?>(null) }
        // SAF tree URIs, persisted + permission-persisted on pick; empty until
        // the user adds one. Auto-index of these trees is F023 — this list is
        // the remembered watch-list, picked + listed on-device today.
        val initialFolders: List<String> =
            settingsPrefs.getStringSet(KEY_FOLDERS, null)?.sorted() ?: emptyList()
        var folders by remember { mutableStateOf(initialFolders) }
        val folderPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val updated = (folders + uri.toString()).distinct().sorted()
                folders = updated
                settingsPrefs.edit().putStringSet(KEY_FOLDERS, updated.toSet()).apply()
            }
        // F022: live index wiring (progress + Index-now button live in this
        // status tab above the sheet; the sheet owns Pause/Resume + Back).
        var indexDone by remember { mutableStateOf(0) }
        var indexTotal by remember { mutableStateOf(0) }
        var imgDone by remember { mutableStateOf(0) }
        var imgTotal by remember { mutableStateOf(0) }
        var pdfDone by remember { mutableStateOf(0) }
        var pdfTotal by remember { mutableStateOf(0) }
        // PDF page segments landed (OUT_PDF_INDEXED). pdfDone/pdfTotal stay in
        // file units; this one is segments — the done label shows both.
        var pdfSegs by remember { mutableStateOf(0) }
        var indexState by remember { mutableStateOf("not started") }
        // Poll-loop run key: each enqueue bumps this, restarting the
        // LaunchedEffect below so a fresh terminal break still observes the
        // next Resume run.
        var enqueueCounter by remember { mutableStateOf(0) }
        // F024: live search rows (DeviceSearch over DeviceIndexStore + LiteRT query
        // embed; kindHint stays null until kind-hint parsing lands in F023+).
        var strongRows by remember { mutableStateOf<List<SearchRowUi>>(emptyList()) }
        var weakRows by remember { mutableStateOf<List<SearchRowUi>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        val searchStore = remember { DeviceIndexStore(this) }
        val searchBackend = remember { LitertEmbedBackend(this) }
        val searchEmbedder = remember { Embedder(searchBackend, LitertEmbedBackend.FP_DIM) }
        DisposableEffect(Unit) {
            onDispose {
                runCatching { searchStore.close() }
                runCatching { searchBackend.close() }
            }
        }
        // Inline preview state: expanded row key (tap thumb toggles, stays in
        // the Search tab) + single in-tab audio player. One ExoPlayer: starting
        // another row stops the current one; release on dispose — no
        // background playback, in-tab only.
        var previewKey by remember { mutableStateOf<String?>(null) }
        var audioKey by remember { mutableStateOf<String?>(null) }
        var audioPlaying by remember { mutableStateOf(false) }
        var audioProgress by remember { mutableStateOf(0f) }
        val player =
            remember {
                ExoPlayer.Builder(this).build().apply {
                    addListener(
                        object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                audioPlaying = isPlaying
                            }

                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_ENDED) {
                                    audioKey = null
                                    audioProgress = 0f
                                }
                            }
                        },
                    )
                }
            }
        DisposableEffect(player) {
            onDispose { runCatching { player.release() } }
        }
        // In-tab audio only: leaving the Search tab pauses (never background
        // playback); backgrounding the app pauses via the onStop observer.
        DisposableEffect(tab) {
            onDispose { runCatching { player.pause() } }
        }
        val appLifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(appLifecycle, player) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) runCatching { player.pause() }
                }
            appLifecycle.addObserver(observer)
            onDispose { appLifecycle.removeObserver(observer) }
        }
        // Progress poll while playing (Main; player reads are cheap getters).
        LaunchedEffect(audioKey, audioPlaying) {
            while (audioPlaying) {
                val dur = player.duration.coerceAtLeast(0L)
                audioProgress = progressFraction(player.currentPosition, dur)
                delay(500)
            }
        }
        fun toggleAudio(row: SearchRowUi) {
            // Same row paused: resume from position, don't restart from zero.
            if (audioKey == row.thumbKey) {
                if (player.isPlaying) player.pause() else player.play()
                return
            }
            if (row.fileUri.isBlank()) {
                Toast.makeText(this@MainActivity, "No audio to play", Toast.LENGTH_SHORT).show()
                return
            }
            runCatching {
                player.stop()
                player.clearMediaItems()
                player.setMediaItem(MediaItem.fromUri(Uri.parse(row.fileUri)))
                player.prepare()
                player.play()
                audioKey = row.thumbKey
                audioProgress = 0f
            }.onFailure {
                Toast.makeText(this@MainActivity, "Can't play this audio", Toast.LENGTH_SHORT).show()
            }
        }
        // Debounced query path (typing + voice onHeard both land in query).
        // Off Main: embed + DB read run on IO; LiteRT stays single-flight
        // behind its own mutex. Stale guard: only the latest query paints.
        // shortcut: search opens its own LiteRT engine; a concurrent
        // index-now run holds a second one (~700MB RSS each) — serialize
        // behind one app-scoped backend when the corpus grows past photos.
        LaunchedEffect(query) {
            if (query.isBlank()) {
                strongRows = emptyList()
                weakRows = emptyList()
                searching = false
                return@LaunchedEffect
            }
            searching = true
            delay(350)
            val q = query
            val res =
                withContext(Dispatchers.IO) {
                    runCatching { DeviceSearch.search(q, searchStore, searchEmbedder) }.getOrElse { t ->
                        if (t is CancellationException) throw t
                        null
                    }
                }
            if (q == query) {
                if (res != null) {
                    strongRows = res.strong
                    weakRows = res.weak
                }
                searching = false
            }
        }

        fun enqueueIndexNow() {
            requestIndexNow(chargerOnly)
            indexState = "starting…"
            enqueueCounter++
        }

        // Result-open: thumbKey is "$kind:$segId" (DeviceSearch.toRow); the
        // stored files.path IS the MediaStore content URI, so ACTION_VIEW
        // opens it directly — no FileProvider needed. DB read off Main;
        // unresolvable row or no viewer app -> toast, never a crash.
        val openScope = rememberCoroutineScope()
        fun openResult(row: SearchRowUi) {
            openScope.launch {
                val uri =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val segId = row.thumbKey.substringAfterLast(':').toLong()
                            searchStore.readSegments().firstOrNull { it.id == segId }?.path?.let(Uri::parse)
                        }.getOrNull()
                    }
                if (uri == null) {
                    Toast.makeText(this@MainActivity, "No app can open this file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val mime = runCatching { this@MainActivity.contentResolver.getType(uri) }.getOrNull() ?: "image/*"
                try {
                    this@MainActivity.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No app can open this file", Toast.LENGTH_SHORT).show()
                }
            }
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
        // Breaks on terminal state; each enqueue bumps enqueueCounter which
        // restarts this effect, so a post-Pause Resume run is still observed.
        LaunchedEffect(enqueueCounter) {
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
                                imgDone = prog.getInt(IndexWorker.PROG_IMAGE_DONE, 0)
                                imgTotal = prog.getInt(IndexWorker.PROG_IMAGE_TOTAL, 0)
                                pdfDone = prog.getInt(IndexWorker.PROG_PDF_DONE, 0)
                                pdfTotal = prog.getInt(IndexWorker.PROG_PDF_TOTAL, 0)
                                indexState = if (indexTotal > 0) "indexing…" else "starting…"
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                indexDone = out.getInt(IndexWorker.OUT_INDEXED, indexDone)
                                indexTotal = out.getInt(IndexWorker.OUT_TOTAL, indexTotal)
                                imgDone = out.getInt(IndexWorker.OUT_INDEXED, imgDone)
                                imgTotal = out.getInt(IndexWorker.OUT_TOTAL, imgTotal)
                                pdfDone = out.getInt(IndexWorker.OUT_PDF_FILES_DONE, pdfDone)
                                pdfSegs = out.getInt(IndexWorker.OUT_PDF_INDEXED, pdfSegs)
                                pdfTotal = out.getInt(IndexWorker.OUT_PDF_TOTAL, pdfTotal)
                                val pause = out.getString(IndexWorker.OUT_PAUSE_REASON)
                                indexState = if (pause != null && pause != PauseReason.OK.name) "paused: $pause" else "done"
                                break
                            }
                            WorkInfo.State.FAILED -> {
                                indexState = "failed: ${(out.getString(IndexWorker.OUT_ERROR) ?: "unknown").take(120)}"
                                break
                            }
                            WorkInfo.State.CANCELLED -> {
                                indexState = "cancelled"
                                break
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "index poll: ${t.javaClass.simpleName}")
                }
                delay(1000)
            }
        }

        // F025 full corpus: per-kind image + pdf rows (honest counts either way).
        val kindProgress =
            if (indexTotal > 0 || imgTotal > 0 || pdfTotal > 0) {
                FullCorpus.corpusKindRows(imgDone.toLong(), imgTotal.toLong(), pdfDone.toLong(), pdfTotal.toLong())
            } else {
                emptyList()
            }
        val remainingLabel =
            when {
                indexTotal <= 0 && indexState == "not started" -> "never indexed — tap Index now"
                indexState == "indexing…" || indexState == "starting…" -> "indexing $indexDone/$indexTotal…"
                // Selected-photos grant with nothing visible: say so, don't print "finishing…".
                indexState == "done" && indexTotal == 0 -> "no photos found — allow full Photos access, then Index now"
                indexState == "done" && pdfTotal > 0 -> "$imgTotal photos + $pdfSegs pdf pages in $pdfTotal pdfs"
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

        // Pause actually pauses: cancel the unique work (partial progress is
        // kept in the DB). Resume clears the flag and re-enqueues; device
        // guards re-apply inside the worker, so a still-hot phone pauses again.
        fun togglePause() {
            if (paused) {
                userPaused = false
                enqueueIndexNow()
            } else {
                // Device pause isn't the user's choice — only claim it when the device is OK.
                if (pauseCheck() == PauseReason.OK) userPaused = true
                WorkManager.getInstance(this).cancelUniqueWork(IndexWorker.UNIQUE_WORK)
            }
        }

        // Cloud ON without a stored key fails loudly at toggle time (names the
        // missing key, toggle stays OFF). Cloud OFF purges transcript rows.
        // Keystore/SQLite run on IO via openScope; toasts + state stay on Main.
        fun keyMissing(load: () -> String?): Boolean = runCatching { load() }.getOrNull().isNullOrBlank()
        fun setCloudStt(on: Boolean) {
            if (on) {
                openScope.launch {
                    val missing = withContext(Dispatchers.IO) { keyMissing(voice::loadSttKey) }
                    if (missing) {
                        cloudKeyError = "Cloud transcription needs a key: save one under " +
                            "\"${VoiceService.PREFS_NAME}\" -> \"${VoiceService.KEY_STT_API_KEY}\"."
                        Toast.makeText(this@MainActivity, "Cloud STT needs a key — toggle left OFF", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    cloudKeyError = null
                    toggles = toggles.copy(cloudStt = true)
                    settingsPrefs.edit().putBoolean(KEY_STT, true).apply()
                }
                return
            }
            cloudKeyError = null
            toggles = toggles.copy(cloudStt = false)
            settingsPrefs.edit().putBoolean(KEY_STT, false).apply()
            openScope.launch {
                withContext(Dispatchers.IO) { runCatching { searchStore.purgeTranscriptRows() } }
            }
        }
        fun setCloudTts(on: Boolean) {
            if (on) {
                openScope.launch {
                    val missing = withContext(Dispatchers.IO) { keyMissing(voice::loadTtsKey) }
                    if (missing) {
                        cloudKeyError = "Cloud read-aloud needs a key: save one under " +
                            "\"${VoiceService.PREFS_NAME}\" -> \"${VoiceService.KEY_TTS_API_KEY}\"."
                        Toast.makeText(this@MainActivity, "Cloud TTS needs a key — toggle left OFF", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    cloudKeyError = null
                    toggles = toggles.copy(cloudTts = true)
                    settingsPrefs.edit().putBoolean(KEY_TTS, true).apply()
                }
                return
            }
            cloudKeyError = null
            toggles = toggles.copy(cloudTts = false)
            settingsPrefs.edit().putBoolean(KEY_TTS, false).apply()
            openScope.launch {
                withContext(Dispatchers.IO) { runCatching { searchStore.purgeTranscriptRows() } }
            }
        }

        // Full wipe: cancel work, clear the index (transcript rows included),
        // delete model packs, voice keys, and prefs. Loaded backends keep
        // working until process death; a restart reports missing-model honestly.
        fun wipeAll() {
            WorkManager.getInstance(this).cancelUniqueWork(IndexWorker.UNIQUE_WORK)
            openScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { searchStore.resetAll() }
                    this@MainActivity.filesDir.listFiles { f -> f.extension == "litertlm" }
                        ?.forEach { runCatching { it.delete() } }
                    runCatching { voice.clearKeys() }
                }
            }
            getSharedPreferences(PREFS_F022, MODE_PRIVATE).edit().clear().apply()
            settingsPrefs.edit().clear().apply()
            folders = emptyList()
            toggles = VoiceService.defaultToggles()
            cloudKeyError = null
            userPaused = false
            chargerOnly = false
            wifiOnlyModels = true
            indexDone = 0
            indexTotal = 0
            imgDone = 0
            imgTotal = 0
            pdfDone = 0
            pdfTotal = 0
            pdfSegs = 0
            indexState = "not started"
            Toast.makeText(this, "App data removed", Toast.LENGTH_SHORT).show()
        }

        val displayFolders =
            folders.map {
                runCatching { Uri.parse(it) }.getOrNull()?.lastPathSegment?.let(Uri::decode) ?: it
            }
        val validationErrors =
            SettingsState(
                folders, emptyList(), chargerOnly, wifiOnlyModels,
                toggles.cloudStt, toggles.cloudTts,
            ).validate(sttProvider = "google", ttsProvider = "google") + listOfNotNull(cloudKeyError)

        FileSearchApp(
            tab = tab,
            onTabChange = { tab = it },
            search = {
                SearchScreen(
                    voice = voice,
                    query = query,
                    onQueryChange = { query = it },
                    visibleRows = if (expanded) strongRows + weakRows else strongRows,
                    weakCount = weakRows.size,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    searching = searching,
                    onHeard = { query = it },
                    onOpenResult = { openResult(it) },
                    expandedKey = previewKey,
                    onPreviewToggle = { previewKey = if (previewKey == it.thumbKey) null else it.thumbKey },
                    audioPlayingKey = audioKey,
                    audioIsPlaying = audioPlaying,
                    audioProgress = audioProgress,
                    onAudioToggle = { toggleAudio(it) },
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
                        onPauseToggle = { togglePause() },
                        onDismiss = { tab = 0 },
                    )
                }
            },
            settings = {
                SettingsScreenContent(
                    folders = displayFolders,
                    excludedTypes = emptyList(),
                    chargerOnly = chargerOnly,
                    wifiOnlyModels = wifiOnlyModels,
                    toggles = toggles,
                    sttProviderLabel = "Google STT",
                    ttsProviderLabel = "Google TTS",
                    validationErrors = validationErrors,
                    wipeSummary = "Remove app data deletes downloaded models, the search index, " +
                        "logs, cloud transcripts, and stored voice keys.",
                    onPickFolder = { folderPicker.launch(null) },
                    onChargerOnlyChange = {
                        chargerOnly = it
                        settingsPrefs.edit().putBoolean(KEY_CHARGER, it).apply()
                    },
                    onWifiOnlyChange = {
                        wifiOnlyModels = it
                        settingsPrefs.edit().putBoolean(KEY_WIFI, it).apply()
                    },
                    onCloudSttChange = { setCloudStt(it) },
                    onCloudTtsChange = { setCloudTts(it) },
                    onWipe = { wipeAll() },
                )
            },
        )
    }
}

// F025 constants: first-launch auto-start flag (full corpus, unbound — no cap).
private const val PREFS_F022 = "f022"
private const val KEY_AUTOSTART_DONE = "autostart_done"
private const val PREFS_SETTINGS = "settings"
private const val KEY_FOLDERS = "folders"
private const val KEY_CHARGER = "charger_only"
private const val KEY_WIFI = "wifi_models"
private const val KEY_STT = "cloud_stt"
private const val KEY_TTS = "cloud_tts"
private const val TAG = "MainActivity"
