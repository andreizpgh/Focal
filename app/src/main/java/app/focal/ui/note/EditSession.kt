package app.focal.ui.note

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.focal.data.SplitOrigin
import app.focal.ui.state.BlankParagraph
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.ParagraphUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The store operations an edit session runs: the note's view model, or a fake in tests. */
interface EditBackend {
    suspend fun edit(id: Long, revision: Long, text: String): EditOutcome

    /**
     * Enter at [offset] of [id]'s stored text: the text before it becomes a new paragraph before [id], which keeps the
     * rest. [EditOutcome.Saved] lists the new part first, and gives [id]'s [EditOutcome.Saved.origin].
     */
    suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome

    /** Undoes that split exactly: [keepId] takes back [origin], and [removeId], the part before it, goes. */
    suspend fun unsplit(keepId: Long, keepRevision: Long, removeId: Long, removeRevision: Long, offset: Int, origin: SplitOrigin): EditOutcome

    /** Backspace at the start of [secondId]: [firstId], the paragraph before it, joins it and goes. */
    suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome

    suspend fun restore(id: Long, revision: Long): EditOutcome
}

/** One undoable step of an edit session (spec 5.7: typing, split, merge, restore and Replace all share one log). */
sealed interface EditStep {
    /** Typing up to an autosave, or Restore original: the paragraph's stored text before and after. */
    data class Text(val id: Long, val before: String, val after: String) : EditStep

    /**
     * Enter at [offset] of [id]'s stored text: the text before it became [newId], and [id] kept the rest. [origin] is
     * [id] as it was, which Undo writes back exactly (joining the parts would put one space where the split was).
     */
    data class Split(val id: Long, val offset: Int, val newId: Long, val origin: SplitOrigin) : EditStep

    /** Backspace at the start of [secondId] joined [firstId], whose text was [offset] long, into it. */
    data class Merge(val firstId: Long, val secondId: Long, val offset: Int) : EditStep

    /** Replace all, inside Edit: every paragraph it changed. */
    data class Replace(val changes: List<Text>) : EditStep
}

/**
 * A session's undo and redo log and the ids its paragraphs moved to, kept across recreation (rotation, process death)
 * so Undo goes on where it was ([EditSession.log], [EditSession.begin]).
 */
data class EditLog(val undo: List<EditStep>, val redo: List<EditStep>, val alias: Map<Long, Long>) {
    /**
     * As a flat list of strings and numbers, which a saved-state Bundle holds. The newest steps that fit in [budget]
     * characters of paragraph text are kept, so a log of many large Replace alls cannot overrun the Bundle's
     * transaction limit; Undo then reaches back as far as what was kept.
     */
    fun encode(budget: Int = SAVED_TEXT_BUDGET): ArrayList<Any> {
        var left = budget
        fun keep(steps: List<EditStep>): List<EditStep> {
            val kept = ArrayList<EditStep>()
            for (step in steps.asReversed()) {
                val cost = step.textLength()
                if (cost > left) break
                left -= cost
                kept += step
            }
            return kept.asReversed()
        }
        val undoKept = keep(undo)
        val redoKept = keep(redo)
        val out = ArrayList<Any>()
        out += undoKept.size
        out += redoKept.size
        out += alias.size
        (undoKept + redoKept).forEach { it.encodeInto(out) }
        alias.forEach { (from, to) -> out += from; out += to }
        return out
    }

    companion object {
        /** About 64 KB of UTF-16: well under the Bundle's limit with the page's other saved state. */
        const val SAVED_TEXT_BUDGET = 32_000

        /** The log [encode] wrote, or null for anything else. */
        fun decode(saved: List<*>?): EditLog? = runCatching {
            saved ?: return null
            val read = saved.iterator()
            val undoCount = read.next() as Int
            val redoCount = read.next() as Int
            val aliasCount = read.next() as Int
            val undo = List(undoCount) { decodeStep(read) }
            val redo = List(redoCount) { decodeStep(read) }
            val alias = HashMap<Long, Long>()
            repeat(aliasCount) { alias[read.next() as Long] = read.next() as Long }
            if (read.hasNext()) null else EditLog(undo, redo, alias)
        }.getOrNull()
    }
}

