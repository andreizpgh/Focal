package app.focal.ui.note

import app.focal.data.SplitOrigin
import app.focal.ui.state.BlankParagraph
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.WriteQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's edit rules (NoteStore + ParagraphEdits) in memory: trims; Enter puts the text before the caret in a new
 * paragraph before the split one, which keeps its id; Backspace joins with one space into the second, which keeps its
 * id; an undone split gets its paragraph back exactly; ids are never reused. While [gate] is set every write waits for
 * it, as a busy database holds the store's writes.
 */
private class FakeStore(vararg texts: String) : EditBackend {
    val order = texts.indices.map { it.toLong() + 1 }.toMutableList()
    val text = order.associateWith { texts[(it - 1).toInt()] }.toMutableMap()
    val revision = order.associateWith { 1L }.toMutableMap()
    val source = HashMap<Long, String>()
    var nextId = order.size.toLong() + 1
    var failNext = false
    /** Paragraphs whose original is unknown: the store refuses to join them with known ones. */
    val legacy = HashSet<Long>()
    /** Paragraphs the page knows to have a recognizer original. */
    val recognized = HashSet<Long>()
    var gate: CompletableDeferred<Unit>? = null

    /** What the note's state shows: visible paragraphs only. */
    fun visible(): List<ParagraphUi> = order.filter { text.getValue(it).isNotBlank() }.map { ui(it) }
    /** The stored paragraphs with no text, each after the one before it, as the note's state lists them. */
    fun blank(): List<BlankParagraph> = order.withIndex().filter { text.getValue(it.value).isBlank() }
        .map { (index, id) -> BlankParagraph(ui(id), order.getOrNull(index - 1)) }
    fun ui(id: Long) = paragraph(id, text.getValue(id), revision.getValue(id)).copy(originKnown = id !in legacy, hasOriginal = id in recognized)
    fun texts(): List<String> = order.map { text.getValue(it) }

    private fun write(id: Long, value: String) {
        text[id] = value
        revision[id] = revision.getValue(id) + 1
    }

    private suspend fun check(id: Long, rev: Long): EditOutcome? {
        gate?.await()
        return when {
            failNext -> EditOutcome.Failed("disk full").also { failNext = false }
            id !in text -> EditOutcome.Stale
            revision.getValue(id) != rev -> EditOutcome.Stale
            else -> null
        }
    }

    override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome {
        check(id, revision)?.let { return it }
        write(id, text.trim())
        return EditOutcome.Saved(listOf(ui(id)))
    }

    override suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome {
        check(id, revision)?.let { return it }
        val whole = text.getValue(id)
        val origin = SplitOrigin(whole, source[id], null, 0, 0, false)
        val added = nextId++
        text[added] = whole.substring(0, offset).trimEnd()
        this.revision[added] = 1
        order.add(order.indexOf(id), added)
        write(id, whole.substring(offset).trimStart())
        return EditOutcome.Saved(listOf(ui(added), ui(id)), origin)
    }

    override suspend fun unsplit(keepId: Long, keepRevision: Long, removeId: Long, removeRevision: Long, offset: Int, origin: SplitOrigin): EditOutcome {
        check(keepId, keepRevision)?.let { return it }
        check(removeId, removeRevision)?.let { return it }
        val parts = origin.text.substring(0, offset).trimEnd() to origin.text.substring(offset).trimStart()
        if (parts != (text.getValue(removeId) to text.getValue(keepId))) return EditOutcome.Stale
        order.remove(removeId)
        text.remove(removeId)
        write(keepId, origin.text)
        return EditOutcome.Saved(listOf(ui(keepId)))
    }

    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome {
        check(firstId, firstRevision)?.let { return it }
        check(secondId, secondRevision)?.let { return it }
        if ((firstId in legacy) != (secondId in legacy)) return EditOutcome.CannotMerge
        write(secondId, listOf(text.getValue(firstId).trim(), text.getValue(secondId).trim()).filter { it.isNotEmpty() }.joinToString(" "))
        order.remove(firstId)
        text.remove(firstId)
        return EditOutcome.Saved(listOf(ui(secondId)))
    }

    override suspend fun restore(id: Long, revision: Long): EditOutcome {
        check(id, revision)?.let { return it }
        write(id, source[id] ?: return EditOutcome.NoOriginal)
        return EditOutcome.Saved(listOf(ui(id)))
    }
}

/**
 * The note page as the session sees it: a field it reads when a write runs, changed the way the page changes it (Enter
 * leaves it on the text after the caret, Backspace puts the paragraph before in front, an undo reseeds it, a kept
 * field whose paragraph moved takes its text).
 */
private class Page private constructor(val store: FakeStore, val edit: EditSession, var field: FieldText?) {
    /** Steps dropped because their paragraphs had changed since ("Too late to undo"). */
    var tooLate = 0

