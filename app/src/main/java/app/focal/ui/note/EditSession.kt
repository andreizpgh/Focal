package app.focal.ui.note

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.ParagraphUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The store operations an edit session runs: the note's view model, or a fake in tests. */
interface EditBackend {
    suspend fun edit(id: Long, revision: Long, text: String): EditOutcome
    suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome
    suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome
    suspend fun restore(id: Long, revision: Long): EditOutcome
}

/** One undoable step of an edit session (spec 5.7: typing, split, merge, restore and Replace all share one log). */
sealed interface EditStep {
    /** Typing up to an autosave, or Restore original: the paragraph's stored text before and after. */
    data class Text(val id: Long, val before: String, val after: String) : EditStep
    /** Enter at [offset] of [id]'s stored text made [newId]. */
    data class Split(val id: Long, val offset: Int, val newId: Long) : EditStep
    /** Backspace at the start of [secondId] joined it into [firstId], whose text was [offset] long. */
    data class Merge(val firstId: Long, val secondId: Long, val offset: Int) : EditStep
    /** Replace all, inside Edit: every paragraph it changed. */
    data class Replace(val changes: List<Text>) : EditStep
}

/**
 * A session's undo and redo log and the ids that undoing merges replaced, kept across recreation (rotation, process
 * death) so Undo goes on where it was ([EditSession.log], [EditSession.begin]).
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
    is EditStep.Split, is EditStep.Merge -> 0
    is EditStep.Replace -> changes.sumOf { it.before.length + it.after.length }
}

private fun EditStep.encodeInto(out: ArrayList<Any>) {
    when (this) {
        is EditStep.Text -> { out += "T"; out += id; out += before; out += after }
        is EditStep.Split -> { out += "S"; out += id; out += offset; out += newId }
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
    "S" -> EditStep.Split(read.next() as Long, read.next() as Int, read.next() as Long)
    "M" -> EditStep.Merge(read.next() as Long, read.next() as Long, read.next() as Int)
    "R" -> EditStep.Replace(List(read.next() as Int) { EditStep.Text(read.next() as Long, read.next() as String, read.next() as String) })
    else -> error("Not an edit step.")
}

/** Where the caret goes after a step: paragraph [id] at [caret]. */
data class CaretAt(val id: Long, val caret: Int)

/** What Backspace at the start of a paragraph did ([EditSession.mergeIntoPrevious]). */
sealed interface JoinResult {
    /** It joined the paragraph before it; the caret goes to [caret]. */
    data class Joined(val caret: CaretAt) : JoinResult
    /** The store keeps the two apart ([EditOutcome.CannotMerge]): both stay, the draft is saved, the caret stays. */
    data object Refused : JoinResult
    /** Nothing to join it to (the first paragraph), or a write failed ("Not saved" says so). */
    data object Skipped : JoinResult
}

/**
 * An Edit session over one note (spec 5.7). It runs every write through [backend] one at a time, keeps the session's
 * undo and redo log (the store keeps none), and lays the paragraphs it saved over the note's state until that state
 * reloads with them, so a split, a merge or an autosave never flickers back for a frame. While a session's order is
 * in force it also places paragraphs the state does not list yet: Enter at the end of a paragraph makes an empty one,
 * which the state omits (it shows visible paragraphs only) until it has text.
 *
 * Ids change when a merge is undone (the store gives the restored half a new id); older steps reach the new id
 * through an alias table, so the whole log stays valid.
 */
@Stable
class EditSession(private val backend: EditBackend) {
    private val mutex = Mutex()
    private var state: List<ParagraphUi> = emptyList()
    private val overrides = mutableStateMapOf<Long, ParagraphUi>()
    private val alias = HashMap<Long, Long>()

    /** The session's paragraph order, or null when the state's own order is in force. */
    private var order by mutableStateOf<List<Long>?>(null)

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

    /**
     * Starts a session on the note's current paragraphs: their order becomes the session's. A session that the page
     * had before it was recreated goes on with its [log].
     */
    fun begin(paragraphs: List<ParagraphUi>, log: EditLog? = null) {
        started = true
        state = paragraphs
        order = paragraphs.map { it.id }
        undoSteps.clear()
        redoSteps.clear()
        if (log != null) {
            alias.putAll(log.alias)
            undoSteps.addAll(log.undo)
            redoSteps.addAll(log.redo)
        }
        failed = false
        failures = 0
    }

    /** The running session's log, for the page's saved state. */
    fun log(): EditLog? = if (started) EditLog(undoSteps.toList(), redoSteps.toList(), HashMap(alias)) else null