private fun EditStep.textLength(): Int = when (this) {
    is EditStep.Text -> before.length + after.length
    is EditStep.Split -> origin.text.length + origin.sourceText.orEmpty().length + origin.wordsJson.orEmpty().length
    is EditStep.Merge -> 0
    is EditStep.Replace -> changes.sumOf { it.before.length + it.after.length }
}

/** The paragraphs a step names. */
private fun EditStep.ids(): List<Long> = when (this) {
    is EditStep.Text -> listOf(id)
    is EditStep.Split -> listOf(id, newId)
    is EditStep.Merge -> listOf(firstId, secondId)
    is EditStep.Replace -> changes.map { it.id }
}

private fun EditStep.encodeInto(out: ArrayList<Any>) {
    when (this) {
        is EditStep.Text -> { out += "T"; out += id; out += before; out += after }
        is EditStep.Split -> {
            out += "S"; out += id; out += offset; out += newId
            out += origin.text
            out += (origin.sourceText != null); out += origin.sourceText.orEmpty()
            out += (origin.wordsJson != null); out += origin.wordsJson.orEmpty()
            out += origin.startMs; out += origin.endMs; out += origin.edited
        }
        is EditStep.Merge -> { out += "M"; out += firstId; out += secondId; out += offset }
        is EditStep.Replace -> {
            out += "R"
            out += changes.size
            changes.forEach { out += it.id; out += it.before; out += it.after }
        }
    }
}

private fun decodeStep(read: Iterator<Any?>): EditStep = when (read.next()) {
    "T" -> EditStep.Text(read.next() as Long, read.next() as String, read.next() as String)
    "S" -> {
        val id = read.next() as Long
        val offset = read.next() as Int
        val newId = read.next() as Long
        val text = read.next() as String
        fun nullable(): String? = (read.next() as Boolean).let { known -> (read.next() as String).takeIf { known } }
        val source = nullable()
        val words = nullable()
        EditStep.Split(id, offset, newId, SplitOrigin(text, source, words, read.next() as Long, read.next() as Long, read.next() as Boolean))
    }
    "M" -> EditStep.Merge(read.next() as Long, read.next() as Long, read.next() as Int)
    "R" -> EditStep.Replace(List(read.next() as Int) { EditStep.Text(read.next() as Long, read.next() as String, read.next() as String) })
    else -> error("Not an edit step.")
}

/** Where the caret goes after a step: paragraph [id] at [caret]. */
data class CaretAt(val id: Long, val caret: Int)

/** The focused field as a write reads it: its paragraph and the text it holds now. */
data class FieldText(val id: Long, val text: String)

/** What Backspace at the start of a paragraph does ([EditSession.join]), decided at once. */
sealed interface JoinResult {
    /** The paragraph before joins it: the field takes [prefix] in front of its text, the caret goes to [caret]. */
    data class Joined(val prefix: String, val caret: Int) : JoinResult
    /** The store keeps the two apart ([EditOutcome.CannotMerge]): both stay, the draft is saved, the caret stays. */
    data object Refused : JoinResult
    /** Nothing to join it to: the first paragraph. */
    data object Skipped : JoinResult
}

