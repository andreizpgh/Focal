package app.focal.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.data.TextMatch
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalMenuDivider
import app.focal.ui.components.FocalMenuItem
import app.focal.ui.components.MenuSurface
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.note.EditBackend
import app.focal.ui.note.FieldFocus
import app.focal.ui.note.NoteActions
import app.focal.ui.note.NotePage
import app.focal.ui.note.NotePageState
import app.focal.ui.note.NotePlayback
import app.focal.ui.note.rememberNotePageState
import app.focal.ui.shell.Destination
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.EndReason
import app.focal.ui.state.GapUi
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlaybackSession
import app.focal.ui.state.WordUi
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The note stage's renders (spec 5.4 to 5.9): reading, scrolled (the collapsed title), playing (lens, strip, follow),
 * Edit (the sheet, the field, the provenance line), Find and the replaced state, transcribing and waiting, partial
 * failure with a gap row, no speech, text only, an interrupted recording, the menu, legacy playback without word
 * times, and 200 % font. Names follow the v2 renders (note, playing, edit, find, find-replaced, transcribing).
 */
object NoteShots {
    val all: List<Shot> = buildList {
        addAll(themed("note") { Note(design()) })
        addAll(themed("note-scrolled") { Note(design(), scroll = 1 to SCROLLED_PX) })
        addAll(themed("playing") { Note(design(), playing = true) })
        add(Shot("playing-legacy-light", dark = false) { Note(design(timed = false), playing = true) })
        addAll(themed("edit") { Note(design(edited = true), edit = true, scroll = 2 to 0) })
        addAll(themed("find") { Note(design(), find = "screen" to "page", nextMatch = true) })
        addAll(themed("find-replaced") { Note(design(replaced = true), find = "screen" to "page", replaced = true) })
        addAll(themed("transcribing") { Note(saturdayNote(NotePhase.TRANSCRIBING)) })
        add(Shot("waiting-light", dark = false) { Note(saturdayNote(NotePhase.WAITING), captureElsewhere = true) })
        add(Shot("model-failed-light", dark = false) { Note(saturdayNote(NotePhase.WAITING).copy(waitingReason = "memory", canRetry = true)) })
        addAll(themed("partial") { Note(partial()) })
        add(Shot("no-speech-light", dark = false) { Note(noSpeech()) })
        add(Shot("text-only-light", dark = false) { Note(design().copy(audioPresent = false, canPlay = false, canRemoveAudio = false)) })
        add(Shot("ended-light", dark = false) { Note(design().copy(endReason = EndReason.PROCESS_ENDED, durationMs = 41 * 60_000L + 12_000)) })
        add(Shot("unavailable-light", dark = false) { Note(design().copy(canPlay = false)) })
        addAll(themed("menu") { Note(design(), menu = true) })
        add(Shot("note-light-200", dark = false, fontScale = 2f) { Note(design()) })
        add(Shot("edit-light-200", dark = false, fontScale = 2f) { Note(design(edited = true), edit = true, scroll = 2 to 0) })
        add(Shot("find-light-200", dark = false, fontScale = 2f) { Note(design(), find = "screen" to "page") })
        add(Shot("transcribing-light-200", dark = false, fontScale = 2f) { Note(saturdayNote(NotePhase.TRANSCRIBING)) })
    }
}

private const val SCROLLED_PX = 420

/** The design renders' note (v2 `note`, `playing`, `edit`, `find`). Synthetic text, never user data. */
private val DesignText = listOf(
    "Notes for Thursday’s design review. Start with the recording screen, because that’s the moment people decide whether to trust the app at all.",
    "The second screen asks for too much at once: microphone, notifications and a theme choice. I would move the permission prompt to the moment someone first taps Record.",
    "The welcome screen can lose the subtitle. The headline already says what the app does, and the extra line pushes the button below the fold on small phones.",
    "On the last screen, the illustration is lovely but slow to load. Let’s try the static version first and see whether anyone misses the motion.",
    "Then test it with five people and decide on Friday. Send the deck to Ines by Friday morning, so she has the weekend.",
)
private const val EDITED_THIRD =
    "The welcome screen can lose the subtitle entirely. The headline already says what the app does, and the extra line pushes the button below the fold on small phones."

