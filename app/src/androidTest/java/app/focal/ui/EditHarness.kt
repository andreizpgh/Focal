package app.focal.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.focal.data.SplitOrigin
import app.focal.gallery.timedParagraphs
import app.focal.ui.note.EditBackend
import app.focal.ui.note.NoteActions
import app.focal.ui.state.BlankParagraph
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.WriteQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * A note's paragraphs in memory with the store's edit rules (NoteStore + ParagraphEdits): revisions, trimming, Enter
 * putting the text before the caret in a new paragraph before the split one (which keeps its id), Backspace joining
 * into the second paragraph, an undone split written back exactly. The page reloads from it ([state]). While [hold] is
 * set every write waits for it, as a busy database holds the store's writes; [failNext] fails the next write, as a full
 * disk does. Paragraph texts are synthetic.
 */
internal class EditStore(texts: List<String>) : EditBackend {
    /** Every paragraph in order, empty ones included (the state lists those apart). */
    var paragraphs by mutableStateOf(timedParagraphs(texts, 60_000))
    private var nextId = texts.size + 1L
    @Volatile var hold: CompletableDeferred<Unit>? = null
    @Volatile var failNext = false
    val held = AtomicInteger()
    /** Writes that changed a paragraph. */
    val writes = AtomicInteger()

    fun text(id: Long): String? = paragraphs.firstOrNull { it.id == id }?.text
    fun texts(): List<String> = paragraphs.map { it.text }

    /** The note's state as its view model reads it: visible paragraphs, and the empty ones apart. */
    fun state(noteId: String, title: String): NoteUiState = NoteUiState(
        noteId = noteId, loaded = true, title = title, titleIsCustom = true, created = 0, durationMs = 60_000,
        phase = NotePhase.READY, audioPresent = true, canPlay = true, editable = true,
        paragraphs = paragraphs.filter { it.text.isNotBlank() },
        blankParagraphs = paragraphs.withIndex().filter { it.value.text.isBlank() }
            .map { (index, paragraph) -> BlankParagraph(paragraph, paragraphs.getOrNull(index - 1)?.id) },
    )

    /** Waits while the store is held; then a failed write's outcome, or null to go on. */
    private suspend fun busy(): EditOutcome? {
        hold?.let { gate ->
            held.incrementAndGet()
            gate.await()
        }
        if (!failNext) return null
        failNext = false
        return EditOutcome.Failed("Synthetic full disk.")
    }

    override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome {
        busy()?.let { return it }
        val current = paragraphs.firstOrNull { it.id == id } ?: return EditOutcome.Stale
        if (current.revision != revision) return EditOutcome.Stale
        writes.incrementAndGet()
        val saved = current.copy(text = text.trim(), revision = revision + 1, edited = true, words = emptyList())
        paragraphs = paragraphs.map { if (it.id == id) saved else it }
        return EditOutcome.Saved(listOf(saved))
    }

    override suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome {
        busy()?.let { return it }
        val current = paragraphs.firstOrNull { it.id == id } ?: return EditOutcome.Stale
        if (current.revision != revision) return EditOutcome.Stale
        writes.incrementAndGet()
        val origin = SplitOrigin(current.text, current.text, null, current.startMs, current.endMs, current.edited)
        val added = current.copy(id = nextId++, revision = 1, text = current.text.substring(0, offset).trimEnd(), words = emptyList())
        val kept = current.copy(revision = revision + 1, text = current.text.substring(offset).trimStart(), words = emptyList())
        paragraphs = paragraphs.flatMap { if (it.id == id) listOf(added, kept) else listOf(it) }
        return EditOutcome.Saved(listOf(added, kept), origin)
    }

    override suspend fun unsplit(keepId: Long, keepRevision: Long, removeId: Long, removeRevision: Long, offset: Int, origin: SplitOrigin): EditOutcome {
        busy()?.let { return it }
        val keep = paragraphs.firstOrNull { it.id == keepId } ?: return EditOutcome.Stale
        val remove = paragraphs.firstOrNull { it.id == removeId } ?: return EditOutcome.Stale
        if (keep.revision != keepRevision || remove.revision != removeRevision) return EditOutcome.Stale
        if (origin.text.substring(0, offset).trimEnd() != remove.text || origin.text.substring(offset).trimStart() != keep.text) return EditOutcome.Stale
        writes.incrementAndGet()
        val restored = keep.copy(text = origin.text, revision = keepRevision + 1, edited = origin.edited, words = emptyList())
        paragraphs = paragraphs.filter { it.id != removeId }.map { if (it.id == keepId) restored else it }
        return EditOutcome.Saved(listOf(restored))
    }

    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome {
        busy()?.let { return it }
        val first = paragraphs.firstOrNull { it.id == firstId } ?: return EditOutcome.Stale
        val second = paragraphs.firstOrNull { it.id == secondId } ?: return EditOutcome.Stale
        if (first.revision != firstRevision || second.revision != secondRevision) return EditOutcome.Stale
        writes.incrementAndGet()
        val text = listOf(first.text.trim(), second.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val joined = second.copy(text = text, revision = secondRevision + 1, words = emptyList())
        paragraphs = paragraphs.filter { it.id != firstId }.map { if (it.id == secondId) joined else it }
        return EditOutcome.Saved(listOf(joined))
    }

    override suspend fun restore(id: Long, revision: Long) = EditOutcome.NotEditable
}

/** The page's actions with Edit's writes in [scope], which outlives the page as the note's view model's does. */
internal fun lastingWrites(scope: CoroutineScope): NoteActions = NoteActions(writes = WriteQueue(scope))
