package com.filesearch.android.engine

/** Photo vs screenshot — drives downscale size and vision token budget. */
enum class ImageKind { PHOTO, SCREENSHOT }

/** Plan for one image. Null [skipReason] means the image will be embedded. */
data class ImagePlan(
    val longestSide: Int,
    val png: Boolean,
    val visionTokens: Int,
    val skipReason: String? = null,
)

private val RAW_FORMATS = setOf("arw", "cr2", "cr3", "nef", "dng", "rw2", "orf", "raf", "pef", "srw")
private val RAW_MIME_HINTS = listOf("adobe-dng", "/arw", "/x-sony-arw", "/x-olympus-orf", "/x-fuji-raf", "/x-panasonic-rw2")

/** HEIC stills need an on-device decoder (SM8635 firmware ships an encoder only — F021). */
fun isHeicFormat(format: String): Boolean {
    val norm = format.lowercase(java.util.Locale.ROOT)
    val ext = norm.substringAfterLast(".")
    return ext == "heic" || ext == "heif" ||
        norm == "image/heic" || norm == "image/heif" ||
        norm == "image/vnd.android.heic"
}

/** Smallest longest-side that still counts as content; below this in both dims the image is an icon. */
private const val ICON_LONGEST_PX = 256

/** Size + skip rules. HEIC decodes via ImageDecoder where the firmware ships a decoder;
 * without one ([heicDecoderPresent] false) it is skipped with an explicit reason
 * instead of silently missed. Defaults to present so decoder-less phones opt in explicitly. */
fun plan(
    width: Int,
    height: Int,
    isScreenshot: Boolean,
    format: String,
    heicDecoderPresent: Boolean = true,
): ImagePlan {
    val kind = if (isScreenshot) ImageKind.SCREENSHOT else ImageKind.PHOTO
    val base =
        when (kind) {
            ImageKind.PHOTO -> ImagePlan(longestSide = 1024, png = false, visionTokens = 140)
            ImageKind.SCREENSHOT -> ImagePlan(longestSide = 1536, png = true, visionTokens = 280)
        }
    val norm = format.lowercase(java.util.Locale.ROOT)
    val ext = norm.substringAfterLast(".")
    val skipReason =
        when {
            width <= 0 || height <= 0 -> "skipped:invalid-dims"
            // Icons are small in both dims; a wide banner (1920x100) is real content, not an icon.
            maxOf(width, height) < ICON_LONGEST_PX -> "skipped:icon"
            ext in RAW_FORMATS || RAW_MIME_HINTS.any { it in norm } -> "skipped:raw-v1"
            isHeicFormat(format) && !heicDecoderPresent -> "skipped:no-heic-decoder"
            else -> null
        }
    return if (skipReason == null) base else base.copy(skipReason = skipReason)
}

/** Screenshot iff path or MediaStore bucket mentions screenshots, or filename starts with "Screenshot". */
fun isScreenshot(
    path: String,
    bucket: String?,
): Boolean {
    val bucketHit = bucket != null && bucket.contains("screenshot", ignoreCase = true)
    val pathHit = path.contains("screenshot", ignoreCase = true)
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    return bucketHit || pathHit || name.startsWith("screenshot", ignoreCase = true)
}

/** OCR backend seam — device wires ML Kit Text Recognition v2 here; Linux tests use a fake. */
interface OcrReader {
    fun read(imageBytes: ByteArray): String
}

/**
 * OCR text segment: FTS-only, modality=none, no vector.
 * Text is stored verbatim for every script (no language branching).
 */
data class OcrSegment(val text: String)

fun ocrSegment(text: String): OcrSegment = OcrSegment(text)

/** Aspect-preserving fit of srcWxsrcH inside [longestSide]; never upscales. */
fun downscalePlan(
    srcW: Int,
    srcH: Int,
    longestSide: Int,
): Pair<Int, Int> {
    val m = maxOf(srcW, srcH)
    if (m <= longestSide) return srcW to srcH
    return maxOf(1, srcW * longestSide / m) to maxOf(1, srcH * longestSide / m)
}