private val SaturdayText = FakeScene + "Last thing: everyone checks their boots on Friday, not on the morning itself, and we meet back at the car park by six."

private val zone: ZoneId = ZoneId.systemDefault()
private fun at(time: String) = LocalDateTime.of(today, LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

/** Paragraphs with evenly spread word times over [totalMs]; [doubtful] words get the dotted underline. */
internal fun timedParagraphs(texts: List<String>, totalMs: Long, timed: Boolean = true, doubtful: Set<String> = emptySet()): List<ParagraphUi> {
    val count = texts.sumOf { it.split(' ').size }
    val step = totalMs / count
    var word = 0
    return texts.mapIndexed { index, text ->
        var offset = 0
        val words = text.split(' ').map { token ->
            val start = offset
            offset += token.length + 1
            val time = word++ * step
            WordUi(start, start + token.length, time.takeIf { timed }, (time + step * 4 / 5).takeIf { timed }, token.trimEnd(',', '.') in doubtful)
        }
        val first = words.first().startMs ?: (index * step * 20)
        ParagraphUi(
            id = index + 1L, revision = 1, text = text, startMs = first, endMs = words.last().endMs ?: (first + step * 20),
            words = words, exactTimes = timed, edited = false, canRestore = true, open = false,
        )
    }
}

private const val DESIGN_MS = 12 * 60_000L + 4_000

internal fun design(timed: Boolean = true, edited: Boolean = false, replaced: Boolean = false): NoteUiState {
    var texts = DesignText
    if (edited) texts = texts.toMutableList().also { it[2] = EDITED_THIRD }
    if (replaced) texts = texts.map { it.replace("screen", "page") }
    val paragraphs = timedParagraphs(texts, DESIGN_MS, timed, doubtful = setOf("Ines")).map { paragraph ->
        if (edited && paragraph.id == THIRD) paragraph.copy(edited = true) else paragraph
    }
    return NoteUiState(
        noteId = "design", loaded = true, title = "Notes for Thursday’s design review", titleIsCustom = false,
        created = at("16:05"), durationMs = DESIGN_MS, phase = NotePhase.READY, audioPresent = true, canPlay = true,
        editable = true, paragraphs = paragraphs, canRemoveAudio = true,
    )
}

private const val THIRD = 3L

internal fun saturdayNote(phase: NotePhase) = NoteUiState(
    noteId = "saturday", loaded = true, title = "So the plan for Saturday is simple", created = at("14:32"),
    durationMs = 4 * 60_000L, phase = phase, progress = 0.64f, audioPresent = true, canPlay = true,
    paragraphs = timedParagraphs(SaturdayText, 4 * 60_000L),
)

private fun partial(): NoteUiState {
    val note = design()
    return note.copy(
        phase = NotePhase.PARTIAL_FAILURE, canRetry = true, canRemoveAudio = false, failedParts = 1,
        gaps = listOf(GapUi(note.paragraphs[1].endMs, note.paragraphs[1].endMs + 42_000, afterParagraphId = 2, failed = true)),
    )
}

private fun noSpeech() = NoteUiState(
    noteId = "quiet", loaded = true, title = "", created = at("09:48"), durationMs = 9_000, phase = NotePhase.READY,
    audioPresent = true, canPlay = true, editable = true, noSpeech = true,
)

/** A player that holds still at one word, playing or paused. */
private class StillPlayback(override val session: PlaybackSession, private val at: Long) : NotePlayback {
    override fun positionMs(): Long = at
    override fun playWord(paragraph: ParagraphUi, offset: Int) = Unit
    override fun playFrom(ms: Long) = Unit
    override fun playRange(startMs: Long, endMs: Long) = Unit
    override fun toggle() = Unit
    override fun pause() = Unit
    override fun stop() = Unit
}

private object NoEdits : EditBackend {
    override suspend fun edit(id: Long, revision: Long, text: String) = EditOutcome.NotEditable
    override suspend fun split(id: Long, revision: Long, offset: Int) = EditOutcome.NotEditable
    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long) = EditOutcome.NotEditable
    override suspend fun restore(id: Long, revision: Long) = EditOutcome.NotEditable
}

