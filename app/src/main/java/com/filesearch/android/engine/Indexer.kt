package com.filesearch.android.engine

/** Image-ish kinds indexed per job (IO overlap); everything else one at a time. */
private val imageJobKinds = setOf("image", "screenshot", "frame", "page_image")
private const val IMAGE_JOB_BATCH = 8

/** Files per indexing job: image-ish kinds batch for IO overlap, everything else one at a time. */
fun jobBatch(kind: String): Int = if (kind in imageJobKinds) IMAGE_JOB_BATCH else 1

private const val TEXT_INFER_BATCH = 16
private const val IMAGE_INFER_BATCH = 4
private const val AUDIO_INFER_BATCH = 2

/**
 * Units per inference (arch §3: up to 16 texts, 4 images, 2 audio).
 * Takes a modality or a segment kind — callers hold kinds, so kinds map in the same when.
 * FTS-only/unknown go one at a time.
 */
fun inferBatch(kindOrModality: String): Int =
    when (kindOrModality) {
        "text", "chunk", "page", "ocr", "name" -> TEXT_INFER_BATCH
        "image", "page_image", "frame", "screenshot" -> IMAGE_INFER_BATCH
        "audio" -> AUDIO_INFER_BATCH
        else -> 1
    }

private const val PDF_FIRST_PASS_PAGES = 30
private const val DOC_FIRST_PASS_CHUNKS = 40

/**
 * First-pass cap for long files (arch §6): 30 pages for PDFs, 40 chunks for docs —
 * the rest waits in a second pass after everything else is searchable.
 * Null means the whole file is indexed now.
 */
fun firstPassLimit(kind: String): Int? =
    when (kind) {
        "pdf", "page", "page_image" -> PDF_FIRST_PASS_PAGES
        "doc", "chunk" -> DOC_FIRST_PASS_CHUNKS
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
private const val QUEUE_RANK_DOC = 3
private const val QUEUE_RANK_AUDIO = 4
private const val QUEUE_RANK_VIDEO = 5
private val queueRank =
    mapOf(
        "screenshot" to 0,
        "image" to 1, "page_image" to 1,
        "pdf" to 2, "page" to 2,
        "doc" to QUEUE_RANK_DOC, "chunk" to QUEUE_RANK_DOC, "name" to QUEUE_RANK_DOC, "ocr" to QUEUE_RANK_DOC,
        "text" to QUEUE_RANK_DOC,
        "audio" to QUEUE_RANK_AUDIO,
        "video" to QUEUE_RANK_VIDEO, "frame" to QUEUE_RANK_VIDEO,
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
