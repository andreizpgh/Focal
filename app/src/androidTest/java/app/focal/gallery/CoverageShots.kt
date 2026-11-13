package app.focal.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.focal.ui.design.FocalColors
import app.focal.ui.design.LocalFocalColors
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.LibraryModel
import app.focal.ui.library.STAGE_PILL_TAG
import app.focal.ui.library.StageUi
import app.focal.ui.shell.RationaleMode
import app.focal.ui.shell.RecordFlow
import app.focal.ui.shell.RecordFlowSheet
import app.focal.ui.state.NotePhase
import app.focal.ui.state.PauseReason
import app.focal.ui.state.WaitingReason

/**
 * Spec 8.4's font-scale pass over the frames the stages rendered at 1.0 only (every frame at 1.0, 1.3 and 2.0, Light
 * and Dark), and the states no stage rendered: list rows that transcribe or wait, a fallback title, inline renames,
 * Edit's failed save and Edit with no paragraph focused, "Now playing", the reduced-motion swipe, the
 * rationale sheet (both modes), the stage's model-failed and partial-failure last lines, right-to-left layouts (8.5)
 * and the contrast and high-contrast-text palettes (8.1).
 */
object CoverageShots {
    val all: List<Shot> = buildList {
        addAll(scaled("transcribing") { Library(workingModel(NotePhase.TRANSCRIBING)) })
        addAll(scaled("ready") { Library(readyModel(fresh = true)) })
        addAll(scaled("copied", settleMs = COPIED_AT_MS, click = STAGE_PILL_TAG) { Library(readyModel(fresh = true)) })
        addAll(scaled("storage-full") { Library(liveModel(paused = true, reason = PauseReason.STORAGE)) })
        addAll(scaled("select") { Library(idleModel(), selected = listOf("note-0", "note-2")) })
        addAll(scaled("hint") { Library(idleModel(), hint = true) })
        addAll(scaled("settings-swipe-50") { Strip(HALF) })
        addAll(scaled("scrolled") { Library(idleModel(), scroll = true) })
        addAll(scaled("search") { Library(idleModel(), search = searchResults()) })
        addAll(scaled("sheet") { Sheet(RationaleMode.Allow) })
        addAll(scaled("sheet-denied") { Sheet(RationaleMode.OpenSettings) })
        addAll(scaled("find-replaced") { Note(design(replaced = true), find = "screen" to "page", replaced = true) })
        addAll(scaled("home-rows") { Library(rowsModel()) })
        addAll(themed("stage-model-failed") { Library(modelFailedModel()) })
        // The other reasons a waiting note gives (spec 5.4): repeated failures, storage for the model, a failed copy.
        add(Shot("stage-waiting-failures-light", dark = false) { Library(modelFailedModel(WaitingReason.Failures)) })
        add(Shot("stage-waiting-storage-light", dark = false) { Library(modelFailedModel(WaitingReason.Storage(850_000_000))) })
        addAll(scaled("stage-waiting-install") { Library(modelFailedModel(WaitingReason.Install)) })
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
        // "Now playing" tapped: the playing line back in the band's middle third, a line edge on the top band (2.6).
        add(Shot("now-playing-back-light", dark = false, settleMs = FOLLOW_BACK_MS) { Note(longDesign(), playing = true, states = PageStates(followOff = true, followBack = true)) })
        add(Shot("now-playing-back-light-200", dark = false, fontScale = LARGE, settleMs = FOLLOW_BACK_MS) { Note(longDesign(), playing = true, states = PageStates(followOff = true, followBack = true)) })
        // Edit's failed save: "Not saved" in the bar, Done reads "Retry" (spec 4.1.3).
        addAll(themed("edit-unsaved", settleMs = UNSAVED_MS) { Note(design(), edit = true, states = PageStates(unsaved = true)) })
        // Right to left (spec 8.5): the dock's circle at the start edge, the stage's time at the title's left, fills
        // from the start edge, the settings strip mirrored. The words stay English: only the layout mirrors.
        addAll(themed("home-rtl") { Rtl { Library(idleModel()) } })
        add(Shot("recording-rtl-light", dark = false) { Rtl { Library(liveModel(paused = true)) } })
        // The first frames of an expand from the mirrored live stage: the morph starts where the stage's row is.
        add(Shot("expand-10-rtl-light", dark = false) { Rtl { SceneFrame(EXPAND_EARLY, live()) } })
        add(Shot("expand-10-light", dark = false) { SceneFrame(EXPAND_EARLY, live()) })
        add(Shot("rec-paused-rtl-light", dark = false) { Rtl { SceneFrame(1f, paused()) } })
        add(Shot("transcribing-rtl-light", dark = false) { Rtl { Library(workingModel(NotePhase.TRANSCRIBING)) } })
        add(Shot("settings-swipe-50-rtl-light", dark = false) { Rtl { Strip(HALF) } })
        add(Shot("note-rtl-light", dark = false) { Rtl { Note(design()) } })
        add(Shot("playing-rtl-light", dark = false) { Rtl { Note(design(), playing = true) } })
        // A title renamed in a right-to-left script on a left-to-right interface follows its own direction, as the
        // transcript does; the interface around it (day labels, the top bar) keeps the layout's (8.5).
        add(Shot("home-rtl-title-light", dark = false) { Library(rtlTitleModel()) })
        add(Shot("note-rtl-title-light", dark = false) { Note(design().copy(title = RTL_TITLE, titleIsCustom = true)) })
        add(Shot("note-rtl-title-rtl-light", dark = false) { Rtl { Note(design().copy(title = RTL_TITLE, titleIsCustom = true)) } })
        // The system's contrast setting (medium or high: muted text lifted, spec 8.1) and high-contrast text (no
        // receding: every text in its ink).
        addAll(contrasted("home-contrast", contrast = 1f) { Library(idleModel()) })
        addAll(contrasted("recording-contrast", contrast = 1f) { Library(liveModel(paused = true)) })
        addAll(contrasted("note-contrast", contrast = 1f) { Note(design()) })
        addAll(contrasted("home-hctext", highContrastText = true) { Library(idleModel()) })
        addAll(contrasted("playing-hctext", highContrastText = true) { Note(design(), playing = true) })
    }

