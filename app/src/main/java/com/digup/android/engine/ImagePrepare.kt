package com.digup.android.engine

/** Photo vs screenshot — drives downscale size and vision token budget. */
enum class ImageKind { PHOTO, SCREENSHOT }

/** Plan for one image. Null [skipReason] means the image will be embedded. */
data class ImagePlan(
    val longestSide: Int,
    val png: Boolean,
    val visionTokens: Int,
    val skipReason: String? = null
)

private val RAW_FORMATS = setOf("arw", "cr2", "cr3", "nef", "dng", "rw2", "orf", "raf", "pef", "srw")
private val RAW_MIME_HINTS = listOf("adobe-dng", "/arw", "/x-sony-arw", "/x-olympus-orf", "/x-fuji-raf", "/x-panasonic-rw2")

/** Size + skip rules. HEIC is NOT skipped (decoded on-device via ImageDecoder). */
fun plan(width: Int, height: Int, isScreenshot: Boolean, format: String): ImagePlan {
    val kind = if (isScreenshot) ImageKind.SCREENSHOT else ImageKind.PHOTO
    val base = when (kind) {
        ImageKind.PHOTO -> ImagePlan(longestSide = 1024, png = false, visionTokens = 140)
        ImageKind.SCREENSHOT -> ImagePlan(longestSide = 1536, png = true, visionTokens = 280)
    }
    if (width <= 0 || height <= 0) return base.copy(skipReason = "skipped:invalid-dims")
    // Icons are small in both dims; a wide banner (1920x100) is real content, not an icon.
    if (maxOf(width, height) < 256) return base.copy(skipReason = "skipped:icon")
    val norm = format.lowercase(java.util.Locale.ROOT)
    val ext = norm.substringAfterLast(".")
    if (ext in RAW_FORMATS || RAW_MIME_HINTS.any { it in norm }) return base.copy(skipReason = "skipped:raw-v1")
    return base
}

/** Screenshot iff path or MediaStore bucket mentions screenshots, or filename starts with "Screenshot". */
fun isScreenshot(path: String, bucket: String?): Boolean {
    if (bucket != null && bucket.contains("screenshot", ignoreCase = true)) return true
    if (path.contains("screenshot", ignoreCase = true)) return true
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    return name.startsWith("screenshot", ignoreCase = true)
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
fun downscalePlan(srcW: Int, srcH: Int, longestSide: Int): Pair<Int, Int> {
    val m = maxOf(srcW, srcH)
    if (m <= longestSide) return srcW to srcH
    return maxOf(1, srcW * longestSide / m) to maxOf(1, srcH * longestSide / m)
}
