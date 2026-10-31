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
 * F025 full corpus: ALL MediaStore Images buckets (DCIM, Pictures,
 * Screenshots, WhatsApp Images, Download, …), newest first, unbound — the
 * F022 Pictures-only cap-50 slice is gone. Workers commit in per-50 batches
 * (progress + watchdog-friendly yields) so RSS stays sane. The SAF folder
 * picker + persisted permissions stay F023.
 */
object MediaPictures {
    fun list(
        resolver: ContentResolver,
        limit: Int = Int.MAX_VALUE,
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

    /**
     * Distinct bucket names across the whole Images collection — probed, never
     * hardcoded — so the status sheet can report which buckets were covered.
     * Single-column cursor, streamed: cheap next to the photo sweep itself.
     */
    fun listBuckets(resolver: ContentResolver): List<String> {
        val cols = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cols,
            null,
            null,
            null,
        )?.use { c ->
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            return FullCorpus.distinctBuckets(buildList { while (c.moveToNext()) add(c.getString(bucketCol)) })
        }
        return emptyList()
    }
}
