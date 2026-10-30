package com.filesearch.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

/** One phone photo: content URI (the stable id), display name, bucket, modified time. */
data class PicRow(
    val uri: Uri,
    val name: String,
    val bucket: String?,
    val dateModified: Long,
)

/**
 * F022 first corpus: phone Pictures via MediaStore (read-only).
 * Newest first, capped — the SAF folder picker + persisted permissions land in
 * F023.
 *
 * // shortcut: no SAF picker yet (F023); corpus is Pictures-only, capped at 50.
 */
object MediaPictures {
    fun list(
        resolver: ContentResolver,
        limit: Int = 50,
    ): List<PicRow> {
        val cols =
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cols,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            return buildList {
                while (c.moveToNext() && size < limit) {
                    val id = c.getLong(idCol)
                    add(
                        PicRow(
                            uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            name = c.getString(nameCol).orEmpty(),
                            bucket = c.getString(bucketCol),
                            dateModified = c.getLong(dateCol),
                        ),
                    )
                }
            }
        }
        return emptyList()
    }
}