/** A store that cannot write: every save fails as full storage does. */
private class FailingEdits(private val message: String) : EditBackend {
    override suspend fun edit(id: Long, revision: Long, text: String) = EditOutcome.Failed(message)
    override suspend fun split(id: Long, revision: Long, offset: Int) = EditOutcome.Failed(message)
    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long) = EditOutcome.Failed(message)
    override suspend fun restore(id: Long, revision: Long) = EditOutcome.Failed(message)
}

/** Find over the fake paragraphs, folded by case like the store. */
private fun findIn(note: NoteUiState, query: String): List<TextMatch> = note.paragraphs.flatMap { paragraph ->
    buildList {
        var from = paragraph.text.indexOf(query, ignoreCase = true)
        while (from >= 0) {
            add(TextMatch(paragraph.id, from, from + query.length))
            from = paragraph.text.indexOf(query, from + query.length, ignoreCase = true)
        }
    }
}

/** The note page in the real shell (dock, strip, rows, feedback pill), from fake state. */
@Composable
internal fun Note(
    note: NoteUiState,
    playing: Boolean = false,
    edit: Boolean = false,
    find: Pair<String, String>? = null,
    nextMatch: Boolean = false,
    replaced: Boolean = false,
    menu: Boolean = false,
    captureElsewhere: Boolean = false,
    scroll: Pair<Int, Int>? = null,
    landing: Boolean = false,
    states: PageStates = PageStates(),
) {
    val shell = rememberShellState()
    val destination = remember(note.noteId) { Destination.Note(note.noteId) }
    LaunchedEffect(shell) { shell.open(destination) }
    FocalShell(shell, destination = { current ->
        if (current is Destination.Note) {
            NoteDestination(note, destination, playing, edit, find, nextMatch, replaced, captureElsewhere, scroll, landing, states)
            if (menu) NoteMenuInline()
        } else Box(Modifier.fillMaxSize())
    })
}

/**
 * Page states the gallery sets up: an inline rename, Edit with no focused paragraph, follow off (and back on, as
 * "Now playing" turns it), a failed save.
 */
internal data class PageStates(
    val rename: Boolean = false,
    val editField: Boolean = true,
    val followOff: Boolean = false,
    /** After [followOff], "Now playing": follow resumes and brings the playing line back into the band. */
    val followBack: Boolean = false,
    val unsaved: Boolean = false,
    /** The focused paragraph takes focus, so the keyboard opens (the gallery waits for it). */
    val focus: Boolean = false,
)