    /** Edit ended: the log goes; saved paragraphs stay laid over the state until it catches up ([sync]). */
    fun end() {
        started = false
        undoSteps.clear()
        redoSteps.clear()
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
        if (editing) return
        val shown = current.filter { id -> (overrides[id] ?: byId[id])?.text?.isNotBlank() == true }
        if (overrides.isEmpty() && shown == paragraphs.map { it.id }) order = null
    }

    /** The paragraphs to draw: the state with the session's saved paragraphs and order laid over it. */
    fun paragraphs(paragraphs: List<ParagraphUi>): List<ParagraphUi> {
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

    /** The paragraph [id] as last saved or loaded. */
    fun paragraph(id: Long): ParagraphUi? = paragraphs(state).firstOrNull { it.id == resolve(id) }

    /**
     * Autosave (600 ms after typing stops) and every paragraph commit: stores [draft] as [id]'s text, one undoable
     * step. True when it is stored (or nothing changed).
     */
    suspend fun save(id: Long, draft: String): Boolean = mutex.withLock { saveLocked(id, draft) }

    /**
     * Enter at [caret] of [draft]: the draft is saved, then the paragraph splits there. The caret goes to the start
     * of the new paragraph. Null when a write failed (the draft stays in the field).
     */
    suspend fun split(id: Long, draft: String, caret: Int): CaretAt? = mutex.withLock {
        if (!saveLocked(id, draft)) return@withLock null
        val stored = paragraphLocked(id) ?: return@withLock null
        val lead = draft.length - draft.trimStart().length
        val offset = (caret - lead).coerceIn(0, stored.text.length)
        val added = splitLocked(id, offset) ?: return@withLock null
        push(EditStep.Split(id, offset, added))
        CaretAt(added, 0)
    }

    /**
     * Backspace at the start of [id]: its saved [draft] joins the paragraph drawn before it, with one space. The
     * caret lands where the joined text begins.
     */
    suspend fun mergeIntoPrevious(id: Long, draft: String): JoinResult = mutex.withLock {
        val shown = paragraphs(state)
        val index = shown.indexOfFirst { it.id == id }
        val previous = shown.getOrNull(index - 1) ?: return@withLock JoinResult.Skipped
        if (!saveLocked(id, draft)) return@withLock JoinResult.Skipped
        val first = previous.text
        val second = paragraphLocked(id)?.text.orEmpty()
        when (mergeLocked(previous.id, id)) {
            Merge.Refused -> return@withLock JoinResult.Refused
            Merge.Failed -> return@withLock JoinResult.Skipped
            Merge.Done -> Unit
        }
        push(EditStep.Merge(previous.id, id, first.length))
        JoinResult.Joined(CaretAt(previous.id, joinPoint(first, second)))
    }

    /** Restore original (spec 4.14): the recognizer's text back, as one undoable step. */
    suspend fun restore(id: Long, draft: String): CaretAt? = mutex.withLock {
        if (!saveLocked(id, draft)) return@withLock null
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
    fun recordReplace(saved: List<ParagraphUi>, before: Map<Long, String>) {
        apply(saved)
        val changes = saved.mapNotNull { after -> before[after.id]?.takeIf { it != after.text }?.let { EditStep.Text(after.id, it, after.text) } }
        if (changes.isNotEmpty()) push(EditStep.Replace(changes))
    }

    /** Paragraphs saved outside the log (Replace all and its Undo while reading): drawn at once, before the reload. */
    fun applySaved(saved: List<ParagraphUi>) = apply(saved)

    /** "Replaced N · Undo" inside Edit: undoes the Replace all if it is still the last step. */
    suspend fun undoReplace(): Boolean {
        if (undoSteps.lastOrNull() !is EditStep.Replace) return false
        return undo() != null
    }

    /** Undoes the last step; the caret goes where it happened. Null when nothing was undone. */
    suspend fun undo(): CaretAt? = mutex.withLock {
        val step = undoSteps.removeLastOrNull() ?: return@withLock null
        val caret = run(step, forward = false)
        if (caret == null) undoSteps.add(step) else redoSteps.add(step)
        caret
    }

    /** Redoes the last undone step. */
    suspend fun redo(): CaretAt? = mutex.withLock {
        val step = redoSteps.removeLastOrNull() ?: return@withLock null
        val caret = run(step, forward = true)
        if (caret == null) redoSteps.add(step) else undoSteps.add(step)
        caret
    }

    /** Follows the ids that undoing a merge replaced. */
    fun resolve(id: Long): Long {
        var at = id
        while (true) at = alias[at] ?: return at
    }

    private suspend fun saveLocked(id: Long, draft: String): Boolean {
        val current = paragraphLocked(id) ?: return true
        val text = draft.trim()
        if (text == current.text) {
            succeed()
            return true
        }
        // A stale revision fails like storage does: the draft stays, and Retry writes it once the state reloaded.
        val outcome = backend.edit(current.id, revision(current.id), draft)
        if (outcome !is EditOutcome.Saved) {
            fail()
            return false
        }
        apply(outcome.paragraphs)
        val after = paragraphLocked(id)?.text ?: text
        if (after != current.text) push(EditStep.Text(current.id, current.text, after))
        succeed()
        return true
    }

    private suspend fun splitLocked(id: Long, offset: Int): Long? {
        val target = resolve(id)
        val outcome = backend.split(target, revision(target), offset)
        if (outcome !is EditOutcome.Saved) return fail()
        apply(outcome.paragraphs)
        val added = outcome.paragraphs.firstOrNull { it.id != target } ?: return fail()
        insertAfter(target, added.id)
        succeed()
        return added.id
    }

    private enum class Merge { Done, Refused, Failed }

    private suspend fun mergeLocked(firstId: Long, secondId: Long): Merge {
        val first = resolve(firstId)
        val second = resolve(secondId)
        val outcome = backend.merge(first, revision(first), second, revision(second))
        // A refused merge is not a failed write: both paragraphs stay and the caret does not move.
        if (outcome == EditOutcome.CannotMerge) return Merge.Refused
        if (outcome !is EditOutcome.Saved) {
            fail()
            return Merge.Failed
        }
        apply(outcome.paragraphs)
        overrides.remove(second)
        order = order?.filter { it != second }
        succeed()
        return Merge.Done
    }

    private suspend fun run(step: EditStep, forward: Boolean): CaretAt? = when (step) {
        is EditStep.Text -> {
            val text = if (forward) step.after else step.before
            val previous = if (forward) step.before else step.after
            if (write(step.id, text)) CaretAt(resolve(step.id), changeEnd(previous, text)) else null
        }
        is EditStep.Split -> if (forward) {
            splitLocked(step.id, step.offset)?.let { added ->
                if (added != resolve(step.newId)) alias[step.newId] = added
                CaretAt(added, 0)
            }
        } else {
            if (mergeLocked(step.id, step.newId) == Merge.Done) CaretAt(resolve(step.id), step.offset) else null
        }
        is EditStep.Merge -> if (forward) {
            val first = paragraphLocked(step.firstId)?.text.orEmpty()
            val second = paragraphLocked(step.secondId)?.text.orEmpty()
            if (mergeLocked(step.firstId, step.secondId) == Merge.Done) CaretAt(resolve(step.firstId), joinPoint(first, second)) else null
        } else {
            splitLocked(step.firstId, step.offset)?.let { added ->
                if (added != resolve(step.secondId)) alias[step.secondId] = added
                CaretAt(added, 0)
            }
        }
        is EditStep.Replace -> {
            val ok = step.changes.all { write(it.id, if (forward) it.after else it.before) }
            if (ok) CaretAt(resolve(step.changes.first().id), 0) else null
        }
    }

    /** Writes [text] as [id]'s text without logging it (undo and redo). */
    private suspend fun write(id: Long, text: String): Boolean {
        val target = resolve(id)
        if (paragraphLocked(target)?.text == text) return true
        val outcome = backend.edit(target, revision(target), text)
        if (outcome !is EditOutcome.Saved) {
            fail()
            return false
        }
        apply(outcome.paragraphs)
        succeed()
        return true
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

    private fun insertAfter(anchor: Long, id: Long) {
        val current = order ?: paragraphs(state).map { it.id }
        val at = current.indexOf(anchor)
        order = current.toMutableList().apply { if (id !in this) add(if (at < 0) size else at + 1, id) }
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

    companion object {
        /** Where the second text begins once two paragraphs are joined with one space. */
        fun joinPoint(first: String, second: String): Int =
            if (first.isBlank() || second.isBlank()) first.trim().length else first.trim().length + 1

        /** Where a change from [before] to [after] ends in [after]: the caret after an undo or redo. */
        fun changeEnd(before: String, after: String): Int {
            var prefix = 0
            val limit = minOf(before.length, after.length)
            while (prefix < limit && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
            return after.length - suffix
        }
    }
}
