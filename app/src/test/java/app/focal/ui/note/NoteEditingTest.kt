package app.focal.ui.note

import app.focal.data.TextMatch
import app.focal.ui.library.DatePatterns
import app.focal.ui.library.DayWords
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.SpokenDateline
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.ParagraphUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

internal fun paragraph(id: Long, text: String, revision: Long = 1) = ParagraphUi(
    id = id, revision = revision, text = text, startMs = id * 1000, endMs = id * 1000 + 999, words = emptyList(),
    exactTimes = false, edited = false, canRestore = false, open = false,
)

/** The store's edit rules (NoteStore + ParagraphEdits) in memory: trims, splits, joins with one space, new ids. */
private class FakeStore(vararg texts: String) : EditBackend {
    val order = texts.indices.map { it.toLong() + 1 }.toMutableList()
    val text = order.associateWith { texts[(it - 1).toInt()] }.toMutableMap()
    val revision = order.associateWith { 1L }.toMutableMap()
    val source = HashMap<Long, String>()
    var nextId = order.size.toLong() + 1
    var failNext = false
    /** Paragraphs whose original is unknown: the store refuses to join them with known ones. */
    val legacy = HashSet<Long>()

    /** What the note's state shows: visible paragraphs only. */
    fun visible(): List<ParagraphUi> = order.filter { text.getValue(it).isNotBlank() }.map { ui(it) }
    fun ui(id: Long) = paragraph(id, text.getValue(id), revision.getValue(id))

    private fun write(id: Long, value: String) {
        text[id] = value
        revision[id] = revision.getValue(id) + 1
    }

    private fun check(id: Long, rev: Long): EditOutcome? = when {
        failNext -> EditOutcome.Failed("disk full").also { failNext = false }
        id !in text -> EditOutcome.Stale
        revision.getValue(id) != rev -> EditOutcome.Stale
        else -> null
    }

    override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome {
        check(id, revision)?.let { return it }
        write(id, text.trim())
        return EditOutcome.Saved(listOf(ui(id)))
    }

    override suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome {
        check(id, revision)?.let { return it }
        val whole = text.getValue(id)
        write(id, whole.substring(0, offset).trimEnd())
        val added = nextId++
        text[added] = whole.substring(offset).trimStart()
        this.revision[added] = 1
        order.add(order.indexOf(id) + 1, added)
        return EditOutcome.Saved(listOf(ui(id), ui(added)))
    }

    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome {
        check(firstId, firstRevision)?.let { return it }
        check(secondId, secondRevision)?.let { return it }
        if ((firstId in legacy) != (secondId in legacy)) return EditOutcome.CannotMerge
        write(firstId, listOf(text.getValue(firstId).trim(), text.getValue(secondId).trim()).filter { it.isNotEmpty() }.joinToString(" "))
        order.remove(secondId)
        text.remove(secondId)
        return EditOutcome.Saved(listOf(ui(firstId)))
    }

    override suspend fun restore(id: Long, revision: Long): EditOutcome {
        check(id, revision)?.let { return it }
        write(id, source[id] ?: return EditOutcome.NoOriginal)
        return EditOutcome.Saved(listOf(ui(id)))
    }
}

class EditSessionTest {
    private fun session(store: FakeStore) = EditSession(store).also { it.begin(store.visible()) }

    /** The session after the note's state reloaded, as the page sees it. */
    private fun EditSession.shown(store: FakeStore, editing: Boolean = true): List<String> {
        sync(store.visible(), editing)
        return paragraphs(store.visible()).map { it.text }
    }

    @Test fun anAutosaveIsOneStepThatUndoesAndRedoes() = runBlocking {
        val store = FakeStore("One two.", "Three.")
        val edit = session(store)
        assertTrue(edit.save(1, "One two three. "))
        assertEquals(listOf("One two three.", "Three."), edit.shown(store))
        assertTrue(edit.canUndo)
        val back = edit.undo()
        assertEquals(CaretAt(1, "One two".length), back)
        assertEquals("One two.", store.text[1])
        assertTrue(edit.canRedo)
        edit.redo()
        assertEquals("One two three.", store.text[1])
        assertFalse(edit.canRedo)
    }

    @Test fun savingTheSameTextLogsNothing() = runBlocking {
        val store = FakeStore("Same.")
        val edit = session(store)
        assertTrue(edit.save(1, " Same. "))
        assertFalse(edit.canUndo)
    }

