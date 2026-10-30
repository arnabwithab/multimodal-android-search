package com.filesearch.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filesearch.android.engine.classifyPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared inline preview pane for expanded result rows (Search tab only —
 * never navigates, never leaves the app).
 *
 * Paparazzi-safe by construction: every composable here takes already-loaded
 * state (bitmaps/text/progress) and never touches ContentResolver/Pdfium/
 * ExoPlayer. Device loaders ([rememberImageThumb], [rememberPdfFirstPage])
 * return null/Loading in inspection mode, so JVM snapshots render the
 * placeholder/error tiles deterministically.
 */

// --- Pure helpers (JVM unit-tested, no Android framework) ---

/** >=200-char TEXT rule (engine PdfPages.classifyPage), reused not duplicated. */
fun pdfShowsText(pageText: String): Boolean =
    classifyPage(pageText, isLocked = false, isScanned = false) ==
        com.filesearch.android.engine.PageClass.TEXT

/** Power-of-2 BitmapFactory.inSampleSize so the longest side lands near [targetPx]. */
fun sampleSizeFor(
    srcW: Int,
    srcH: Int,
    targetPx: Int,
): Int {
    if (srcW <= 0 || srcH <= 0 || targetPx <= 0) return 1
    var sample = 1
    while (maxOf(srcW, srcH) / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** 0..1 playback fraction; 0 when duration unknown. */
fun progressFraction(
    posMs: Long,
    durMs: Long,
): Float {
    if (durMs <= 0 || posMs < 0) return 0f
    return (posMs.toFloat() / durMs).coerceIn(0f, 1f)
}

// --- Device loaders (early-out BELOW hooks; runCatching -> null, never crash) ---

/** Longest side in px for inline tile renders (smaller than the 1400px index cache). */
const val PREVIEW_LONGEST_PX = 800

/** Sampled thumb bitmap for an image/video row URI, or null while loading/on failure. */
@Composable
fun rememberImageThumb(
    uriString: String,
    targetPx: Int = 256,
): ImageBitmap? {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    var thumb by remember(uriString) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriString) {
        if (inspection || uriString.isBlank()) {
            thumb = null
            return@LaunchedEffect
        }
        thumb =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    // Content URIs (MediaStore/SAF) first, plain files as fallback.
                    fun decodeWith(opts: BitmapFactory.Options): android.graphics.Bitmap? =
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, opts)
                            }
                        }.getOrNull() ?: BitmapFactory.decodeFile(uriString, opts)
                    val bounds =
                        BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    decodeWith(bounds)
                    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    decodeWith(opts)?.asImageBitmap()
                }.getOrNull()
            }
    }
    if (inspection || uriString.isBlank()) return null
    return thumb
}

/** First-page PDF state: text layer (TEXT rule) or rendered bitmap, else placeholders. */
data class PdfPreviewData(
    val text: String? = null,
    val bitmap: ImageBitmap? = null,
    val failed: Boolean = false,
)