/**
 * An Edit session over one note (spec 5.7). The page changes at once and the store follows, one write at a time:
 *
 * - Enter and Backspace change what the page shows the moment they are pressed ([split], [join]); so does a caret
 *   that leaves a paragraph with its draft ([leave]). Each is kept as a change the store does not have yet, in the
 *   order it happened, and drawn over the stored paragraphs ([paragraphs]). Typing goes on in the same field: Enter
 *   leaves it on the text after the caret and puts the text before it in a new paragraph above (the store keeps the
 *   field's paragraph id with the second part), and Backspace puts the paragraph before it in front of the field's
 *   text (the store keeps the field's id with the joined text).
 * - Every write ([save], [undo], [redo], [restore], [undoReplace]) first stores those changes in order, then the
 *   focused [field] as it is when the write runs. A change that fails stays shown and is tried again by the next
 *   write (Retry, spec 4.1.3); nothing typed is dropped. The page runs the writes one at a time, in the order they
 *   were asked for ([app.focal.ui.state.WriteQueue]); a lock keeps them apart here too.
 *
 * The session keeps the undo and redo log (the store keeps none) and lays the paragraphs it saved over the note's
 * state until that state reloads with them, so nothing flickers back for a frame. While its order is in force it also
 * places paragraphs the state does not list: an empty one (Enter at the end of a paragraph), which the state omits.
 *
 * Ids change when an undo or redo brings a paragraph back (the store gives it a new id) and when a split the page
 * showed reaches the store (the page's stand-in id becomes the stored one). Every id that led to a paragraph is
 * redirected along with it ([resolve]), so every step of the log, however often it was undone and redone, still
 * finds its paragraph.
 */
@Stable
class EditSession(private val backend: EditBackend) {
    /** The focused field, read when a write runs so it stores what the field holds then (set by the page). */
    var field: () -> FieldText? = { null }

    /**
     * A join the store refused after the page had shown it (the page could not tell beforehand): [prefix], the
     * paragraph that stays apart, comes back off the start of the field of [id] (set by the page).
     */
    var joinRefused: (id: Long, prefix: String) -> Unit = { _, _ -> }

    private val mutex = Mutex()
    private var state: List<ParagraphUi> = emptyList()
    private val overrides = mutableStateMapOf<Long, ParagraphUi>()
    private val alias = HashMap<Long, Long>()

    /** The session's paragraph order, or null when the state's own order is in force. */
    private var order by mutableStateOf<List<Long>?>(null)

    /** What the page shows that the store does not have yet, oldest first. */
    private val changes = mutableStateListOf<Change>()

    /** The next stand-in id for the text before a split the store has not made yet (stored ids are positive). */
    private var nextTemp = -1L

    /** Changes the page made so far ([split], [join], [leave]): a write that reseeds the field checks none came since. */
    var changesMade = 0
        private set

    private val undoSteps = mutableStateListOf<EditStep>()
    private val redoSteps = mutableStateListOf<EditStep>()

    val canUndo: Boolean get() = undoSteps.isNotEmpty()
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    /** The last write failed: "Not saved", and Done reads "Retry" (spec 4.1.3). */
    var failed by mutableStateOf(false)
        private set

    /** Writes that failed in a row: the second one also says so in the feedback pill. */
    var failures by mutableIntStateOf(0)
        private set

    /** A session is running: [begin] was called and [end] was not. */
    var started by mutableStateOf(false)
        private set

    /** A change the page shows ahead of the store. Ids are the page's, resolved when the change is stored. */
    private sealed interface Change {
        /** The paragraph whose text the change sets. */
        val id: Long

        /** The caret left paragraph [id]; its field held [text]. */
        data class Text(override val id: Long, val text: String) : Change

        /** Enter at [at] of [full], [id]'s field text: the text before it is shown as [temp] until the store splits. */
        data class Split(override val id: Long, val full: String, val at: Int, val temp: Long) : Change

        /** Backspace at the start of [id], whose field held [text]: [first] went in front of it as [prefix]. */
        data class Join(val first: Long, override val id: Long, val text: String, val prefix: String) : Change
    }

