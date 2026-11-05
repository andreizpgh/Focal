package app.focal.data

import app.focal.core.AsrToken
import app.focal.core.EndKind
import app.focal.core.WindowPlan
import app.focal.core.Word

internal const val MAX_PASSAGE_EDIT_BYTES = 128 * 1024
internal fun fitsPassageDraftBudget(text: String): Boolean =
    text.length <= MAX_PASSAGE_EDIT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_PASSAGE_EDIT_BYTES

class PassageSizeException(message: String) : IllegalStateException(message)

/** Every new note is transcribed by Parakeet Ultra, which detects the language itself. */
const val ULTRA_ENGINE = "ultra-v1"
const val AUTOMATIC_LANGUAGE = "auto"

/** Notes recorded before the single-engine rebuild keep this engine as provenance. */
internal const val LEGACY_WHISPER_ENGINE = "whisper"

/** Note lifecycle. Edits are accepted only in [READY]; the speech worker never changes a READY note. */
object NoteState {
    const val RECORDING = "RECORDING"
    const val PAUSED = "PAUSED"
    /** Capture ended; the remaining windows are being transcribed. */
    const val PROCESSING = "PROCESSING"
    /** Every saved second is transcribed (some windows may have failed: see [Note.failedWindows]). */
    const val READY = "READY"
    /** Saved audio waits for transcription, which resumes when Focal is next opened. */
    const val INTERRUPTED = "INTERRUPTED"
    /** The recording could not start. */
    const val ERROR = "ERROR"

    internal val ACTIVE = listOf(RECORDING, PAUSED, PROCESSING)
}

/** Why a recording was paused or ended automatically. */
object CaptureReason {
    const val MICROPHONE = "MICROPHONE"
    const val STORAGE = "STORAGE"
    const val PERMISSION = "PERMISSION"
    const val PROCESS_ENDED = "PROCESS_ENDED"
}

/**
 * [title] is empty until the transcript has words (show the date instead) unless [titleIsCustom].
 * [legacy] notes were transcribed before schema 5: their paragraphs have no word times.
 */
data class Note(
    val id: String,
    val title: String,
    val created: Long,
    val language: String,
    val state: String,
    val duration: Long,
    val error: String,
    val preview: String = "",
    val speechEngine: String = ULTRA_ENGINE,
    val titleIsCustom: Boolean = false,
    val legacy: Boolean = false,
    val audioPresent: Boolean = true,
    val pauseReason: String? = null,
    val endReason: String? = null,
    val failedWindows: Int = 0,
)

internal val Note.mayNeedRecovery: Boolean get() = state == NoteState.INTERRUPTED || state == NoteState.ERROR
val Note.displayTitle: String get() = title.ifBlank { "Untitled note" }

/**
 * One paragraph of a note. [text] is what the user sees; [sourceText] is the formatted recognizer text it
 * was materialised from (null = unknown, for text saved before schema 5) and [wordsJson] holds the timed
 * source words ([sourceWords]; null when there are no word times). [revision] changes with every write, so
 * an editor can detect that its copy is stale. [hidden] paragraphs (legacy background-sound exclusions) stay
 * stored but are left out of the body, search and copy. Only the speech worker extends an [open] paragraph.
 */
data class Paragraph(
    val id: Long,
    val noteId: String,
    val ord: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val sourceText: String?,
    val wordsJson: String?,
    val edited: Boolean,
    val hidden: Boolean,
    val open: Boolean,
    val revision: Long,
) {
    val visible: Boolean get() = !hidden && text.isNotBlank()
    val canRestore: Boolean get() = sourceText != null && sourceText != text
    fun sourceWords(): List<Word>? = wordsJson?.let(TranscriptJson::decodeWords)
}

/** The note body as Copy and Share put it: visible paragraphs joined by a blank line. */
fun transcriptText(paragraphs: List<Paragraph>): String =
    paragraphs.asSequence().filter { it.visible }.joinToString("\n\n") { it.text.trim() }

enum class WindowState {
    /** Planned and persisted; decoding has not committed yet. */
    PENDING,
    DONE,
    /** Not decoded: see [StoredWindow.skip]. */
    SILENT,
    /** Decoding failed or was killed twice. */
    FAILED,
}

/**
 * A persisted segmentation window. Windows of notes recorded with schema 5 tile the recording; their plan
 * is the segmenter checkpoint ([plan]). Windows migrated from older schemas have no [endKind] and no plan.
 * [hidden] marks a decoded result kept raw but not shown (hallucination guard).
 */
data class StoredWindow(
    val id: Long,
    val noteId: String,
    val index: Int,
    val ownStartMs: Long,
    val ownEndMs: Long,
    val audioStartMs: Long,
    val audioEndMs: Long,
    val keepLoMs: Long?,
    val keepHiMs: Long?,
    val endKind: EndKind?,
    val decideAtMs: Long,
    val speechMs: Long,
    val peak: Int,
    val state: WindowState,
    val skip: String?,
    val hidden: Boolean,
    val engine: String?,
    val attempts: Int,
    val rawText: String?,
    val error: String?,
) {
    val legacy: Boolean get() = endKind == null
    fun plan(): WindowPlan = WindowPlan(
        index, ownStartMs, ownEndMs, audioStartMs, audioEndMs, keepLoMs, keepHiMs,
        checkNotNull(endKind) { "A migrated window has no segmentation plan." }, decideAtMs, speechMs, peak,
    )
}

/** A recognizer result as stored: its text and its tokens with times and log-probabilities. */
data class RawAsr(val text: String, val tokens: List<AsrToken>)

/** A range of a migrated note that was never transcribed. Ultra transcribes it as a recording of its own. */
data class Gap(val noteId: String, val startMs: Long, val endMs: Long)

/** Determinate progress: [remainingMs] of saved audio still needs transcription. */
data class TranscriptionProgress(
    val durationMs: Long,
    val remainingMs: Long,
    val pendingWindows: Int,
    val gaps: Int,
    val failedWindows: Int,
)

/** A match of Find in note: chars [start, end) of the paragraph's text. */
data class TextMatch(val paragraphId: Long, val start: Int, val end: Int)

/**
 * A library search result. [paragraphId] is the first matching paragraph (null when only the title
 * matched); [hitStart]/[hitEnd] locate the hit in that paragraph's text and [snippetHitStart] in [snippet].
 */
data class SearchResult(
    val note: Note,
    val paragraphId: Long?,
    val hitStart: Int,
    val hitEnd: Int,
    val snippet: String,
    val snippetHitStart: Int,
)

sealed interface EditResult {
    /** The change is saved; [paragraphs] are the affected paragraphs as now stored. */
    data class Saved(val paragraphs: List<Paragraph>) : EditResult
    /** The paragraph changed since the caller read it ([Paragraph.revision]). */
    data object Conflict : EditResult
    data object Missing : EditResult
    /** The note is recording or transcribing; edits are accepted only when it is ready. */
    data object NotEditable : EditResult
    data object TooLarge : EditResult
    /** The paragraph has no recognizer text to restore (saved before schema 5 without one). */
    data object NoOriginal : EditResult
}

/** One-step undo of Replace all; valid while none of the replaced paragraphs changed again. */
data class ReplaceUndo(val noteId: String, val entries: List<Entry>) {
    data class Entry(val paragraphId: Long, val text: String, val edited: Boolean, val revision: Long)
}

data class ReplaceResult(val count: Int, val undo: ReplaceUndo?, val outcome: EditResult)
