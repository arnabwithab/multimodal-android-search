package com.filesearch.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

// v1 shell: plain Activity, no Compose. Compose binding is later device work.
// With `--ez bench true` (see scripts/bench-device.sh) runs the one-query
// EmbeddingGemma smoke benchmark off the main thread and writes bench-sm8635.json.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = "FileSearch shell OK (engine + Compose come later)"
        setContentView(view)

        if (!intent.getBooleanExtra("bench", false)) return
        view.text = "Benchmark running (one query)…"
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
    }
}