    @Test fun enterSplitsAtTheCaretOfTheTrimmedDraftAndUndoJoinsBack() = runBlocking {
        val store = FakeStore("First half second half.")
        val edit = session(store)
        // The draft has a leading space the store trims: the caret maps onto the stored text.
        val caret = edit.split(1, "  First half second half.", "  First half ".length)
        assertEquals(CaretAt(2, 0), caret)
        assertEquals(listOf("First half", "second half."), edit.shown(store))
        val back = edit.undo()
        assertEquals(CaretAt(1, "First half ".length), back)
        assertEquals(listOf("First half second half."), edit.shown(store))
        // Redo splits again; the new part has a new id, reached through the old one.
        val again = edit.redo()
        assertEquals(listOf("First half", "second half."), edit.shown(store))
        assertNotEquals(2L, again?.id)
        assertEquals(again?.id, edit.resolve(2))
    }

    @Test fun enterAtTheEndShowsAnEmptyParagraphTheStateDoesNotList() = runBlocking {
        val store = FakeStore("Last line.")
        val edit = session(store)
        val caret = edit.split(1, "Last line.", "Last line.".length)
        assertEquals(CaretAt(2, 0), caret)
        // The state lists visible paragraphs only; the session still draws the empty one, in its place.
        assertEquals(listOf(1L), store.visible().map { it.id })
        assertEquals(listOf("Last line.", ""), edit.shown(store))
        // Backspace in it joins it to the paragraph before, which the state's own order could not find.
        val joined = edit.mergeIntoPrevious(2, "")
        assertEquals(JoinResult.Joined(CaretAt(1, "Last line.".length)), joined)
        assertEquals(listOf("Last line."), edit.shown(store))
    }

