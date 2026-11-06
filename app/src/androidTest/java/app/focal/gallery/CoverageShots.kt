package app.focal.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.LibraryModel
import app.focal.ui.library.STAGE_PILL_TAG
import app.focal.ui.library.StageUi
import app.focal.ui.shell.RationaleMode
import app.focal.ui.shell.RecordFlow
import app.focal.ui.shell.RecordFlowSheet
import app.focal.ui.state.NotePhase
import app.focal.ui.state.PauseReason

/**
 * Spec 8.4's font-scale pass over the frames the stages rendered at 1.0 only (every frame at 1.0, 1.3 and 2.0, Light
 * and Dark), and the states no stage rendered: list rows that transcribe or wait, a fallback title, inline renames,
 * Edit's failed save and Edit with no paragraph focused, "Now playing", the reduced-motion swipe, the rationale sheet
 * (both modes) and the stage's model-failed and partial-failure last lines.
 */
object CoverageShots {
    val all: List<Shot> = buildList {
        addAll(scaled("transcribing") { Library(workingModel(NotePhase.TRANSCRIBING)) })
        addAll(scaled("ready") { Library(readyModel(fresh = true)) })
        addAll(scaled("copied", settleMs = COPIED_AT_MS, click = STAGE_PILL_TAG) { Library(readyModel(fresh = true)) })
        addAll(scaled("storage-full") { Library(liveModel(paused = true, reason = PauseReason.STORAGE)) })
        addAll(scaled("menu") { Library(idleModel(), menu = true) })
        addAll(scaled("peek") { Strip(PEEK) })
        addAll(scaled("settings-swipe-50") { Strip(HALF) })
        addAll(scaled("scrolled") { Library(idleModel(), scroll = true) })
        addAll(scaled("search") { Library(idleModel(), search = searchResults()) })
        addAll(scaled("sheet") { Sheet(RationaleMode.Allow) })
        addAll(scaled("sheet-denied") { Sheet(RationaleMode.OpenSettings) })
        addAll(scaled("find-replaced") { Note(design(replaced = true), find = "screen" to "page", replaced = true) })
        addAll(scaled("home-rows") { Library(rowsModel()) })
        addAll(themed("stage-model-failed") { Library(modelFailedModel()) })
        addAll(themed("stage-partial") { Library(partialModel()) })
        addAll(themed("rename-stage") { Library(idleModel(), rename = "note-0") })
        addAll(themed("rename-row") { Library(idleModel(), rename = "note-1") })
        addAll(themed("rename-note") { Note(design(), states = PageStates(rename = true)) })
        addAll(themed("edit-unfocused") { Note(design(), edit = true, states = PageStates(editField = false)) })
        addAll(themed("now-playing") { Note(longDesign(), playing = true, states = PageStates(followOff = true)) })
        // Reduced motion: the swipe is a fade-through on paper (H2), blank at exactly s = 0.5; these are either side of it.
        addAll(themed("settings-swipe-30-rm") { Reduced { Strip(REDUCED_OUT) } })
        addAll(themed("settings-swipe-70-rm") { Reduced { Strip(REDUCED_IN) } })
        add(Shot("rename-stage-light-200", dark = false, fontScale = LARGE) { Library(idleModel(), rename = "note-0") })
        add(Shot("now-playing-light-200", dark = false, fontScale = LARGE) { Note(longDesign(), playing = true, states = PageStates(followOff = true)) })
    }

    private const val COPIED_AT_MS = 300L
    private const val REDUCED_OUT = 0.3f
    private const val REDUCED_IN = 0.7f
    private const val PEEK = 32f / 420f
    private const val HALF = 0.5f
    private const val LARGE = 2f
}

/** Light and Dark at 1.0, 1.3 and 2.0 (spec 8.4): `name-light`, `name-dark-130`, `name-light-200`, ... */
internal fun scaled(name: String, settleMs: Long = 1_000L, click: String? = null, content: @Composable () -> Unit): List<Shot> =
    listOf(1f to "", 1.3f to "-130", 2f to "-200").flatMap { (scale, suffix) ->
        listOf(false, true).map { dark ->
            Shot("$name-${if (dark) "dark" else "light"}$suffix", dark, scale, settleMs, click, content)
        }
    }

@Composable
private fun Reduced(content: @Composable () -> Unit) = CompositionLocalProvider(LocalReducedMotion provides true) { content() }

/** The rationale sheet over the library (spec 5.11): before Android stops asking, and after ("Open settings"). */
@Composable
private fun Sheet(mode: RationaleMode) {
    val flow = remember { RecordFlow().also { it.sheet = mode } }
    Library(idleModel())
    RecordFlowSheet(flow)
}

/** The design note three times over, so the playing line can be scrolled out of view ("Now playing", spec 4.11). */
private fun longDesign() = design().let { note ->
    note.copy(paragraphs = timedParagraphs(List(LONG_REPEATS) { note.paragraphs.map { it.text } }.flatten(), note.durationMs))
}

private const val LONG_REPEATS = 3

/** The list with rows that are not ready (spec 4.12.4) and a note with no title yet (4.12.2's fallback). */
private fun rowsModel(): LibraryModel {
    val all = rows().toMutableList()
    all[1] = all[1].copy(phase = NotePhase.TRANSCRIBING, progress = 0.64f)
    all[2] = all[2].copy(phase = NotePhase.WAITING)
    all[3] = all[3].copy(title = "", titleIsCustom = false)
    return LibraryModel(loaded = true, stage = StageUi.Note(all.first(), fresh = false), days = days(all.drop(1)))
}

private fun modelFailedModel(): LibraryModel {
    val note = saturday.copy(phase = NotePhase.WAITING, waitingReason = "memory")
    return LibraryModel(loaded = true, stage = StageUi.Note(note, fresh = false), days = days(rows()))
}

private fun partialModel(): LibraryModel {
    val note = saturday.copy(phase = NotePhase.PARTIAL_FAILURE, failedParts = 1)
    return LibraryModel(loaded = true, stage = StageUi.Note(note, fresh = false), days = days(rows()))
}