    /**
     * Starts a session on the note's current paragraphs: their order becomes the session's. A session that the page
     * had before it was recreated goes on with its [log], and gets back the empty paragraphs it had made from [blank]
     * (the state lists text only): the ones its log names and the one the field [fieldId] is typing in.
     */
    fun begin(paragraphs: List<ParagraphUi>, log: EditLog? = null, blank: List<BlankParagraph> = emptyList(), fieldId: Long? = null) {
        started = true
        state = paragraphs
        undoSteps.clear()
        redoSteps.clear()
        changes.clear()
        if (log != null) {
            alias.putAll(log.alias)
            undoSteps.addAll(log.undo)
            redoSteps.addAll(log.redo)
        }
        val ids = paragraphs.mapTo(ArrayList()) { it.id }
        val own = HashSet<Long>()
        (undoSteps + redoSteps).forEach { step -> step.ids().forEach { own += resolve(it) } }
        fieldId?.let { own += resolve(it) }
        val after = blank.associate { it.paragraph.id to it.after }
        for (empty in blank) {
            val id = empty.paragraph.id
            if (id !in own || id in ids) continue
            overrides[id] = empty.paragraph
            // After the paragraph before it, or the nearest one before that the session shows.
            var anchor = empty.after
            while (anchor != null && anchor !in ids) anchor = after[anchor]
            ids.add(if (anchor == null) 0 else ids.indexOf(anchor) + 1, id)
        }
        order = ids
        nextTemp = minOf(-1L, (alias.keys.minOrNull() ?: 0L) - 1)
        failed = false
        failures = 0
    }

    /**
     * The running session's log, for the page's saved state. The focused field's text joins it as its newest step when
     * the session has not stored it yet: the field's last save starts as the page stops, and its step is logged only
     * once the write returns, after the state has been saved. The restored page then finds that text already stored and
     * logs nothing for it. (With changes still on their way the log is left as it is: they log their own steps.)
     */
    fun log(): EditLog? {
        if (!started) return null
        val draft = field()
        // Read on the main thread, where every write's result is applied and logged in one go.
        val stored = draft?.let { paragraphLocked(it.id) }
        val text = draft?.text?.trim()
        val pending = if (changes.isEmpty() && stored != null && text != null && text != stored.text) {
            EditStep.Text(stored.id, stored.text, text)
        } else null
        // A new step clears redo, as the save will.
        return if (pending == null) EditLog(undoSteps.toList(), redoSteps.toList(), HashMap(alias))
        else EditLog(undoSteps.toList() + pending, emptyList(), HashMap(alias))
    }

    /** Edit ended: the log goes; saved paragraphs stay laid over the state until it catches up ([sync]). */
    fun end() {
        started = false
        undoSteps.clear()
        redoSteps.clear()
        changes.clear()
        failed = false
        failures = 0
    }

    /**
     * The note's state reloaded. Saved paragraphs it now holds at their revision stop overriding it; the session's
     * order is dropped once the state lists every paragraph with text in it.
     */
    fun sync(paragraphs: List<ParagraphUi>, editing: Boolean) {
        state = paragraphs
        val byId = paragraphs.associateBy { it.id }
        overrides.keys.filter { id -> byId[id]?.let { it.revision >= overrides.getValue(id).revision } == true }.forEach { overrides.remove(it) }
        val current = order ?: return
        if (editing || changes.isNotEmpty()) return
        val shown = current.filter { id -> (overrides[id] ?: byId[id])?.text?.isNotBlank() == true }
        if (overrides.isEmpty() && shown == paragraphs.map { it.id }) order = null
    }

    /** The paragraphs to draw: the state with the session's saved paragraphs, its order and its changes laid over it. */
    fun paragraphs(paragraphs: List<ParagraphUi>): List<ParagraphUi> {
        val stored = stored(paragraphs)
        if (changes.isEmpty()) return stored
        val shown = stored.toMutableList()
        changes.forEach { shown.show(it) }
        return shown
    }

    /** The paragraph [id] as the page shows it. */
    fun paragraph(id: Long): ParagraphUi? = paragraphs(state).firstOrNull { it.id == resolve(id) }

    /** Every paragraph's stored text, by id: what a Replace all starts from. */
    fun storedTexts(): Map<Long, String> = stored(state).associate { it.id to it.text }

    /**
     * Enter at [at] of [full], the field's text for paragraph [id]. The page shows it at once: the text before the
     * caret as a new paragraph above, the field keeping the rest ([splitTexts]). The next write splits the store.
     */
    fun split(id: Long, full: String, at: Int) {
        changes += Change.Split(id, full, at.coerceIn(0, full.length), nextTemp--)
        changesMade++
    }