    private const val COPIED_AT_MS = 300L
    /** Typing at 400 ms, the autosave 600 ms later, then its failure on screen. */
    private const val UNSAVED_MS = 1_400L
    /** Follow off at 400 ms, "Now playing" 400 ms later, then the bring (`move`) and the edge settle. */
    private const val FOLLOW_BACK_MS = 2_000L
    private const val REDUCED_OUT = 0.3f
    private const val REDUCED_IN = 0.7f
    private const val HALF = 0.5f
    private const val EXPAND_EARLY = 0.1f
    private const val LARGE = 2f
}

/** Light and Dark at 1.0, 1.3 and 2.0 (spec 8.4): `name-light`, `name-dark-130`, `name-light-200`, ... */
internal fun scaled(name: String, settleMs: Long = 1_000L, click: String? = null, content: @Composable () -> Unit): List<Shot> =
    listOf(1f to "", 1.3f to "-130", 2f to "-200").flatMap { (scale, suffix) ->
        listOf(false, true).map { dark ->
            Shot("$name-${if (dark) "dark" else "light"}$suffix", dark, scale, settleMs, click, content = content)
        }
    }

@Composable
private fun Reduced(content: @Composable () -> Unit) = CompositionLocalProvider(LocalReducedMotion provides true) { content() }

@Composable
internal fun Rtl(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }

/** Light and Dark with the palette the system's contrast setting or high-contrast text gives (FocalColors.of). */
private fun contrasted(name: String, contrast: Float = 0f, highContrastText: Boolean = false, content: @Composable () -> Unit): List<Shot> =
    listOf(false, true).map { dark ->
        Shot("$name-${if (dark) "dark" else "light"}", dark) {
            CompositionLocalProvider(LocalFocalColors provides FocalColors.of(dark, contrast, highContrastText)) { content() }
        }
    }

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

/** A synthetic title in Hebrew ("Summary of Thursday's meeting"), as a person might rename a note. */
private const val RTL_TITLE = "סיכום הפגישה של יום חמישי"

/** The stage's note and a row renamed in a right-to-left script. */
private fun rtlTitleModel(): LibraryModel {
    val all = rows().toMutableList()
    all[0] = all[0].copy(title = RTL_TITLE)
    all[2] = all[2].copy(title = RTL_TITLE)
    return LibraryModel(loaded = true, stage = StageUi.Note(all.first(), fresh = false), days = days(all.drop(1)))
}

private fun modelFailedModel(reason: WaitingReason = WaitingReason.Memory): LibraryModel {
    val note = saturday.copy(phase = NotePhase.WAITING, waitingReason = reason)
    return LibraryModel(loaded = true, stage = StageUi.Note(note, fresh = false), days = days(rows()))
}

private fun partialModel(): LibraryModel {
    val note = saturday.copy(phase = NotePhase.PARTIAL_FAILURE, failedParts = 1)
    return LibraryModel(loaded = true, stage = StageUi.Note(note, fresh = false), days = days(rows()))
}
