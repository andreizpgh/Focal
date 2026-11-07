package app.focal.ui.note

import app.focal.data.SplitOrigin
import app.focal.ui.state.BlankParagraph
import app.focal.ui.state.EditOutcome
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
 * leaves it on the text after the caret, Backspace puts the paragraph before in front, an undo reseeds it).
 */
private class Page(val store: FakeStore, log: EditLog? = null, var field: FieldText? = null, restored: Boolean = log != null || field != null) {
    val edit = EditSession(store).also { session ->
        session.field = { field }
        session.joinRefused = { id, prefix ->
            field = field?.let { if (it.id == id && it.text.startsWith(prefix)) it.copy(text = it.text.removePrefix(prefix)) else it }
        }
        if (restored) session.begin(store.visible(), log, store.blank(), field?.id) else session.begin(store.visible())
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
}