    /**
     * Backspace at the start of [id], whose field holds [text]: the paragraph shown before it joins it at once, with
     * one space unless the text starts with one. Refused, with nothing changed, when the store keeps the two apart (a
     * known recognizer original beside text whose original is unknown); the next write joins them in the store.
     */
    fun join(id: Long, text: String): JoinResult {
        val shown = paragraphs(state)
        val index = shown.indexOfFirst { it.id == resolve(id) }
        val previous = shown.getOrNull(index - 1) ?: return JoinResult.Skipped
        if (keptApart(previous, previous.text, shown[index], text)) return JoinResult.Refused
        val first = previous.text.trim()
        val prefix = if (first.isEmpty() || text.isBlank() || text.first().isWhitespace()) first else "$first "
        changes += Change.Join(previous.id, id, text, prefix)
        changesMade++
        return JoinResult.Joined(prefix, prefix.length + (text.length - text.trimStart().length))
    }

    /** The caret left paragraph [id] for another one: [text], what its field held, is shown and stored in turn. */
    fun leave(id: Long, text: String) {
        if (paragraph(id)?.text == text) return
        changes += Change.Text(id, text)
        changesMade++
    }

    /**
     * Stores everything the page shows that the store does not have yet: the changes in the order they were made,
     * then the focused field's text as it is now. True when all of it is stored. On a failure the rest stays shown
     * and the next write tries again; a field whose paragraph the session does not know is a failure, never dropped.
     */
    suspend fun save(): Boolean = mutex.withLock { saveLocked() }

    /** Restore original (spec 4.14) for the focused paragraph: the recognizer's text back, as one undoable step. */
    suspend fun restore(): CaretAt? = mutex.withLock {
        if (!saveLocked()) return@withLock null
        val id = field()?.id?.let(::resolve) ?: return@withLock null
        val before = paragraphLocked(id)?.text ?: return@withLock null
        val outcome = backend.restore(id, revision(id))
        if (outcome !is EditOutcome.Saved) return@withLock fail()
        apply(outcome.paragraphs)
        val after = paragraphLocked(id)?.text ?: return@withLock null
        if (after != before) push(EditStep.Text(id, before, after))
        succeed()
        CaretAt(id, after.length)
    }

    /** Replace all ran inside Edit: its paragraphs as stored, and their texts before ([before], by id). */
    suspend fun recordReplace(saved: List<ParagraphUi>, before: Map<Long, String>) = mutex.withLock {
        apply(saved)
        val replaced = saved.mapNotNull { after -> before[after.id]?.takeIf { it != after.text }?.let { EditStep.Text(after.id, it, after.text) } }
        if (replaced.isNotEmpty()) push(EditStep.Replace(replaced))
    }

    /** Paragraphs saved outside the log (Replace all and its Undo while reading): drawn at once, before the reload. */
    fun applySaved(saved: List<ParagraphUi>) = apply(saved)

    /** "Replaced N · Undo" inside Edit: undoes the Replace all if it is still the last step. */
    suspend fun undoReplace(): Boolean = mutex.withLock {
        if (!saveLocked() || undoSteps.lastOrNull() !is EditStep.Replace) return@withLock false
        undoLocked() != null
    }

    /** Undoes the last step once everything shown is stored; the caret goes where it happened. Null: nothing undone. */
    suspend fun undo(): CaretAt? = mutex.withLock { if (saveLocked()) undoLocked() else null }

    /** Redoes the last undone step once everything shown is stored. */
    suspend fun redo(): CaretAt? = mutex.withLock {
        if (!saveLocked()) return@withLock null
        val step = redoSteps.lastOrNull() ?: return@withLock null
        val caret = run(step, forward = true) ?: return@withLock null
        redoSteps.removeAt(redoSteps.lastIndex)
        undoSteps.add(step)
        caret
    }

    /** The id paragraph [id] is stored under now, following every id it moved to. */
    fun resolve(id: Long): Long {
        var at = id
        repeat(alias.size + 1) { at = alias[at] ?: return at }
        return at
    }

