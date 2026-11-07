package app.focal.recording

import app.focal.core.EndKind
import app.focal.core.Segmenter
import app.focal.core.SegmenterStart
import app.focal.core.SpeechDetector
import app.focal.core.WindowGate
import app.focal.core.WindowPlan
import app.focal.data.NoteStore
import java.io.RandomAccessFile

/**
 * Feeds one note's saved PCM to the [Segmenter] on the speech worker and persists every planned window,
 * which is also the segmenter checkpoint. Recorded Pause positions become breaks. A note migrated from an
 * older schema is segmented gap by gap, each gap like a recording of its own.
 *
 * [detector] supplies a fresh speech detector per segmenter; null selects the relative-energy fallback.
 */
internal class WindowFeed(
    private val store: NoteStore,
    private val noteId: String,
    private val detector: () -> SpeechDetector?,
) {
    private var segmenter: Segmenter? = null
    private var gapStartMs: Long? = null
    /** Pause positions (samples) this feed has closed a window at, or skipped. */
    private val marked = HashSet<Long>()
    private val block = ShortArray(BLOCK_SAMPLES)
    private val bytes = ByteArray(BLOCK_SAMPLES * 2)

    /** True once every saved sample belongs to a persisted window. */
    var complete = false
        private set

    /**
     * Plans windows from saved audio: up to [durableMs] (the synced watermark) and any synced Pause while
     * recording, the whole file once [finished]. Returns true when it saved at least one window.
     */
    fun advance(durableMs: Long, finished: Boolean, legacy: Boolean): Boolean =
        if (legacy) advanceGap() else advanceTail(durableMs, finished)

    private fun advanceTail(durableMs: Long, finished: Boolean): Boolean {
        val last = store.lastWindow(noteId)
        if (last?.endKind == EndKind.FINAL) { complete = true; return false }
        val current = segmenter ?: Segmenter(detector(), last?.let { SegmenterStart.after(it.plan()) } ?: SegmenterStart.BEGINNING)
            .also { segmenter = it }
        // A recording that ends exactly at a cut has no FINAL window: its segmenter is finished all the same.
        if (current.isFinished) { complete = true; return false }
        val checkpoint = (last?.ownEndMs ?: 0L) * SAMPLES_PER_MS
        // A Pause exactly at the checkpoint is replayed too: it plans no window, but the slot it splits
        // must count only its later samples in the next window, as it did before the process died.
        val breaks = store.breaks(noteId).filter { it >= checkpoint && it !in marked }
        val end = if (finished) fileSamples() else maxOf(durableMs * SAMPLES_PER_MS, breaks.lastOrNull() ?: 0L)
        for (pause in breaks) {
            // Audio already fed past a Pause cannot be closed there; that can only follow a lost break.
            if (pause < current.fedSamples) { marked += pause; continue }
            // Each saved window is the checkpoint: return after it and continue on the next call.
            if (feed(current, pause)) return true
            marked += pause
            if (persist(current.markBreak())) return true
        }
        if (current.fedSamples < end && feed(current, end)) return true
        if (finished && current.fedSamples >= end) {
            persist(current.finish())
            complete = true
            return true
        }
        return false
    }

    private fun advanceGap(): Boolean {
        val gap = store.gaps(noteId).firstOrNull() ?: run { complete = true; return false }
        var current = segmenter
        if (current == null || gapStartMs == null) {
            val previous = store.windowEndingAt(noteId, gap.startMs)?.takeIf { !it.legacy && it.endKind != EndKind.FINAL }
            current = Segmenter(detector(), previous?.let { SegmenterStart.after(it.plan()) }
                ?: SegmenterStart.range(store.nextWindowIndex(noteId), gap.startMs))
            segmenter = current
            gapStartMs = gap.startMs
        }
        val end = gap.endMs * SAMPLES_PER_MS
        if (feed(current, end) && current.fedSamples < end) return true
        if (current.fedSamples >= end) {
            persist(current.markBreak())
            segmenter = null
            gapStartMs = null
        }
        return true
    }

    /** Feeds [segmenter] up to sample [target], one block at a time; stops after a block that saved a window. */
    private fun feed(segmenter: Segmenter, target: Long): Boolean {
        RandomAccessFile(store.audioFile(noteId), "r").use { file ->
            while (segmenter.fedSamples < target) {
                val count = minOf(BLOCK_SAMPLES.toLong(), target - segmenter.fedSamples).toInt()
                file.seek(segmenter.fedSamples * 2)
                file.readFully(bytes, 0, count * 2)
                for (i in 0 until count) block[i] = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort()
                if (persist(segmenter.accept(block, 0, count))) return true
            }
        }
        return false
    }

    private fun persist(plans: List<WindowPlan>): Boolean {
        plans.forEach { store.addWindow(noteId, it, WindowGate.skip(it)) }
        return plans.isNotEmpty()
    }

    private fun fileSamples(): Long = store.audioFile(noteId).length() / 2

    private companion object {
        const val SAMPLES_PER_MS = 16L
        const val BLOCK_SAMPLES = 16_000
    }
}