    init {
        edit.field = { field }
        edit.joinRefused = { id, prefix ->
            field = field?.let { if (it.id == id && it.text.startsWith(prefix)) it.copy(text = it.text.removePrefix(prefix)) else it }
        }
        edit.fieldMoved = { from, to ->
            val current = field
            if (current != null && edit.resolve(current.id) == edit.resolve(from.id) && current.text == from.text) field = to?.let { FieldText(it.id, it.text) }
        }
        edit.tooLate = { tooLate++ }
    }

    constructor(store: FakeStore, log: EditLog? = null, field: FieldText? = null, restored: Boolean = log != null || field != null) :
        this(store, EditSession(store), field) {
        if (restored) edit.begin(store.visible(), log, store.blank(), field?.id) else edit.begin(store.visible())
    }

    /** What the page's saved state keeps: the session's log (changes included, through a Bundle's flat list) and the field. */
    fun saved(): Pair<EditLog, KeptField?> =
        EditLog.decode(ArrayList<Any?>(edit.log()!!.encode()))!! to field?.let { edit.keep(it.id, it.text, it.text.length, it.text.length) }

    /** The page goes while Edit runs (rotation, or another note replaces it beside the library): it parks its field. */
    fun go() {
        edit.park(field?.let { edit.keep(it.id, it.text, it.text.length, it.text.length) })
        field = null
    }

    companion object {
        /** The page after [previous] went, on the same session (the note's view model's): it takes the parked field. */
        fun after(previous: Page): Page = Page(previous.store, previous.edit, null).also { page ->
            page.edit.unpark()?.let { kept ->
                page.field = FieldText(page.edit.resolve(kept.id), kept.text)
                page.edit.adopt(kept)
            }
        }

        /** The page after process death: a new session from what the saved state held. */
        fun restored(store: FakeStore, saved: Pair<EditLog, KeptField?>): Page {
            val (log, kept) = saved
            return Page(store, EditSession(store), kept?.let { FieldText(it.id, it.text) }).also { page ->
                page.edit.begin(store.visible(), log, store.blank(), kept?.id)
                kept?.let(page.edit::adopt)
            }
        }
    }

    fun type(id: Long, text: String) {
        field = FieldText(id, text)
    }

    /** The caret moves to paragraph [id], as a tap puts it there: the field it leaves keeps its draft. */
    fun tap(id: Long) {
        field?.let { edit.leave(it.id, it.text) }
        field = FieldText(edit.resolve(id), edit.paragraph(id)!!.text)
    }

    /** Enter at [at] of the field: it keeps the text after the caret (ParagraphInput). */
    fun enter(at: Int) {
        val current = field!!
        edit.split(current.id, current.text, at)
        field = current.copy(text = EditSession.splitTexts(current.text, at).second)
    }

    /** Backspace at the start of the field. */
    fun backspace(): JoinResult {
        val current = field!!
        return edit.join(current.id, current.text).also { if (it is JoinResult.Joined) field = current.copy(text = it.prefix + current.text) }
    }

    fun reseed(at: CaretAt?) {
        at?.let { field = FieldText(it.id, edit.paragraph(it.id)!!.text) }
    }

    suspend fun undo(): CaretAt? = edit.undo().also(::reseed)
    suspend fun redo(): CaretAt? = edit.redo().also(::reseed)

    /** The session after the note's state reloaded, as the page draws it. */
    fun shown(editing: Boolean = true): List<String> {
        edit.sync(store.visible(), editing)
        return edit.paragraphs(store.visible()).map { it.text }
    }
}

class EditSessionTest {
    @Test fun anAutosaveIsOneStepThatUndoesAndRedoes() = runBlocking {
        val store = FakeStore("One two.", "Three.")
        val page = Page(store)
        page.type(1, "One two three. ")
        assertTrue(page.edit.save())
        assertEquals(listOf("One two three.", "Three."), page.shown())
        assertTrue(page.edit.canUndo)
        val back = page.undo()
        assertEquals(CaretAt(1, "One two".length), back)
        assertEquals("One two.", store.text[1])
        assertTrue(page.edit.canRedo)
        page.redo()
        assertEquals("One two three.", store.text[1])
        assertFalse(page.edit.canRedo)
    }

    @Test fun savingTheSameTextLogsNothing() = runBlocking {
        val page = Page(FakeStore("Same."))
        page.type(1, " Same. ")
        assertTrue(page.edit.save())
        assertFalse(page.edit.canUndo)
    }