    private suspend fun saveLocked(): Boolean {
        if (!started || !flushLocked()) return false
        val draft = field() ?: return true
        return saveTextLocked(draft.id, draft.text)
    }

    private suspend fun undoLocked(): CaretAt? {
        val step = undoSteps.lastOrNull() ?: return null
        val caret = run(step, forward = false) ?: return null
        undoSteps.removeAt(undoSteps.lastIndex)
        redoSteps.add(step)
        return caret
    }

    /** Stores the changes in order; false (and the rest kept) at the first that fails. */
    private suspend fun flushLocked(): Boolean {
        while (true) {
            val change = changes.firstOrNull() ?: return true
            val stored = when (change) {
                is Change.Text -> saveTextLocked(change.id, change.text)
                is Change.Split -> splitLocked(change)
                is Change.Join -> joinLocked(change)
            }
            if (!stored) return false
            // Applied with the write's result in one go: no frame draws the change twice or not at all.
            changes.removeAt(0)
        }
    }

    /** Stores [draft] as [id]'s text, one undoable step. True when it is stored (or nothing changed). */
    private suspend fun saveTextLocked(id: Long, draft: String): Boolean {
        val current = paragraphLocked(id) ?: return failed()
        val text = draft.trim()
        if (text == current.text) {
            succeed()
            return true
        }
        // A stale revision fails like storage does: the draft stays, and Retry writes it once the state reloaded.
        val outcome = backend.edit(current.id, revision(current.id), draft)
        if (outcome !is EditOutcome.Saved) return failed()
        apply(outcome.paragraphs)
        val after = paragraphLocked(current.id)?.text ?: text
        if (after != current.text) push(EditStep.Text(current.id, current.text, after))
        succeed()
        return true
    }

    private suspend fun splitLocked(change: Change.Split): Boolean {
        val target = resolve(change.id)
        // The draft before Enter is its own step, as an autosave would have made it.
        if (!saveTextLocked(target, change.full)) return false
        val stored = paragraphLocked(target) ?: return failed()
        val lead = change.full.length - change.full.trimStart().length
        val offset = (change.at - lead).coerceIn(0, stored.text.length)
        val (added, origin) = splitStoreLocked(target, offset) ?: return false
        rename(change.temp, added)
        push(EditStep.Split(target, offset, added, origin))
        return true
    }

    private suspend fun joinLocked(change: Change.Join): Boolean {
        val first = resolve(change.first)
        val second = resolve(change.id)
        if (!saveTextLocked(second, change.text)) return false
        val firstText = paragraphLocked(first)?.text ?: return failed()
        when (mergeLocked(first, second)) {
            // The page joined them; the store would lose an original. The paragraph comes back, nothing failed: the
            // field (or a draft the caret left since) loses the text that went in front of it.
            Merge.Refused -> {
                for (index in 1 until changes.size) {
                    val later = changes[index]
                    if (later is Change.Text && resolve(later.id) == second && later.text.startsWith(change.prefix)) {
                        changes[index] = later.copy(text = later.text.removePrefix(change.prefix))
                    }
                }
                joinRefused(change.id, change.prefix)
            }
            Merge.Failed -> return false
            Merge.Done -> push(EditStep.Merge(first, second, firstText.length))
        }
        return true
    }

    /** Splits [id] at [offset] in the store: the new part's id and [id]'s origin, or null when the write failed. */
    private suspend fun splitStoreLocked(id: Long, offset: Int): Pair<Long, SplitOrigin>? {
        val outcome = backend.split(id, revision(id), offset)
        if (outcome !is EditOutcome.Saved) return fail()
        val origin = outcome.origin ?: return fail()
        val added = outcome.paragraphs.firstOrNull { it.id != id } ?: return fail()
        apply(outcome.paragraphs)
        insertBefore(id, added.id)
        succeed()
        return added.id to origin
    }

    private enum class Merge { Done, Refused, Failed }

