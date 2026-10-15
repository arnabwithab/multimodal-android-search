package com.filesearch.android.engine

/** Files per indexing job: image-ish kinds batch for IO overlap, everything else one at a time. */
fun jobBatch(kind: String): Int = if (kind == "image" || kind == "screenshot" || kind == "frame" || kind == "page_image") 8 else 1

/**
 * Units per inference (arch §3: up to 16 texts, 4 images, 2 audio).
 * Takes a modality or a segment kind — callers hold kinds, so kinds map in the same when.
 * FTS-only/unknown go one at a time.
 */
fun inferBatch(kindOrModality: String): Int =
    when (kindOrModality) {
        "text", "chunk", "page", "ocr", "name" -> 16
        "image", "page_image", "frame", "screenshot" -> 4
        "audio" -> 2
        else -> 1
    }

/**
 * First-pass cap for long files (arch §6): 30 pages for PDFs, 40 chunks for docs —
 * the rest waits in a second pass after everything else is searchable.
 * Null means the whole file is indexed now.
 */
fun firstPassLimit(kind: String): Int? =
    when (kind) {
        "pdf", "page", "page_image" -> 30
        "doc", "chunk" -> 40
        else -> null
    }

/** Two-pass file state (arch §3): each step commits one txn, so kills lose nothing. */
enum class FileState {
    PENDING,
    PARTIAL,
    DONE,
    FAILED,
    STOPPED,
}

/** Units remain -> [FileState.PARTIAL], else [FileState.DONE]; DONE/FAILED/STOPPED are terminal. */
fun nextState(
    current: FileState,
    unfinishedUnits: Long,
): FileState {
    require(unfinishedUnits >= 0)
    return when (current) {
        FileState.DONE, FileState.FAILED, FileState.STOPPED -> current
        else -> if (unfinishedUnits > 0) FileState.PARTIAL else FileState.DONE
    }
}

// canonical vocab (arch §5 segment kinds: name/image/ocr/page/page_image/chunk/audio/frame,
// plus file words screenshot/pdf/doc/video and the text modality) — the one place for queue ranks.
private val queueRank =
    mapOf(
        "screenshot" to 0,
        "image" to 1, "page_image" to 1,
        "pdf" to 2, "page" to 2,
        "doc" to 3, "chunk" to 3, "name" to 3, "ocr" to 3, "text" to 3,
        "audio" to 4,
        "video" to 5, "frame" to 5,
    )

private fun rankOf(kind: String): Int = queueRank[kind] ?: Int.MAX_VALUE

/** One file waiting to index: path identifies it, kind ranks it. */
data class QueueItem(val path: String, val kind: String)

/**
 * Pure queue ordering (arch §5 indexingOrderSQL): screenshots -> images -> PDFs ->
 * docs -> audio -> video. Stable within a rank; unknown kinds go last, never dropped.
 *
 * Device-worker concerns live outside this policy: prepare-next-while-embedding-current
 * (coroutines + single embed mutex) and closeWorker() freeing the full model; each step
 * commits one txn via IndexStore.
 */
class IndexQueue {
    fun order(files: List<QueueItem>): List<QueueItem> = files.sortedBy { rankOf(it.kind) }
}

/**
 * Second-pass pick-up: kinds still holding unfinished units, in queue rank order.
 * Pure — the worker feeds it IndexStore.unfinishedCounts(); each step commits one txn
 * via IndexStore.
 */
fun finishLongFiles(unfinished: Map<String, Long>): List<String> = unfinished.filter { it.value > 0 }.keys.sortedBy { rankOf(it) }