    @Test fun backspaceJoinsWithOneSpaceAndTheCaretLandsWhereTheSecondBegins() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val edit = session(store)
        assertEquals(JoinResult.Joined(CaretAt(1, "Alpha. ".length)), edit.mergeIntoPrevious(2, "Beta."))
        assertEquals(listOf("Alpha. Beta."), edit.shown(store))
        // Undoing the join brings Beta back under a new id; older steps follow it.
        val back = edit.undo()
        assertEquals(listOf("Alpha.", "Beta."), edit.shown(store))
        assertEquals(back?.id, edit.resolve(2))
    }

    @Test fun aRefusedJoinKeepsBothParagraphsTheDraftAndTheCaretWithoutFailing() = runBlocking {
        val store = FakeStore("Legacy text.", "Recognized text.")
        store.legacy += 1L
        val edit = session(store)
        assertEquals(JoinResult.Refused, edit.mergeIntoPrevious(2, "Recognized text, edited."))
        assertEquals(listOf("Legacy text.", "Recognized text, edited."), edit.shown(store))
        assertFalse(edit.failed)
        assertEquals(0, edit.failures)
        // Only the draft's autosave is logged; undo takes back the typing, not a join that never happened.
        edit.undo()
        assertEquals(listOf("Legacy text.", "Recognized text."), edit.shown(store))
        assertFalse(edit.canUndo)
    }

    @Test fun backspaceInTheFirstParagraphJoinsNothing() = runBlocking {
        val store = FakeStore("Only.", "Second.")
        val edit = session(store)
        assertEquals(JoinResult.Skipped, edit.mergeIntoPrevious(1, "Only."))
        assertEquals(listOf("Only.", "Second."), edit.shown(store))
        assertFalse(edit.canUndo)
        assertFalse(edit.failed)
    }

    @Test fun anEditBeforeAJoinStillUndoesAfterTheJoinWasUndone() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val edit = session(store)
        edit.save(2, "Beta gamma.")
        edit.mergeIntoPrevious(2, "Beta gamma.")
        edit.undo() // the join: Beta gamma returns with a new id
        edit.undo() // the typing, on the paragraph's new id
        assertEquals(listOf("Alpha.", "Beta."), edit.shown(store))
    }

    @Test fun restoreOriginalIsOneUndoableStep() = runBlocking {
        val store = FakeStore("Edited words.")
        store.source[1] = "Original words."
        val edit = session(store)
        assertEquals(CaretAt(1, "Original words.".length), edit.restore(1, "Edited words."))
        assertEquals("Original words.", store.text[1])
        edit.undo()
        assertEquals("Edited words.", store.text[1])
    }

    @Test fun aFailedWriteSaysNotSavedAndKeepsCounting() = runBlocking {
        val store = FakeStore("Text.")
        val edit = session(store)
        store.failNext = true
        assertFalse(edit.save(1, "Text and more."))
        assertTrue(edit.failed)
        assertEquals(1, edit.failures)
        store.failNext = true
        assertFalse(edit.save(1, "Text and more."))
        assertEquals(2, edit.failures)
        assertTrue(edit.save(1, "Text and more."))
        assertFalse(edit.failed)
        assertEquals(0, edit.failures)
    }

    @Test fun aStaleRevisionKeepsTheDraftUntilTheStateReloads() = runBlocking {
        val store = FakeStore("Text.")
        val edit = session(store)
        store.revision[1] = 5 // something else wrote; the session's copy is behind
        assertFalse(edit.save(1, "Text, changed."))
        assertTrue(edit.failed)
        edit.sync(store.visible(), editing = true)
        assertTrue(edit.save(1, "Text, changed."))
        assertEquals("Text, changed.", store.text[1])
    }

    @Test fun replaceAllInsideEditIsOneStep() = runBlocking {
        val store = FakeStore("A screen.", "Another screen.")
        val edit = session(store)
        val before = store.visible().associate { it.id to it.text }
        store.text[1] = "A page."
        store.text[2] = "Another page."
        store.revision[1] = 2
        store.revision[2] = 2
        edit.recordReplace(listOf(store.ui(1), store.ui(2)), before)
        assertTrue(edit.undoReplace())
        assertEquals(listOf("A screen.", "Another screen."), edit.shown(store))
        assertFalse(edit.undoReplace())
    }

    @Test fun savedParagraphsCoverTheStateUntilItReloadsThenTheOrderIsDropped() = runBlocking {
        val store = FakeStore("One.", "Two.")
        val edit = session(store)
        val stale = store.visible()
        edit.save(1, "One, changed.")
        // Before the reload the old state is drawn with the saved paragraph over it.
        assertEquals(listOf("One, changed.", "Two."), edit.paragraphs(stale).map { it.text })
        edit.end()
        edit.sync(store.visible(), editing = false)
        assertEquals(store.visible(), edit.paragraphs(store.visible()))
    }

    @Test fun aRecreatedPageGoesOnWithItsSessionsLog() = runBlocking {
        val store = FakeStore("Alpha.", "Beta.")
        val edit = session(store)
        edit.save(1, "Alpha, typed.")
        edit.mergeIntoPrevious(2, "Beta.")
        edit.undo() // the join: Beta returns under a new id, and the join waits in redo
        val saved = edit.log()!!.encode()
        // The page is recreated: a new session over the reloaded note, with the log the saved state kept.
        val again = EditSession(store).also { it.begin(store.visible(), EditLog.decode(ArrayList<Any?>(saved))) }
        assertTrue(again.canUndo)
        assertTrue(again.canRedo)
        again.redo()
        assertEquals(listOf("Alpha, typed. Beta."), again.shown(store))
        again.undo()
        again.undo()
        assertEquals(listOf("Alpha.", "Beta."), again.shown(store))
        assertFalse(again.canUndo)
    }

    @Test fun theSavedLogKeepsEveryKindOfStepAndOnlyTheNewestThatFit() {
        val log = EditLog(
            undo = listOf(
                EditStep.Text(1, "a".repeat(10), "b".repeat(10)),
                EditStep.Split(1, 4, 9),
                EditStep.Merge(1, 9, 4),
                EditStep.Replace(listOf(EditStep.Text(1, "one", "two"), EditStep.Text(2, "три", "четыре"))),
            ),
            redo = listOf(EditStep.Text(2, "x", "y")),
            alias = mapOf(5L to 7L),
        )
        assertEquals(log, EditLog.decode(log.encode()))
        // Within 20 characters only the newest steps fit: the 20-character typing step is dropped, the older one.
        val small = EditLog.decode(log.encode(budget = 20))!!
        assertEquals(log.undo.drop(1), small.undo)
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
    }
}

class FindStateTest {
    private fun match(paragraph: Long, start: Int) = TextMatch(paragraph, start, start + 6)

    @Test fun theCurrentMatchStartsAtTheReadingPositionAndWraps() {
        val find = FindState()
        val found = listOf(match(1, 0), match(2, 4), match(3, 0))
        find.show("screen", found) { it.paragraphId >= 2 }
        assertEquals(match(2, 4), find.currentMatch)
        find.next()
        find.next()
        assertEquals(match(1, 0), find.currentMatch)
        find.previous()
        assertEquals(match(3, 0), find.currentMatch)
        assertEquals(listOf(4 until 10), find.matchesIn(2))
    }