    private suspend fun mergeLocked(first: Long, second: Long): Merge {
        val outcome = backend.merge(first, revision(first), second, revision(second))
        // A refused merge is not a failed write: both paragraphs stay and the caret does not move.
        if (outcome == EditOutcome.CannotMerge) return Merge.Refused
        if (outcome !is EditOutcome.Saved) {
            fail()
            return Merge.Failed
        }
        apply(outcome.paragraphs)
        drop(first)
        succeed()
        return Merge.Done
    }

    private suspend fun run(step: EditStep, forward: Boolean): CaretAt? = when (step) {
        is EditStep.Text -> {
            val text = if (forward) step.after else step.before
            val previous = if (forward) step.before else step.after
            if (write(step.id, text)) CaretAt(resolve(step.id), changeEnd(previous, text)) else null
        }
        // Redo splits again where it was; the text before it comes back under a new id.
        is EditStep.Split -> if (forward) {
            val target = resolve(step.id)
            splitStoreLocked(target, step.offset)?.let { (added, _) ->
                rename(step.newId, added)
                CaretAt(target, 0)
            }
        } else unsplitLocked(step)
        is EditStep.Merge -> if (forward) {
            val first = resolve(step.firstId)
            val second = resolve(step.secondId)
            val firstText = paragraphLocked(first)?.text.orEmpty()
            val secondText = paragraphLocked(second)?.text.orEmpty()
            if (mergeLocked(first, second) == Merge.Done) CaretAt(second, joinPoint(firstText, secondText)) else null
        } else {
            // The joined texts were trimmed with one space between: split where the first ended gives both back.
            val target = resolve(step.secondId)
            splitStoreLocked(target, step.offset)?.let { (added, _) ->
                rename(step.firstId, added)
                CaretAt(target, 0)
            }
        }
        is EditStep.Replace -> {
            val ok = step.changes.all { write(it.id, if (forward) it.after else it.before) }
            if (ok) CaretAt(resolve(step.changes.first().id), 0) else null
        }
    }

    /** Undo of Enter: the paragraph as it was before the split, written back whole; the caret where Enter was. */
    private suspend fun unsplitLocked(step: EditStep.Split): CaretAt? {
        val keep = resolve(step.id)
        val remove = resolve(step.newId)
        val outcome = backend.unsplit(keep, revision(keep), remove, revision(remove), step.offset, step.origin)
        if (outcome !is EditOutcome.Saved) return fail()
        apply(outcome.paragraphs)
        drop(remove)
        succeed()
        return CaretAt(keep, step.offset)
    }

    /** Writes [text] as [id]'s text without logging it (undo and redo). */
    private suspend fun write(id: Long, text: String): Boolean {
        val target = resolve(id)
        if (paragraphLocked(target)?.text == text) return true
        val outcome = backend.edit(target, revision(target), text)
        if (outcome !is EditOutcome.Saved) return failed()
        apply(outcome.paragraphs)
        succeed()
        return true
    }

