package com.filesearch.android

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * F021 — HEIC decoder probe.
 *
 * A still-image query alone lies on some SoCs: SM8635 lists no
 * `image/vnd.android.heic` *decoder* (encoder only) yet its ImageDecoder
 * decodes the `f017/sample.heic` fixture to a 1280x854 bitmap — via the
 * software HEVC decoder (`c2.android.hevc.decoder`), since `.heic` stills are
 * HEVC-coded. So "can this phone decode HEIC?" = still-image codec OR an HEVC
 * video decoder.
 *
 * Engine ([plan][com.filesearch.android.engine.ImagePrepare.plan] lives in `:app`,
 * which this shell module must not depend on) decides skip-vs-vector from a plain
 * [heicDecoderPresent] flag that defaults to true; the decode path passes this
 * probe's result once the shared-module extract allows the `:app` import. Until
 * then: no HEIC decode is attempted when this returns false, and HEIC files are
 * recorded as `skipped:no-heic-decoder` instead of silently missed.
 *
 * Unknown (probe throws) means present — other phones must not newly skip.
 */
object HeicSupport {
    const val HEIC_STILL_MIME = "image/vnd.android.heic"

    /** `.heic` stills are HEVC-coded; this leg is what SM8635 actually decodes through. */
    const val HEVC_VIDEO_MIME = "video/hevc"

    @Volatile
    private var cached: Boolean? = null

    /** True when a still-image HEIC decoder or an HEVC decoder exists; true on any probe error. */
    fun hasHeicDecoder(): Boolean {
        cached?.let { return it }
        val present =
            try {
                hasDecoderFor(HEIC_STILL_MIME) || hasDecoderFor(HEVC_VIDEO_MIME)
            } catch (_: Throwable) {
                true
            }
        cached = present
        return present
    }

    /** One leg of the probe: is there a decoder for [mime]? True on query error (unknown = present). */
    fun hasDecoderFor(mime: String): Boolean {
        return try {
            val probe = MediaFormat()
            probe.setString(MediaFormat.KEY_MIME, mime)
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) != null
        } catch (_: Throwable) {
            true
        }
    }

    /** Test seam: forget the cached probe result. */
    fun resetForTest() {
        cached = null
    }
}
