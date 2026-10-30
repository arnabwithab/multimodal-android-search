package com.filesearch.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Two independent cloud toggles, both OFF on install (design §7). */
data class VoiceToggles(
    val cloudStt: Boolean = false,
    val cloudTts: Boolean = false,
)

/** Cloud path called with no stored key: names the prefs key, never crashes. */
class MissingVoiceKeyException(message: String) : IllegalStateException(message)

/**
 * F019 device-side voice wiring (arch §7, design §7). On-device STT/TTS are
 * free defaults; cloud paths stay behind the OFF-by-default toggles and need a
 * key in EncryptedSharedPreferences. No audio is recorded, no key is logged.
 */
class VoiceService(private val context: Context) {
    companion object {
        const val PREFS_NAME = "voice_keys"
        const val KEY_STT_API_KEY = "voice_cloud_stt_api_key"
        const val KEY_TTS_API_KEY = "voice_cloud_tts_api_key"
        private const val MS_PER_MINUTE = 60_000L
        private const val UTTERANCE_ID = "filesearch-voice"

        /** Install defaults: both cloud toggles OFF. */
        fun defaultToggles(): VoiceToggles = VoiceToggles()
    }

    private val app: Context get() = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, () -> Unit>? = null
    private var pendingDone: (() -> Unit)? = null

    /** Per-minute cloud-usage tally the metering UI reads (arch §7). */
    var cloudMillisUsed: Long = 0
        private set

    fun addCloudMillis(millis: Long) {
        require(millis >= 0)
        cloudMillisUsed += millis
    }

    fun cloudMinuteBucket(): Long = cloudMillisUsed / MS_PER_MINUTE

    // Key storage (arch §7): EncryptedSharedPreferences only, never logged.
    private fun prefs() = EncryptedSharedPreferences.create(
        app,
        PREFS_NAME,
        MasterKey.Builder(app, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveSttKey(key: String) {
        prefs().edit().putString(KEY_STT_API_KEY, key).apply()
    }

    fun saveTtsKey(key: String) {
        prefs().edit().putString(KEY_TTS_API_KEY, key).apply()
    }

    fun loadSttKey(): String? = prefs().getString(KEY_STT_API_KEY, null)

    fun loadTtsKey(): String? = prefs().getString(KEY_TTS_API_KEY, null)

    fun clearKeys() {
        prefs().edit().remove(KEY_STT_API_KEY).remove(KEY_TTS_API_KEY).apply()
    }

    fun requireCloudSttKey(): String =
        loadSttKey()?.takeIf { it.isNotBlank() } ?: throw MissingVoiceKeyException(
            "Cloud STT key required: save it under EncryptedSharedPreferences " +
                "\"$PREFS_NAME\" key \"$KEY_STT_API_KEY\" (Settings → Voice). " +
                "Offline search still works.",
        )

    fun requireCloudTtsKey(): String =
        loadTtsKey()?.takeIf { it.isNotBlank() } ?: throw MissingVoiceKeyException(
            "Cloud TTS key required: save it under EncryptedSharedPreferences " +
                "\"$PREFS_NAME\" key \"$KEY_TTS_API_KEY\" (Settings → Voice). " +
                "On-device voice still works.",
        )

    // On-device STT input: mic → transcript string → normal query path.
    fun sttIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }

    fun isSttAvailable(): Boolean =
        try {
            SpeechRecognizer.isRecognitionAvailable(app)
        } catch (_: Throwable) {
            false
        }

    /** Starts listening; each final result is delivered as a query string. False = unavailable, never a crash.
     * Caller must call [stopListening] after a result or error; the recognizer is not auto-released. */
    fun startListening(onResult: (String) -> Unit, onError: (Int) -> Unit = {}): Boolean {
        if (!isSttAvailable()) return false
        return try {
            stopListening()
            val rec = SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (heard.isBlank()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(heard)
                }
                override fun onError(error: Int) = onError(error)
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            rec.startListening(sttIntent())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Releases the recognizer. Call after each result/error; [startListening] also calls it first. */
    fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    // On-device TTS output: excerpt → spoken audio, streamed, never persisted.
    // Latest call wins while init is in flight (queued in pendingSpeak, flushed in onInit).
    // Interrupted utterance counts as done (latest-wins, QUEUE_FLUSH).
    fun speak(text: String, onDone: () -> Unit = {}) {
        pendingDone?.invoke().also { pendingDone = null }
        pendingDone = onDone
        if (ttsReady && tts != null) {
            @Suppress("DEPRECATION")
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            return
        }
        pendingSpeak = text to onDone
        if (tts != null) return // init in flight; onInit flushes the queued text
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(utteranceId: String?) {
                    pendingDone?.invoke().also { pendingDone = null }
                }
                override fun onError(utteranceId: String?) {
                    pendingDone?.invoke().also { pendingDone = null }
                }
                @Deprecated("Use onError(String) — kept for old engines.")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    pendingDone?.invoke().also { pendingDone = null }
                }
                override fun onStart(utteranceId: String?) = Unit
            })
            pendingSpeak?.let { (queued, _) ->
                pendingSpeak = null
                if (ttsReady) {
                    @Suppress("DEPRECATION")
                    tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                } else {
                    pendingDone?.invoke().also { pendingDone = null }
                    // TTS-less devices retry per tap.
                    tts?.shutdown(); tts = null
                }
            }
        }
    }

    fun shutdownTts() {
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ttsReady = false
    }

    // Offline fallback (design §7 / arch §7): the typed query always works.
    fun offlineFallback(typed: String): String = typed

    fun isAirplaneModeOn(): Boolean = try {
        Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (_: Throwable) {
        false
    }
}