    @Test fun enterShowsTheSplitAtOnceAndTheFieldKeepsItsParagraph() = runBlocking {
        val store = FakeStore("First half second half.")
        val page = Page(store)
        // The draft has a leading space the store trims: the caret maps onto the stored text.
        page.type(1, "  First half second half.")
        page.enter("  First half ".length)
        // Before the store has it: the text before the caret above, the field on the rest.
        assertEquals(listOf("First half", "second half."), page.shown())
        assertTrue(page.edit.save())
        assertEquals(listOf("First half", "second half."), store.texts())
        // The field's paragraph kept its id, with the text after the caret; the new one is before it.
        assertEquals(listOf(2L, 1L), store.order)
        assertEquals(listOf("First half", "second half."), page.shown())
    }

    /** S4: Undo of Enter inside a word gives the word back, and every space, not the two parts joined with one. */
    @Test fun undoingEnterGivesTheParagraphBackExactly() = runBlocking {
        val store = FakeStore("Our country road.", "Two  spaces.")
        val page = Page(store)
        page.type(1, "Our country road.")
        page.enter("Our cou".length)
        assertTrue(page.edit.save())
        assertEquals(listOf("Our cou", "ntry road.", "Two  spaces."), store.texts())
        assertEquals(CaretAt(1, "Our cou".length), page.undo())
        assertEquals(listOf("Our country road.", "Two  spaces."), store.texts())
        page.redo()
        assertEquals(listOf("Our cou", "ntry road.", "Two  spaces."), store.texts())
        page.undo()
        // Enter between two spaces: each part loses them, the undone paragraph has both.
        page.tap(2)
        page.enter("Two ".length)
        assertTrue(page.edit.save())
        assertEquals(listOf("Our country road.", "Two", "spaces."), store.texts())
        page.undo()
        assertEquals(listOf("Our country road.", "Two  spaces."), store.texts())
        assertEquals(listOf("Our country road.", "Two  spaces."), page.shown())
        assertFalse(page.edit.failed)
    }

    /** S1: typed while Enter's split is still writing, the words stay in the second paragraph, where the caret went. */
    @Test fun typingWhileASplitIsWritingStaysInTheSecondParagraph() = runBlocking {
        val store = FakeStore("First half second half.", "Next.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "First half second half.")
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        page.enter("First half ".length)
        writes.launch { page.edit.save() }
        yield()
        // The store holds the split; the page already shows it, and typing goes on in the field.
        assertEquals(listOf("First half", "second half.", "Next."), page.shown())
        page.type(1, "New second half.")
        writes.launch { page.edit.save() }
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals(listOf("First half", "New second half.", "Next."), store.texts())
        assertEquals(listOf("First half", "New second half.", "Next."), page.shown())
        // One step each, the split and then the typing: the store never held the second part as the whole paragraph.
        page.undo()
        assertEquals(listOf("First half", "second half.", "Next."), store.texts())
        page.undo()
        assertEquals(listOf("First half second half.", "Next."), store.texts())
        assertFalse(page.edit.canUndo)
    }

    /** S1: a split the store did not take stays on the page with what was typed after it; the next write makes it. */
    @Test fun aSplitThatFailsStaysShownUntilTheNextWriteMakesIt() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        store.failNext = true
        assertFalse(page.edit.save())
        assertTrue(page.edit.failed)
        page.type(1, "beta gamma.")
        assertEquals(listOf("Alpha", "beta."), page.shown())
        assertEquals(listOf("Alpha beta."), store.texts())
        // Retry: the split first, then the field.
        assertTrue(page.edit.save())
        assertFalse(page.edit.failed)
        assertEquals(listOf("Alpha", "beta gamma."), store.texts())
    }

    @Test fun enterAtTheEndShowsAnEmptyParagraphTheStateDoesNotList() = runBlocking {
        val store = FakeStore("Last line.")
        val page = Page(store)
        page.type(1, "Last line.")
        page.enter("Last line.".length)
        assertTrue(page.edit.save())
        // The state lists visible paragraphs only; the session still draws the field's empty one, in its place.
        assertEquals(listOf(2L), store.visible().map { it.id })
        assertEquals(listOf("Last line.", ""), page.shown())
        // Backspace in it joins the paragraph before into it, which the state's own order could not find.
        assertEquals(JoinResult.Joined("Last line.", "Last line.".length), page.backspace())
        assertEquals(listOf("Last line."), page.shown())
        assertTrue(page.edit.save())
        assertEquals(listOf("Last line."), store.texts())
        assertEquals(listOf("Last line."), page.shown())
    }

