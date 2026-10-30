package com.filesearch.android

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import android.util.Log

/**
 * MediaStore change stub (arch §6: index new files in ~1–2s).
 *
 * v1 only logs the event (logcat) — no indexing yet; the [onEvent] hook exists
 * so the field test can assert delivery without touching real user files.
 */
class MediaStoreObserver(
    handler: Handler?,
    private val onEvent: (selfChange: Boolean, uri: Uri?) -> Unit = { self, uri ->
        Log.i(TAG, "media event self=$self uri=$uri")
    },
) : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean, uri: Uri?) = onEvent(selfChange, uri)

    fun register(resolver: ContentResolver) {
        resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, this)
    }

    fun unregister(resolver: ContentResolver) = resolver.unregisterContentObserver(this)

    companion object {
        const val TAG = "MediaStoreObserver"
    }
}
