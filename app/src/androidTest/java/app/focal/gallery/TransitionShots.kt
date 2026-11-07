package app.focal.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.focal.ui.library.LibraryActions
import app.focal.ui.library.LibraryHost
import app.focal.ui.library.LibraryPage
import app.focal.ui.library.SettingsLayerState
import app.focal.ui.settings.LicensesPage
import app.focal.ui.settings.SettingsActions
import app.focal.ui.settings.SettingsPage
import app.focal.ui.shell.Destination
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.PageMotion
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.SearchUiState
import app.focal.ui.state.SettingsUiState

/**
 * Page changes at fixed times after their trigger (spec 6.1, 2.8.5): the stage opening into its note's page (T16′)
 * and folding back on Back, a row opening and folding back (T16), Finish in the library dock (T7b), and a
 * fade-through between the settings page and the licences. The predictive-back preview is not here: back events
 * dispatched from a test reach NavDisplay's handler only sometimes, so its frames would not be reproducible.
 */
object TransitionShots {
    val all: List<Shot> = buildList {
        for (t in listOf(40L, 100L, 180L)) add(Shot("t16-open-$t-light", dark = false, settleMs = after(AT_MS, t)) { StageToNote(back = false) })
        add(Shot("t16-open-100-dark", dark = true, settleMs = after(AT_MS, 100)) { StageToNote(back = false) })
        for (t in listOf(60L, 140L, 240L)) add(Shot("t16-back-$t-light", dark = false, settleMs = after(AT_MS + OPEN_MS, t)) { StageToNote(back = true) })
        add(Shot("t16-back-140-dark", dark = true, settleMs = after(AT_MS + OPEN_MS, 140)) { StageToNote(back = true) })
        for (t in listOf(60L, 140L)) add(Shot("t16-row-open-$t-light", dark = false, settleMs = after(AT_MS, t)) { RowToNote(back = false) })
        add(Shot("t16-row-back-120-light", dark = false, settleMs = after(AT_MS + OPEN_MS, 120)) { RowToNote(back = true) })
        for (t in listOf(60L, 140L, 260L)) add(Shot("t7b-$t-light", dark = false, settleMs = after(AT_MS, t)) { LiveToNote() })
        add(Shot("t7b-140-dark", dark = true, settleMs = after(AT_MS, 140)) { LiveToNote() })
        add(Shot("licenses-fade-90-light", dark = false, settleMs = after(AT_MS, 90)) { SettingsToLicenses() })
        add(Shot("licenses-fade-180-light", dark = false, settleMs = after(AT_MS, 180)) { SettingsToLicenses() })
    }
}

/** The library settles before the change starts; a page settles before Back (whole frames). */
private const val AT_MS = 512L
private const val OPEN_MS = 608L

/** The note the stage holds (the design renders' note) and the page it opens. */
private val stageNote: NoteUiState get() = design().copy(noteId = "note-0")

/** A row's note: "Pick up the prescription before six". */
private val rowNote: NoteUiState
    get() {
        val row = rows()[2]
        return NoteUiState(
            noteId = row.id, loaded = true, title = row.title, titleIsCustom = true, created = row.created, durationMs = row.durationMs,
            phase = NotePhase.READY, audioPresent = true, canPlay = true, editable = true,
            paragraphs = timedParagraphs(listOf(row.preview), row.durationMs), canRemoveAudio = true,
        )
    }

@Composable
private fun NotePageOf(note: NoteUiState, destination: Destination.Note, landing: Boolean = false) = NoteDestination(
    note, destination, playing = false, edit = false, find = null, nextMatch = false, replaced = false,
    captureElsewhere = false, scroll = null, landing = landing,
)

/** T16′: a tap on the stage opens its note's page; with [back], Back folds the page into the stage. */
@Composable
private fun StageToNote(back: Boolean) {
    val shell = rememberShellState()
    val note = remember { stageNote }
    LaunchedEffect(shell) {
        waitFrames(AT_MS)
        shell.open(Destination.Note(note.noteId), PageMotion.Stage)
        if (!back) return@LaunchedEffect
        waitFrames(OPEN_MS)
        shell.back()
    }
    FocalShell(shell, destination = { d ->
        if (d is Destination.Note) NotePageOf(note, d) else LibraryDestination(idleModel())
    })
}

/** T16: a row opens its note's page; with [back], Back folds the page into the row. */
@Composable
private fun RowToNote(back: Boolean) {
    val shell = rememberShellState()
    val note = remember { rowNote }
    LaunchedEffect(shell) {
        waitFrames(AT_MS)
        shell.open(Destination.Note(note.noteId), PageMotion.Row)
        if (!back) return@LaunchedEffect
        waitFrames(OPEN_MS)
        shell.back()
    }
    FocalShell(shell, destination = { d ->
        if (d is Destination.Note) NotePageOf(note, d) else LibraryDestination(idleModel())
    })
}

/** T7b: Finish in the library dock opens the live stage into the new note's page, which develops as in T7. */
@Composable
private fun LiveToNote() {
    val shell = rememberShellState()
    val note = remember { saturdayNote(NotePhase.TRANSCRIBING) }
    LaunchedEffect(shell) {
        waitFrames(AT_MS)
        shell.open(Destination.Note(note.noteId), PageMotion.LiveStage)
    }
    FocalShell(shell, destination = { d ->
        if (d is Destination.Note) NotePageOf(note, d, landing = true) else LibraryDestination(liveModel(paused = false))
    })
}


/** Licences from the settings page: the two pages fade through (the settings page is gone before the licences show). */
@Composable
private fun SettingsToLicenses() {
    val shell = rememberShellState()
    LaunchedEffect(shell) {
        shell.settings.track(1f)
        waitFrames(AT_MS)
        shell.open(Destination.Licenses)
    }
    val actions = remember { LibraryActions() }
    FocalShell(shell, destination = { d ->
        when (d) {
            Destination.Licenses -> LicensesPage(onBack = {})
            else -> LibraryHost(
                strip = shell.settings, preferences = null, armed = { true }, hasNotes = true, capturing = false, blocked = false,
                layer = remember { SettingsLayerState() },
                library = { LibraryPage(idleModel(), SearchUiState(), meter, dates, today, actions) },
                settings = {
                    SettingsPage(
                        SettingsUiState(audioBytes = 1_400_000_000L, notesWithAudio = 38, versionName = "1.0.0"),
                        remember { SettingsActions() },
                    )
                },
            )
        }
    })
}