    /** Backspace joins at once with one space, and what is typed next stays at the join while the store catches up. */
    @Test fun backspaceJoinsAtOnceAndTypingGoesOnInTheJoinedParagraph() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(2, "Beta.")
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        assertEquals(JoinResult.Joined("Alpha. ", "Alpha. ".length), page.backspace())
        writes.launch { page.edit.save() }
        yield()
        assertEquals(listOf("Alpha. Beta."), page.shown())
        page.type(2, "Alpha. And Beta.")
        writes.launch { page.edit.save() }
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals(listOf("Alpha. And Beta."), store.texts())
        // The joined paragraph is the one the field was in.
        assertEquals(listOf(2L), store.order)
        page.undo()
        page.undo()
        assertEquals(listOf("Alpha.", "Beta."), page.shown())
        assertEquals(listOf("Alpha.", "Beta."), store.texts())
    }

    @Test fun aJoinThePageCanTellIsKeptApartChangesNothing() = runBlocking {
        val store = FakeStore("Legacy text.", "Recognized text.")
        store.legacy += 1L
        store.recognized += 2L
        val page = Page(store)
        page.type(2, "Recognized text.")
        assertEquals(JoinResult.Refused, page.backspace())
        assertEquals(listOf("Legacy text.", "Recognized text."), page.shown())
        assertTrue(page.edit.save())
        assertFalse(page.edit.canUndo)
    }

    @Test fun aJoinTheStoreRefusesAfterwardsComesBackOffTheFieldWithoutFailing() = runBlocking {
        val store = FakeStore("Legacy text.", "Recognized text.")
        store.legacy += 1L
        val page = Page(store)
        page.type(2, "Recognized text, edited.")
        // The page could not tell: it joins them, and the store refuses.
        assertTrue(page.backspace() is JoinResult.Joined)
        assertTrue(page.edit.save())
        assertEquals(FieldText(2, "Recognized text, edited."), page.field)
        assertEquals(listOf("Legacy text.", "Recognized text, edited."), page.shown())
        assertFalse(page.edit.failed)
        assertEquals(0, page.edit.failures)
        // Only the draft's save is logged; undo takes back the typing, not a join that never happened.
        page.undo()
        assertEquals(listOf("Legacy text.", "Recognized text."), page.shown())
        assertFalse(page.edit.canUndo)
    }

    @Test fun backspaceInTheFirstParagraphJoinsNothing() = runBlocking {
        val store = FakeStore("Only.", "Second.")
        val page = Page(store)
        page.type(1, "Only.")
        assertEquals(JoinResult.Skipped, page.backspace())
        assertTrue(page.edit.save())
        assertEquals(listOf("Only.", "Second."), page.shown())
        assertFalse(page.edit.canUndo)
        assertFalse(page.edit.failed)
    }

    @Test fun anEditBeforeAJoinStillUndoesAfterTheJoinWasUndone() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        page.type(1, "Alpha, typed.")
        assertTrue(page.edit.save())
        page.tap(2)
        page.backspace()
        assertTrue(page.edit.save())
        page.undo() // the join: Alpha, typed. returns under a new id
        page.undo() // the typing, on the paragraph's new id
        assertEquals(listOf("Alpha.", "Beta."), page.shown())
        assertNotEquals(1L, page.edit.resolve(1))
    }

    /**
     * S5: every time an undo or redo brings a paragraph back under a new id, every older id follows it. Here the text
     * before a split comes back three times (ids 2, 3, 4); the typing logged on 3 is redone on 4.
     */
    @Test fun aSplitUndoneAndRedoneAgainStillFindsTheTextTypedInEitherPart() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        assertTrue(page.edit.save())
        page.undo()
        page.redo()
        // Typing in both parts: the one before the split is stored under its second id now.
        page.tap(2)
        page.type(page.field!!.id, "Alpha, typed.")
        page.tap(1)
        page.type(1, "beta, typed.")
        assertTrue(page.edit.save())
        assertEquals(listOf("Alpha, typed.", "beta, typed."), store.texts())
        repeat(2) {
            repeat(3) { assertTrue(page.undo() != null) }
            assertEquals(listOf("Alpha beta."), store.texts())
            repeat(3) { assertTrue(page.redo() != null) }
            assertEquals(listOf("Alpha, typed.", "beta, typed."), store.texts())
        }
        assertFalse(page.edit.failed)
    }

    @Test fun restoreOriginalIsOneUndoableStep() = runBlocking {
        val store = FakeStore("Edited words.")
        store.source[1] = "Original words."
        val page = Page(store)
        page.type(1, "Edited words.")
        assertEquals(CaretAt(1, "Original words.".length), page.edit.restore().also(page::reseed))
        assertEquals("Original words.", store.text[1])
        page.undo()
        assertEquals("Edited words.", store.text[1])
    }

    @Test fun aFailedWriteSaysNotSavedAndKeepsCounting() = runBlocking {
        val store = FakeStore("Text.")
        val page = Page(store)
        page.type(1, "Text and more.")
        store.failNext = true
        assertFalse(page.edit.save())
        assertTrue(page.edit.failed)
        assertEquals(1, page.edit.failures)
        store.failNext = true
        assertFalse(page.edit.save())
        assertEquals(2, page.edit.failures)
        assertTrue(page.edit.save())
        assertFalse(page.edit.failed)
        assertEquals(0, page.edit.failures)
    }

    @Test fun aStaleRevisionKeepsTheDraftUntilTheStateReloads() = runBlocking {
        val store = FakeStore("Text.")
        val page = Page(store)
        store.revision[1] = 5 // something else wrote; the session's copy is behind
        page.type(1, "Text, changed.")
        assertFalse(page.edit.save())
        assertTrue(page.edit.failed)
        page.edit.sync(store.visible(), editing = true)
        assertTrue(page.edit.save())
        assertEquals("Text, changed.", store.text[1])
    }

    /** S2: a field whose paragraph the session does not know keeps its draft and says Not saved; it is never dropped. */
    @Test fun aFieldOnAParagraphTheSessionDoesNotKnowIsNotSaved() = runBlocking {
        val store = FakeStore("Text.")
        val page = Page(store, field = FieldText(99, "Words with nowhere to go."))
        assertFalse(page.edit.save())
        assertTrue(page.edit.failed)
        assertEquals(listOf("Text."), store.texts())
    }

    /**
     * S2: Enter at the end makes an empty paragraph that the state leaves out. A session recreated with the field
     * typing in it (process death before the autosave) gets it back in place, and Done stores the draft there.
     */
    @Test fun aRecreatedSessionGetsBackTheEmptyParagraphItsFieldTypesIn() = runBlocking {
        val store = FakeStore("First.", "Last line.")
        val page = Page(store)
        page.type(2, "Last line.")
        page.enter("Last line.".length)
        assertTrue(page.edit.save())
        assertEquals(listOf("First.", "Last line.", ""), store.texts())
        val saved = page.edit.log()!!.encode()
        val again = Page(store, EditLog.decode(ArrayList<Any?>(saved)), FieldText(2, "Added words."))
        assertEquals(listOf("First.", "Last line.", ""), again.shown())
        assertTrue(again.edit.save())
        assertEquals(listOf("First.", "Last line.", "Added words."), store.texts())
        // Its log came back too: Undo takes the typing, then the split.
        again.undo()
        again.undo()
        assertEquals(listOf("First.", "Last line."), store.texts())
        // Only its own empty paragraphs: one an older session left (never shown since) stays out.
        val older = FakeStore("One.", "", "Two.")
        assertEquals(listOf("One.", "Two."), Page(older, field = FieldText(1, "One.")).shown())
    }

    /** S3: the save as the page stops waits for an undo still writing and reads the field the undo reseeded. */
    @Test fun theSaveAsThePageStopsNeverWritesBackOverAnUndoStillWriting() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "Alpha, typed.")
        assertTrue(page.edit.save())
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        writes.launch { page.undo() }
        yield()
        // The activity stops while the undo's write is held.
        writes.launch { page.edit.save() }
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals("Alpha.", store.text[1])
        assertTrue(page.edit.canRedo)
        assertEquals(FieldText(1, "Alpha."), page.field)
    }

    @Test fun replaceAllInsideEditIsOneStep() = runBlocking {
        val store = FakeStore("A screen.", "Another screen.")
        val page = Page(store)
        val before = page.edit.storedTexts()
        store.text[1] = "A page."
        store.text[2] = "Another page."
        store.revision[1] = 2
        store.revision[2] = 2
        page.edit.recordReplace(listOf(store.ui(1), store.ui(2)), before)
        assertTrue(page.edit.undoReplace())
        assertEquals(listOf("A screen.", "Another screen."), page.shown())
        assertFalse(page.edit.undoReplace())
    }

    @Test fun savedParagraphsCoverTheStateUntilItReloadsThenTheOrderIsDropped() = runBlocking {
        val store = FakeStore("One.", "Two.")
        val page = Page(store)
        val stale = store.visible()
        page.type(1, "One, changed.")
        page.edit.save()
        // Before the reload the old state is drawn with the saved paragraph over it.
        assertEquals(listOf("One, changed.", "Two."), page.edit.paragraphs(stale).map { it.text })
        page.edit.end()
        page.edit.sync(store.visible(), editing = false)
        assertEquals(store.visible(), page.edit.paragraphs(store.visible()))
    }

    @Test fun aRecreatedPageGoesOnWithItsSessionsLog() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        page.type(1, "Alpha, typed.")
        page.edit.save()
        page.tap(2)
        page.backspace()
        page.edit.save()
        page.undo() // the join: Alpha, typed. returns under a new id, and the join waits in redo
        val saved = page.edit.log()!!.encode()
        // The page is recreated: a new session over the reloaded note, with the log the saved state kept.
        val again = Page(store, EditLog.decode(ArrayList<Any?>(saved)), page.field)
        assertTrue(again.edit.canUndo)
        assertTrue(again.edit.canRedo)
        again.redo()
        assertEquals(listOf("Alpha, typed. Beta."), again.shown())
        again.undo()
        again.undo()
        assertEquals(listOf("Alpha.", "Beta."), again.shown())
        assertFalse(again.edit.canUndo)
    }

    @Test fun aDraftStillBeingSavedIsTheSavedLogsNewestStep() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        page.type(1, "Alpha, typed.")
        page.edit.save()
        page.type(1, "Alpha, typed twice.")
        page.edit.save()
        page.undo()
        assertTrue(page.edit.canRedo)
        // The draft's last save as the page stops: its write has not returned when the state is saved.
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        page.type(1, " Alpha, typed later. ")
        val last = launch(start = CoroutineStart.UNDISPATCHED) { page.edit.save() }
        val saved = page.edit.log()!!
        val steps = listOf(EditStep.Text(1, "Alpha.", "Alpha, typed."), EditStep.Text(1, "Alpha, typed.", "Alpha, typed later."))
        assertEquals(steps, saved.undo)
        // A new step clears redo, as the save does.
        assertEquals(emptyList<EditStep>(), saved.redo)
        store.gate = null
        gate.complete(Unit)
        last.join()
        // Once the write has returned the save has logged it itself, and only once.
        assertEquals(saved, page.edit.log())
        // The recreated page finds the draft stored and logs nothing for it; Undo takes it back first, Redo returns it.
        val again = Page(store, EditLog.decode(ArrayList<Any?>(saved.encode())), FieldText(1, "Alpha, typed later."))
        assertTrue(again.edit.save())
        again.undo()
        assertEquals(listOf("Alpha, typed.", "Beta."), again.shown())
        again.undo()
        assertEquals(listOf("Alpha.", "Beta."), again.shown())
        again.redo()
        again.redo()
        assertEquals(listOf("Alpha, typed later.", "Beta."), again.shown())
        assertFalse(again.edit.canRedo)
    }

    @Test fun theSavedLogKeepsEveryKindOfStepAndOnlyTheNewestThatFit() {
        val origin = SplitOrigin("Our  country.", null, "[{\"w\":1}]", 10, 900, true)
        val log = EditLog(
            undo = listOf(
                EditStep.Text(1, "a".repeat(10), "b".repeat(10)),
                EditStep.Split(1, 4, 9, origin),
                EditStep.Split(2, 0, 10, SplitOrigin("", "", null, 0, 0, false)),
                EditStep.Merge(1, 9, 4),
                EditStep.Replace(listOf(EditStep.Text(1, "one", "two"), EditStep.Text(2, "три", "четыре"))),
            ),
            redo = listOf(EditStep.Text(2, "x", "y")),
            alias = mapOf(5L to 7L, -1L to 12L),
        )
        assertEquals(log, EditLog.decode(log.encode()))
        val pending = log.copy(
            undo = log.undo + EditStep.Merge(3, 4, 2, "ab cd"),
            changes = listOf(EditChange.Text(1, " typed "), EditChange.Split(2, "Two parts.", 4, -2), EditChange.Join(-2, 2, "parts.", "Two ")),
            revisions = mapOf(1L to 3L, 2L to 5L),
        )
        assertEquals(pending, EditLog.decode(pending.encode()))
        // The changes are kept whole whatever the budget; the steps give way to them.
        assertEquals(pending.changes, EditLog.decode(pending.encode(budget = 0))!!.changes)
        assertEquals(emptyList<EditStep>(), EditLog.decode(pending.encode(budget = 0))!!.undo)
        // Within 36 characters only the newest steps fit: the typing and the split before them are dropped.
        val small = EditLog.decode(log.encode(budget = 36))!!
        assertEquals(log.undo.drop(2), small.undo)
        assertEquals(log.redo, small.redo)
        assertEquals(log.alias, small.alias)
        assertNull(EditLog.decode(listOf("not a log")))
        assertNull(EditLog.decode(null))
        assertNull(EditSession(FakeStore("Idle.")).log())
    }

    @Test fun theCaretAfterUndoSitsAtTheEndOfTheChange() {
        assertEquals("Hello brave ".length, EditSession.changeEnd("Hello world", "Hello brave world"))
        assertEquals("Hello ".length, EditSession.changeEnd("Hello brave world", "Hello world"))
        assertEquals("ab ".length, EditSession.joinPoint("ab", "cd"))
        assertEquals("ab".length, EditSession.joinPoint("ab", ""))
        assertEquals("cou" to "ntry", EditSession.splitTexts("cou ntry", 3))
        assertEquals("cou" to "ntry ", EditSession.splitTexts(" cou  ntry ", 5))
    }

    /** P1: a split the store did not take survives process death in the saved state; the next write makes it. */
    @Test fun aSplitThatFailedSurvivesProcessDeath() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        store.failNext = true
        assertFalse(page.edit.save())
        page.type(1, "beta gamma.")
        val again = Page.restored(store, page.saved())
        assertEquals(listOf("Alpha", "beta."), again.shown())
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha", "beta gamma."), store.texts())
        // The session that stored them logged them: the typing, then the split.
        again.undo()
        again.undo()
        assertEquals(listOf("Alpha beta."), store.texts())
    }

    /** P1 on rotation: the view model's session goes on with the split it still shows, and the field the page parked. */
    @Test fun aSplitThatFailedSurvivesRotation() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        store.failNext = true
        assertFalse(page.edit.save())
        page.type(1, "beta gamma.")
        page.go()
        val again = Page.after(page)
        assertEquals(FieldText(1, "beta gamma."), again.field)
        assertEquals(listOf("Alpha", "beta."), again.shown())
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha", "beta gamma."), store.texts())
    }

    /** P2: a join the store did not take survives process death, and is stored once. */
    @Test fun aJoinThatFailedSurvivesProcessDeath() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        page.type(2, "Beta.")
        assertTrue(page.backspace() is JoinResult.Joined)
        store.failNext = true
        assertFalse(page.edit.save())
        val again = Page.restored(store, page.saved())
        assertEquals(listOf("Alpha. Beta."), again.shown())
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha. Beta."), store.texts())
    }

    /** P3: the draft a tap left that the store did not take survives process death. */
    @Test fun aLeftDraftThatFailedSurvivesProcessDeath() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        page.type(1, "Alpha, typed.")
        page.tap(2)
        store.failNext = true
        assertFalse(page.edit.save())
        val again = Page.restored(store, page.saved())
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha, typed.", "Beta."), store.texts())
    }

    /** P4: a field typing in the text before a split the store has not made yet keeps its stand-in after process death. */
    @Test fun aFieldOnARowTheStoreHasNotMadeYetSurvivesProcessDeath() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        store.failNext = true
        assertFalse(page.edit.save())
        page.tap(-1)
        page.type(-1, "Alpha, typed.")
        val again = Page.restored(store, page.saved())
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha, typed.", "beta."), store.texts())
        // Later writes go through: nothing is left that can never be stored.
        again.tap(1)
        again.type(1, "beta, typed.")
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha, typed.", "beta, typed."), store.texts())
        // A new split's stand-in is not the restored one's.
        again.enter("beta,".length)
        assertEquals(listOf("Alpha, typed.", "beta,", "typed."), again.shown())
    }

    /**
     * The state is saved while the split is still writing; the old process stores it and the typing after it before it
     * dies. The restored session finds the paragraph moved past what the saved state knew and stores nothing twice.
     */
    @Test fun changesStoredAfterTheStateWasSavedAreNotStoredAgain() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "Alpha beta.")
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        page.enter("Alpha ".length)
        writes.launch { page.edit.save() }
        yield()
        page.type(1, "beta, typed.")
        val saved = page.saved()
        writes.launch { page.edit.save() }
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals(listOf("Alpha", "beta, typed."), store.texts())
        val again = Page.restored(store, saved)
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha", "beta, typed."), store.texts())
        assertEquals(FieldText(1, "beta, typed."), again.field)
    }

    /** P6: the state is saved while an undo of Enter writes (Home on a busy database), then the process dies. */
    @Test fun aFieldKeptWhileAnUndoWroteTakesTheUndonePartBackAfterProcessDeath() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        assertTrue(page.edit.save())
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        writes.launch { page.undo() }
        yield()
        writes.launch { page.edit.save() }
        val saved = page.saved()
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals(listOf("Alpha beta."), store.texts())
        val again = Page.restored(store, saved)
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha beta."), store.texts())
        assertEquals(FieldText(1, "Alpha beta."), again.field)
        // The undo the saved log still lists has happened: it is dropped, not run again.
        assertNull(again.undo())
        assertEquals(1, again.tooLate)
        assertEquals(listOf("Alpha beta."), store.texts())
    }

    /** P6 on rotation: the parked field takes the undone text back, and the page after it gets that. */
    @Test fun aFieldParkedWhileAnUndoWroteTakesTheUndonePartBack() = runBlocking {
        val store = FakeStore("Alpha beta.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        assertTrue(page.edit.save())
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        writes.launch { page.undo() }
        yield()
        // The activity stops and is recreated: the stop's save, the page's last save, the new page's check.
        writes.launch { page.edit.save() }
        page.go()
        writes.launch { page.edit.save() }
        val again = Page.after(page)
        writes.launch { again.edit.check() }
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals(listOf("Alpha beta."), store.texts())
        assertEquals(FieldText(1, "Alpha beta."), again.field)
        assertTrue(again.edit.canRedo)
        assertEquals(0, again.tooLate)
    }

    /** P7: the same while a redo of Enter writes: nothing is duplicated, and the redo is not run twice. */
    @Test fun aFieldKeptWhileARedoWroteTakesTheSplitAfterProcessDeath() = runBlocking {
        val store = FakeStore("Alpha beta gamma.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "Alpha beta gamma.")
        page.enter("Alpha ".length)
        assertTrue(page.edit.save())
        page.undo()
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        writes.launch { page.redo() }
        yield()
        writes.launch { page.edit.save() }
        val saved = page.saved()
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        val again = Page.restored(store, saved)
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha", "beta gamma."), store.texts())
        assertNull(again.redo())
        assertEquals(1, again.tooLate)
        assertEquals(listOf("Alpha", "beta gamma."), store.texts())
    }

    /** P8: a restored redo of a split that already happened does not split the paragraph again. */
    @Test fun aRestoredRedoOfASplitThatAlreadyHappenedIsDropped() = runBlocking {
        val store = FakeStore("Alpha beta gamma.", "Other.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(1, "Alpha beta gamma.")
        page.enter("Alpha ".length)
        assertTrue(page.edit.save())
        page.undo()
        page.tap(2)
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        writes.launch { page.redo() }
        yield()
        val saved = page.saved()
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        val again = Page.restored(store, saved)
        assertNull(again.redo())
        assertEquals(listOf("Alpha", "beta gamma.", "Other."), store.texts())
        assertEquals(1, again.tooLate)
    }

    /** A restored undo of a join that already happened does not split the second paragraph where the first ended. */
    @Test fun aRestoredUndoOfAJoinThatAlreadyHappenedIsDropped() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val page = Page(store)
        val writes = WriteQueue(this)
        page.type(2, "Beta.")
        page.backspace()
        assertTrue(page.edit.save())
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        writes.launch { page.undo() }
        yield()
        val saved = page.saved()
        store.gate = null
        gate.complete(Unit)
        writes.settle()
        assertEquals(listOf("Alpha.", "Beta."), store.texts())
        val again = Page.restored(store, saved)
        assertNull(again.undo())
        assertEquals(1, again.tooLate)
        assertEquals(listOf("Alpha.", "Beta."), store.texts())
        assertEquals(FieldText(2, "Beta."), again.field)
    }

    /**
     * Two panes: another note replaces this one while its last save cannot be stored. The session keeps the field and
     * the change, and the note opened again goes on with them.
     */
    @Test fun aNoteReplacedWhileItsLastSaveFailsKeepsItsFieldForWhenItOpensAgain() = runBlocking {
        val store = FakeStore("Alpha beta.", "Other.")
        val page = Page(store)
        page.type(1, "Alpha beta.")
        page.enter("Alpha ".length)
        page.type(1, "beta, typed.")
        page.go()
        store.failNext = true
        assertFalse(page.edit.save())
        assertTrue(page.edit.started)
        val again = Page.after(page)
        assertEquals(FieldText(1, "beta, typed."), again.field)
        assertTrue(again.edit.save())
        assertEquals(listOf("Alpha", "beta, typed.", "Other."), store.texts())
    }

    /** A parked field is stored by the session while no page holds it, and checked against its paragraph first. */
    @Test fun aParkedFieldIsStoredWhileNoPageHoldsIt() = runBlocking {
        val store = FakeStore("Alpha.")
        val page = Page(store)
        page.type(1, "Alpha, typed.")
        page.go()
        assertTrue(page.edit.save())
        assertEquals("Alpha, typed.", store.text[1])
        val again = Page.after(page)
        assertEquals(FieldText(1, "Alpha, typed."), again.field)
        assertTrue(again.edit.save())
        assertFalse(again.edit.canRedo)
        again.undo()
        assertEquals("Alpha.", store.text[1])
    }

    /** A3: in Edit a paragraph offers Edit, which puts the caret in it without a pointer, and nothing that plays. */
    @Test fun inEditAParagraphOffersEditAndNothingThatPlays() {
        val note = NoteUiState("note", loaded = true, editable = true, audioPresent = true, canPlay = true)
        val labels = ParagraphLabels("Play from here", "Edit", "Copy paragraph", "Restore original", "Play unclear word")
        val edited = paragraph(4, "Some edited words.").copy(edited = true, canRestore = true)
        var caret: CaretAt? = null
        fun actions(editing: Boolean, finding: Boolean = false) = paragraphActions(
            edited, labels, note, canPlay = true, editing = editing, finding = finding, play = { _, _ -> },
            edit = { caret = it }, copy = {}, restore = {},
        )
        assertEquals(listOf("Play from here", "Edit", "Copy paragraph", "Restore original"), actions(editing = false).map { it.label })
        val inEdit = actions(editing = true)
        assertEquals(listOf("Edit", "Copy paragraph"), inEdit.map { it.label })
        assertTrue(inEdit.first { it.label == "Edit" }.action())
        assertEquals(CaretAt(4, 0), caret)
        // Find open over Edit has no field to put the caret in.
        assertEquals(listOf("Copy paragraph"), actions(editing = true, finding = true).map { it.label })
    }
}
