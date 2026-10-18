package com.filesearch.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// v1 shell: plain Activity, no Compose. Compose binding is later device work.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = "FileSearch shell OK (engine + Compose come later)"
        setContentView(view)
    }
}