@Composable
internal fun NoteDestination(
    note: NoteUiState,
    destination: Destination.Note,
    playing: Boolean,
    edit: Boolean,
    find: Pair<String, String>?,
    nextMatch: Boolean,
    replaced: Boolean,
    captureElsewhere: Boolean,
    scroll: Pair<Int, Int>?,
    landing: Boolean,
    states: PageStates = PageStates(),
) {
    val shell = app.focal.ui.shell.LocalShell.current
    val list: LazyListState = rememberLazyListState()
    scroll?.let { (index, offset) -> LaunchedEffect(list) { list.scrollToItem(index, offset) } }
    val third = note.paragraphs.getOrNull(2)
    val field = if (edit && states.editField && third != null) {
        remember { FieldFocus(third.id, third.text, "The welcome screen can lose the subtitle entirely.".length) }
    } else null
    val page: NotePageState = rememberNotePageState(editing = edit, field = field, takesFocus = states.focus)
    if (states.rename) LaunchedEffect(page) { page.renaming = true }
    if (states.followOff) LaunchedEffect(page) {
        waitFrames(FIND_SETTLE_MS)
        page.followSuspended = true
        list.scrollToItem(note.paragraphs.size)
        if (states.followBack) {
            waitFrames(FIND_SETTLE_MS)
            // What the "Now playing" pill does.
            page.followSuspended = false
            page.playingOutside = null
        }
    }
    // Typing in the focused paragraph: its autosave fails ("Not saved", Done reads "Retry", spec 4.1.3).
    if (states.unsaved && field != null) LaunchedEffect(field) {
        waitFrames(FIND_SETTLE_MS)
        field.text.edit { append(UNSAVED_TYPING) }
    }
    val word = note.paragraphs.getOrNull(1)?.let { p -> p.words.firstOrNull { p.text.substring(it.start, it.end) == "permission" } }
    val playback = remember(playing) {
        StillPlayback(
            if (playing) PlaybackSession(note.noteId, playing = true, durationMs = note.durationMs) else PlaybackSession(),
            (word?.startMs ?: note.paragraphs.getOrNull(1)?.startMs ?: 0L) + WORD_INTO_MS,
        )
    }
    if (find != null) LaunchedEffect(page) {
        page.find.open(fromEdit = false)
        page.find.query.setTextAndPlaceCursorAtEnd(find.first)
        page.find.replacement.setTextAndPlaceCursorAtEnd(find.second)
        if (nextMatch) {
            waitFrames(FIND_SETTLE_MS)
            page.find.next()
        }
    }
    if (replaced) LaunchedEffect(shell) { shell.bottom.feedback.show("Replaced 4", FeedbackAction("Undo", FocalIcon.Undo)) }
    val actions = remember(note) { NoteActions(find = { query -> findIn(note, query) }, copy = { true }) }
    val saveFailed = stringResource(R.string.error_save)
    val edits = remember(states.unsaved, saveFailed) { if (states.unsaved) FailingEdits(saveFailed) else NoEdits }
    NotePage(
        note = note, playback = playback, actions = actions, edits = edits, destination = destination,
        dates = dates, today = today, captureElsewhere = captureElsewhere, landing = landing, listState = list, page = page,
    )
}

/** The note page under T7 (Finish in the scene): transcribing, developing as the scene settles over it. */
@Composable
internal fun TranscribingNote(destination: Destination.Note) {
    NoteDestination(
        saturdayNote(NotePhase.TRANSCRIBING).copy(noteId = destination.id), destination, playing = false, edit = false, find = null,
        nextMatch = false, replaced = false, captureElsewhere = false, scroll = null, landing = true,
    )
}

/** The note menu as ⋮ opens it (a popup on a phone; drawn inline here): its right edge at 400, 4 dp under the bar's ⋮. */
@Composable
private fun NoteMenuInline() {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        val top = FocalLayout.topBar - (FocalLayout.topBar - FocalLayout.target) / 2 + FocalGrid.x4
        MenuSurface(Modifier.menuAt(x = MENU_END, y = top, endAligned = true)) {
            FocalMenuItem("Find and replace", {})
            FocalMenuItem("Rename", {})
            FocalMenuItem("Share", {})
            FocalMenuDivider()
            FocalMenuItem("Remove audio", {}, trailing = "11 MB")
            FocalMenuItem("Delete", {})
        }
    }
}

/** The ⋮ button's end edge: the menu's top-end corner (spec 4.15, ref x 400). */
private val MENU_END = 400.dp
private const val WORD_INTO_MS = 50L
private const val FIND_SETTLE_MS = 400L
private const val UNSAVED_TYPING = " Twice."