@Composable
fun rememberPdfFirstPage(uriString: String): PdfPreviewData {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    var data by remember(uriString) { mutableStateOf(PdfPreviewData()) }
    LaunchedEffect(uriString) {
        if (inspection || uriString.isBlank()) {
            data = PdfPreviewData()
            return@LaunchedEffect
        }
        data =
            withContext(Dispatchers.IO) {
                runCatching {
                    val pdfium = com.github.axet.pdfium.Pdfium()
                    com.github.axet.pdfium.Pdfium.FPDF_InitLibrary()
                    val uri = Uri.parse(uriString)
                    // Content URIs (MediaStore/SAF) first, plain files as fallback.
                    val pfd =
                        runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                            ?: runCatching {
                                android.os.ParcelFileDescriptor.open(
                                    java.io.File(uriString),
                                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                                )
                            }.getOrNull()
                            ?: return@runCatching PdfPreviewData(failed = true)
                    pfd.use {
                        pdfium.open(it.fileDescriptor)
                        try {
                            if (pdfium.pagesCount < 1) return@runCatching PdfPreviewData(failed = true)
                            val page = pdfium.openPage(0)
                            try {
                                val text =
                                    try {
                                        val t = page.open()
                                        try {
                                            t.getText(0, t.count)
                                        } finally {
                                            t.close()
                                        }
                                    } catch (_: Exception) {
                                        ""
                                    }
                                if (pdfShowsText(text)) {
                                    PdfPreviewData(text = text.trim().take(600))
                                } else {
                                    val size = pdfium.getPageSize(0)
                                    val scale = PREVIEW_LONGEST_PX.toFloat() / maxOf(size.width, size.height)
                                    val w = maxOf(1, (size.width * scale).toInt())
                                    val h = maxOf(1, (size.height * scale).toInt())
                                    val bmp =
                                        android.graphics.Bitmap.createBitmap(
                                            w,
                                            h,
                                            android.graphics.Bitmap.Config.ARGB_8888,
                                        )
                                    page.render(bmp, 0, 0, w, h)
                                    PdfPreviewData(bitmap = bmp.asImageBitmap())
                                }
                            } finally {
                                page.close()
                            }
                        } finally {
                            pdfium.close()
                        }
                    }
                }.getOrElse { PdfPreviewData(failed = true) }
            }
    }
    if (inspection || uriString.isBlank()) return PdfPreviewData()
    return data
}

// --- Presentational panes (pure; snapshots drive these directly) ---

@Composable
fun ThumbTile(
    kind: String,
    thumb: ImageBitmap?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(56.dp),
    ) {
        if (thumb != null) {
            Image(
                thumb,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp),
            )
        } else {
            // Placeholder while loading + error tile share one deterministic
            // tile (letter for known kinds, broken-image glyph otherwise) so
            // JVM snapshots never depend on device decode.
            Box(contentAlignment = Alignment.Center) {
                if (kind == "image" || kind == "video" || kind == "pdf" || kind == "audio") {
                    Text(
                        kind.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Icon(
                        Icons.Filled.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun PdfPreviewPane(
    data: PdfPreviewData,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            data.text != null ->
                Text(
                    data.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).testTag("pdfText"),
                )
            data.bitmap != null ->
                Image(
                    data.bitmap,
                    contentDescription = "First page preview",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().height(180.dp).testTag("pdfRender"),
                )
            else ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp).testTag("pdfPlaceholder"),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Text(if (data.failed) "Preview unavailable" else "PDF preview loading…", style = MaterialTheme.typography.bodyMedium)
                }
        }
    }
}

@Composable
fun AudioInlineRow(
    fileName: String,
    isPlaying: Boolean,
    progress: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.testTag("audioToggle")) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause $fileName" else "Play $fileName",
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    fileName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("audioProgress"),
            )
        }
    }
}

/**
 * Expanded inline preview for one row. Image/video show the (sampled) bitmap
 * or its loading tile; PDFs show text-or-render via the >=200-char rule; audio
 * shows play/pause + progress. The row's viewer icon stays the only external
 * affordance — expansion never navigates.
 */
@Composable
fun MediaPreview(
    row: SearchRowUi,
    imageThumb: ImageBitmap?,
    pdf: PdfPreviewData,
    audioIsPlaying: Boolean,
    audioProgress: Float,
    onAudioToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (row.kind) {
            "image", "video" -> {
                if (imageThumb != null) {
                    Image(
                        imageThumb,
                        contentDescription = row.fileName,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().height(180.dp).testTag("imagePreview"),
                    )
                } else {
                    Text(
                        "Thumbnail loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).testTag("imagePlaceholder"),
                    )
                }
            }
            "pdf" -> PdfPreviewPane(pdf)
            "audio" ->
                AudioInlineRow(
                    fileName = row.fileName,
                    isPlaying = audioIsPlaying,
                    progress = audioProgress,
                    onToggle = onAudioToggle,
                )
            else -> {
                Text(
                    row.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