    /** The stored paragraphs in the session's order, without its changes. */
    private fun stored(paragraphs: List<ParagraphUi>): List<ParagraphUi> {
        val ids = order
        if (ids == null && overrides.isEmpty()) return paragraphs
        val byId = paragraphs.associateBy { it.id }
        fun pick(id: Long): ParagraphUi? {
            val saved = overrides[id]
            val loaded = byId[id]
            return if (saved != null && (loaded == null || saved.revision > loaded.revision)) saved else loaded
        }
        if (ids == null) return paragraphs.map { pick(it.id) ?: it }
        val placed = ids.mapNotNullTo(ArrayList(ids.size)) { pick(it) }
        // A paragraph the session never saw (not expected while it runs) keeps its place after its predecessor.
        val known = ids.toHashSet()
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.id in known) return@forEachIndexed
            val before = paragraphs.getOrNull(index - 1)?.id
            val at = placed.indexOfFirst { it.id == before }
            placed.add(at + 1, paragraph)
        }
        return placed
    }

    /** Draws [change] over the paragraphs before it. */
    private fun MutableList<ParagraphUi>.show(change: Change) {
        val at = indexOfFirst { it.id == resolve(change.id) }
        if (at < 0) return
        val row = this[at]
        when (change) {
            is Change.Text -> this[at] = row.drafted(change.text)
            is Change.Split -> {
                val (before, after) = splitTexts(change.full, change.at)
                this[at] = row.drafted(after)
                add(at, row.drafted(before).copy(id = change.temp, revision = 0, edited = true, canRestore = false, open = false))
            }
            is Change.Join -> {
                this[at] = row.drafted(change.prefix + change.text)
                val first = indexOfFirst { it.id == resolve(change.first) }
                if (first >= 0) removeAt(first)
            }
        }
    }

    private fun paragraphLocked(id: Long): ParagraphUi? {
        val target = resolve(id)
        return overrides[target]?.let { saved ->
            val loaded = state.firstOrNull { it.id == target }
            if (loaded != null && loaded.revision >= saved.revision) loaded else saved
        } ?: state.firstOrNull { it.id == target }
    }

    private fun revision(id: Long): Long =
        maxOf(overrides[id]?.revision ?: Long.MIN_VALUE, state.firstOrNull { it.id == id }?.revision ?: Long.MIN_VALUE)

    private fun apply(saved: List<ParagraphUi>) {
        saved.forEach { overrides[it.id] = it }
    }

    /** Paragraph [id] is gone from the store (joined, or a split undone). */
    private fun drop(id: Long) {
        overrides.remove(id)
        order = order?.filter { it != id }
    }

    private fun insertBefore(anchor: Long, id: Long) {
        val current = order ?: stored(state).map { it.id }
        if (id in current) return
        val at = current.indexOf(anchor)
        order = current.toMutableList().apply { add(if (at < 0) size else at, id) }
    }

    /**
     * Paragraph [from] is stored as [to] now (a split reached the store, or an undo or redo brought a paragraph back).
     * The id [from] last led to is redirected, so every older id that led there, from any step, follows.
     */
    private fun rename(from: Long, to: Long) {
        val current = resolve(from)
        if (current != to && resolve(to) != current) alias[current] = to
    }

    private fun push(step: EditStep) {
        undoSteps.add(step)
        redoSteps.clear()
    }

    private fun succeed() {
        failed = false
        failures = 0
    }

    /** Marks the write failed ("Not saved"); returns null for the caller's result. */
    private fun fail(): Nothing? {
        failed = true
        failures++
        return null
    }

    /** Marks the write failed; false for the caller's result. */
    private fun failed(): Boolean {
        fail()
        return false
    }

    companion object {
        /** Where the second text begins once two paragraphs are joined with one space. */
        fun joinPoint(first: String, second: String): Int =
            if (first.isBlank() || second.isBlank()) first.trim().length else first.trim().length + 1

        /** Enter at [at] of [text]: the text before the caret, trimmed, and the text after it without leading spaces. */
        fun splitTexts(text: String, at: Int): Pair<String, String> {
            val cut = at.coerceIn(0, text.length)
            return text.substring(0, cut).trim() to text.substring(cut).trimStart()
        }

        /** Where a change from [before] to [after] ends in [after]: the caret after an undo or redo. */
        fun changeEnd(before: String, after: String): Int {
            var prefix = 0
            val limit = minOf(before.length, after.length)
            while (prefix < limit && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
            return after.length - suffix
        }

        /** The store keeps these apart: text whose original is unknown beside a known recognizer original. */
        private fun keptApart(first: ParagraphUi, firstText: String, second: ParagraphUi, secondText: String): Boolean {
            val unknownText = (!first.originKnown && firstText.isNotBlank()) || (!second.originKnown && secondText.isNotBlank())
            return unknownText && (first.hasOriginal || second.hasOriginal)
        }
    }
}

/** The paragraph showing [text], which the store does not have yet: its words' places no longer fit it. */
private fun ParagraphUi.drafted(text: String): ParagraphUi =
    if (text == this.text) this else copy(text = text, words = emptyList(), exactTimes = false)
