package com.filesearch.android.engine

enum class FileDecision {
    INDEX,
    SKIP_SECRET,
    SKIP_HIDDEN,
    SKIP_CLOUD_PLACEHOLDER,
    SKIP_SMALL_ICON,
    SKIP_VENDORED,
    SKIP_DATASET_CANDIDATE,
}

/** Pure filename/path filter. No language allow-lists: names in any script always INDEX. */
object Rules {
    const val DATASET_MIN_COUNT = 300
    private const val SMALL_ICON_MAX_BYTES = 1024L

    private val secretExts = setOf("pem", "key", "p12", "pfx", "jks", "keystore", "asc")
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val mediaExts =
        imageExts +
            setOf(
                "mp4", "mkv", "mov", "webm", "avi", "mts", "m2ts", "3gp",
                "mp3", "wav", "ogg", "opus", "m4a", "flac", "aac",
            )
    private val vendoredDirs = setOf("node_modules", ".venv", "vendor", "build")

    fun classify(
        path: String,
        size: Long,
        isHidden: Boolean,
        isCloudPlaceholder: Boolean,
    ): FileDecision {
        val norm = path.replace('\\', '/')
        val segs = norm.split('/').map { it.lowercase() }
        val name = segs.lastOrNull().orEmpty()
        val ext = name.substringAfterLast('.', "")
        if (ext in secretExts || name == ".env" || name.startsWith(".env.") ||
            name.endsWith(".env") || "password" in name
        ) {
            return FileDecision.SKIP_SECRET
        }
        if (segs.any { it in vendoredDirs }) return FileDecision.SKIP_VENDORED
        // dot segments cover .cache and hidden files; Android/data is the app-private dir.
        if (isHidden || segs.any { it.startsWith(".") } || "android/data" in norm.lowercase()) {
            return FileDecision.SKIP_HIDDEN
        }
        if (isCloudPlaceholder) return FileDecision.SKIP_CLOUD_PLACEHOLDER
        if (ext in imageExts && size < SMALL_ICON_MAX_BYTES) return FileDecision.SKIP_SMALL_ICON
        return FileDecision.INDEX
    }

    /** Hex/digit stems (000001.jpg, abc123def456.png) count; IMG_/PXL_/Screenshot_ names do not. */
    fun isMachineNamed(name: String): Boolean {
        val stem = name.substringBeforeLast('.')
        if (stem.length >= 4 && stem.all { it.isDigit() }) return true
        return stem.length >= 8 &&
            stem.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '_' || it == '-' }
    }

    /** Media extensions only — a dir of 300 machine-named logs is data, not a dataset. */
    fun isMediaName(name: String): Boolean = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in mediaExts

    fun isDatasetDir(fileCountMachineNamed: Int): Boolean = fileCountMachineNamed >= DATASET_MIN_COUNT
}