    @Test fun theSameQueryAgainKeepsThePlace() {
        val find = FindState()
        find.show("screen", listOf(match(1, 0), match(2, 4), match(3, 0))) { true }
        find.next()
        find.show("screen", listOf(match(2, 4), match(3, 0))) { true }
        assertEquals(match(2, 4), find.currentMatch)
        find.show("screen", emptyList()) { true }
        assertTrue(find.none)
        assertNull(find.currentMatch)
    }
}

class NoteFormatTest {
    @Test fun playerTimesAreMinutesThenHours() {
        assertEquals("0:00", NoteTimes.clock(0))
        assertEquals("3:08", NoteTimes.clock(188_400))
        assertEquals("12:04", NoteTimes.clock(724_000))
        assertEquals("1:02:03", NoteTimes.clock(3_723_000))
    }

    @Test fun theAdjustablePlayerMovesFiveSecondsPerStep() {
        val duration = 24 * 60_000L + 2_000
        val end = PlayerSteps.rangeEnd(duration)
        val steps = PlayerSteps.steps(duration)
        assertEquals(PlayerSteps.STEP_MS.toFloat(), end / (steps + 1))
        assertTrue(end >= duration)
        assertEquals(0, PlayerSteps.steps(1_000))
    }

    @Test fun followKeepsThePlayingLineInTheMiddleThird() {
        // Band 100..400: the middle third is 200..300.
        assertEquals(0f, Follow.distance(220f, 248f, 100f, 400f))
        assertEquals(110f, Follow.distance(310f, 338f, 100f, 400f))
        assertEquals(-50f, Follow.distance(150f, 178f, 100f, 400f))
    }

    @Test fun aLineAcrossTheTopFadeLandsAnEdgeOnTheBand() {
        // The band's solid paper ends at 100, its fade at 116; lines are 28 tall.
        val paragraph = listOf(Span(80f, 108f), Span(108f, 136f), Span(136f, 164f))
        // Nothing crosses: the scroll stands.
        assertEquals(0f, TopEdge.snap(0f, paragraph.map { it.shifted(-20f) }, 100f, 16f, null, 0f, 1000f))
        // The first line crosses the band: the nearest edge lands on it (8 up hides it).
        assertEquals(8f, TopEdge.snap(0f, paragraph, 100f, 16f, null, 0f, 1000f))
        // Where the brought line may not rise past 130, the other way: the line shows whole from the band down.
        assertEquals(-20f, TopEdge.snap(0f, paragraph, 100f, 16f, Span(136f, 164f), 130f, 1000f))
        // A paragraph that starts inside the fade, the gap above it on the band: the last line above lands instead.
        val gap = listOf(Span(60f, 88f), Span(104f, 132f))
        assertEquals(4f, TopEdge.snap(0f, gap, 100f, 16f, null, 0f, 1000f))
        assertEquals(-12f, TopEdge.snap(0f, gap, 100f, 16f, Span(200f, 228f), 200f, 1000f))
        // A planned scroll is adjusted from where it would leave the lines: 20 would cut the second line, so 8.
        assertEquals(8f, TopEdge.snap(20f, paragraph, 100f, 16f, null, 0f, 1000f))
        // No way to keep the brought line where it must stay: the scroll stands.
        assertEquals(0f, TopEdge.snap(0f, paragraph, 100f, 16f, Span(136f, 164f), 136f, 164f))
    }

    @Test fun theMetaOpensWithAShortDateAndTime() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 6)
        val dates = LibraryDates(
            Locale.UK, zone, DatePatterns("HH:mm", "EEE", "EEEE", "d MMM", "d MMM y", "d MMMM", "d MMMM y"),
            DayWords("Today", "Yesterday"),
        )
        val dateline = SpokenDateline({ "Today, $it" }, { "Yesterday, $it" }, { date, time -> "$date, $time" })
        fun at(day: LocalDate) = LocalDateTime.of(day, LocalTime.of(9, 12)).toInstant(zone).toEpochMilli()
        assertEquals("Today, 09:12", dates.noteWhen(at(today), today, dateline))
        assertEquals("Yesterday, 09:12", dates.noteWhen(at(today.minusDays(1)), today, dateline))
        assertEquals("Mon, 09:12", dates.noteWhen(at(LocalDate.of(2026, 11, 2)), today, dateline))
        assertEquals("28 Oct, 09:12", dates.noteWhen(at(LocalDate.of(2026, 10, 28)), today, dateline))
        assertEquals("28 Oct 2025, 09:12", dates.noteWhen(at(LocalDate.of(2025, 10, 28)), today, dateline))
    }
}
