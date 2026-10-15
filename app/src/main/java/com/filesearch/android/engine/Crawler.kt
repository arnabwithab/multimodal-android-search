package com.filesearch.android.engine

import java.io.File
import java.nio.file.Files

data class CrawlEntry(val path: String, val size: Long, val mtime: Long, val inode: Long)

/** Metadata-only walk. Never follows symlinks, never throws on unreadable entries. */
class Crawler(
    private val filter: (path: String, size: Long, isHidden: Boolean, isCloudPlaceholder: Boolean) -> FileDecision =
        { p, s, h, c -> Rules.classify(p, s, h, c) },
    private val isCloudPlaceholder: (File) -> Boolean = { false },
) {
    data class Skipped(val path: String, val reason: FileDecision)

    private val _skipped = mutableListOf<Skipped>()
    private val _errors = mutableListOf<String>()
    val skipped: List<Skipped> get() = _skipped
    val errors: List<String> get() = _errors

    fun walk(root: File): List<CrawlEntry> {
        _skipped.clear()
        _errors.clear()
        val found = mutableListOf<CrawlEntry>()
        if (!root.exists()) {
            _errors.add("missing root: ${root.path}")
            return found
        }
        if (root.isFile) {
            addFile(root, found)
            return found
        }
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids: Array<File> =
                try {
                    dir.listFiles()
                } catch (_: SecurityException) {
                    null
                } ?: emptyArray()
            if (kids.isEmpty() && !dir.canRead()) {
                _errors.add("unreadable dir: ${dir.path}")
                continue
            }
            val dataset =
                Rules.isDatasetDir(
                    kids.count { it.isFile && Rules.isMediaName(it.name) && Rules.isMachineNamed(it.name) },
                )
            for (kid in kids) {
                if (Files.isSymbolicLink(kid.toPath())) continue
                if (kid.isDirectory) {
                    if (kid.isHidden || kid.name.startsWith(".")) {
                        _skipped.add(Skipped(kid.path, FileDecision.SKIP_HIDDEN))
                    } else {
                        stack.add(kid)
                    }
                } else if (dataset) {
                    _skipped.add(Skipped(kid.path, FileDecision.SKIP_DATASET_CANDIDATE))
                } else {
                    addFile(kid, found)
                }
            }
        }
        return found
    }

    private fun addFile(
        f: File,
        out: MutableList<CrawlEntry>,
    ) {
        val decision = filter(f.path, f.length(), f.isHidden, isCloudPlaceholder(f))
        if (decision == FileDecision.INDEX) {
            out.add(CrawlEntry(f.path, f.length(), f.lastModified(), inodeOf(f)))
        } else {
            _skipped.add(Skipped(f.path, decision))
        }
    }
}

fun inodeOf(f: File): Long =
    try {
        (Files.getAttribute(f.toPath(), "unix:ino") as Number).toLong()
    } catch (_: Exception) {
        // shortcut: non-Linux fallback breaks move detection; Linux CI and Android both provide unix:ino.
        f.absolutePath.hashCode().toLong()
    }
