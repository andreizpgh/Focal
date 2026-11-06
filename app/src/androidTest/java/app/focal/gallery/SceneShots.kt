package app.focal.gallery

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.focal.recording.SpeechAvailability
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.LibraryModel
import app.focal.ui.library.StageUi
import app.focal.ui.scene.RecordingSurface
import app.focal.ui.scene.SceneActions
import app.focal.ui.scene.SceneUi
import app.focal.ui.shell.Destination
import app.focal.ui.shell.PageMotion
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.SceneBottom
import app.focal.ui.shell.ShellState
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.LiveParagraph
import app.focal.ui.state.PauseReason

/**
 * The recording scene's renders: the scene at rest in each state, the morph at fixed p (the v3 strips: 25, 60 and
 * 90 %; paused at 40 %; storage full at 50 %), Record from the stage (T1′) and from the empty library (T1′e) at fixed
 * times after the tap, Finish settling into the note (T7), the reduced-motion crossfade, and 200 % font.
 */
object SceneShots {
    val all: List<Shot> = buildList {
        addAll(themed("rec-start") { SceneFrame(1f, start()) })
        addAll(themed("rec-live") { SceneFrame(1f, live()) })
        addAll(themed("rec-paused") { SceneFrame(1f, paused()) })
        add(Shot("rec-paused-storage-light", dark = false) { SceneFrame(1f, paused(PauseReason.STORAGE)) })
        add(Shot("rec-preparing-light", dark = false) { SceneFrame(1f, start().copy(speech = SpeechAvailability.PREPARING)) })
        add(Shot("rec-notifications-off-light", dark = false) { SceneFrame(1f, start().copy(notificationsOff = true)) })
        add(Shot("rec-latest-light", dark = false) { SceneFrame(1f, long(), scrollTo = LATEST_SCROLL) })
        for (p in listOf(25, 60, 90)) add(Shot("expand-$p-light", dark = false) { SceneFrame(p / PERCENT, live()) })
        add(Shot("expand-60-dark", dark = true) { SceneFrame(0.6f, live()) })
        add(Shot("expand-paused-40-light", dark = false) { SceneFrame(0.4f, paused()) })
        add(Shot("expand-storage-50-light", dark = false) { SceneFrame(0.5f, paused(PauseReason.STORAGE)) })
        add(Shot("expand-start-60-light", dark = false) { SceneFrame(0.6f, start()) })
        for (t in listOf(60L, 160L, 300L)) add(Shot("record-$t-light", dark = false, settleMs = after(RECORD_AT_MS, t)) { RecordFrame(fromEmpty = false) })
        for (t in listOf(60L, 120L, 200L, 600L)) {
            add(Shot("record-empty-$t-light", dark = false, settleMs = after(RECORD_AT_MS, t)) { RecordFrame(fromEmpty = true) })
        }
        add(Shot("record-empty-120-dark", dark = true, settleMs = after(RECORD_AT_MS, 120L)) { RecordFrame(fromEmpty = true) })
        for (t in listOf(120L, 260L)) add(Shot("settle-$t-light", dark = false, settleMs = t) { SettleFrame() })
        add(Shot("crossfade-50-light", dark = false) { SceneFrame(0.5f, live(), reduced = true) })
        add(Shot("rec-live-light-200", dark = false, fontScale = 2f) { SceneFrame(1f, live()) })
        add(Shot("rec-start-light-200", dark = false, fontScale = 2f) { SceneFrame(1f, start()) })
        add(Shot("expand-60-light-200", dark = false, fontScale = 2f) { SceneFrame(0.6f, live()) })
    }
}

private const val PERCENT = 100f
/** Record is tapped once the library has settled, so its dock (Record alone, or Search | Record) morphs from rest. */
private const val RECORD_AT_MS = 512L
private const val LATEST_SCROLL = 3

internal fun paragraphs(texts: List<String>) = texts.mapIndexed { i, text -> LiveParagraph(i.toLong(), text, open = i == texts.lastIndex) }

private fun start() = SceneUi(elapsedSeconds = 4, hint = true)
internal fun live() = SceneUi(elapsedSeconds = 256, paragraphs = paragraphs(FakeScene))
internal fun paused(reason: PauseReason? = null) =
    SceneUi(elapsedSeconds = 768, paused = true, pauseReason = reason, paragraphs = paragraphs(FakeLive))
/** Enough text to scroll back by more than a paragraph. */
private fun long() = SceneUi(elapsedSeconds = 1_384, paragraphs = paragraphs(FakeScene + FakeLive + FakeScene))

/** The library under the scene, its stage live with the same recording. */
internal fun libraryOf(scene: SceneUi) = LibraryModel(
    loaded = true,
    stage = StageUi.Live(scene.elapsedSeconds, scene.paused, scene.pauseReason, scene.speech, scene.paragraphs, hint = scene.hint),
    days = days(rows()),
)

/** The scene's own parts inside a shell: its dock on the root column, the surface over the pages. */
@Composable
internal fun SceneLayer(shell: ShellState, scene: SceneUi, list: LazyListState = rememberLazyListState()) {
    SceneBottom(DockState.Live(scene.paused), remember { DockActions() })
    RecordingSurface(shell.live, scene, meter, remember { SceneActions() }, onLibrary = shell.current == Destination.Library, listState = list)
}

/** The live library with the surface at a fixed [p] (the morph is a pure function of p). */
@Composable
internal fun SceneFrame(p: Float, scene: SceneUi, reduced: Boolean = false, scrollTo: Int? = null) {
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        val shell = rememberShellState()
        val list = rememberLazyListState()
        LaunchedEffect(shell) { shell.live.expansion.snapTo(p) }
        if (scrollTo != null) LaunchedEffect(list) { list.scrollToItem(scrollTo) }
        FocalShell(shell, destination = { LibraryDestination(libraryOf(scene)) }, scene = { SceneLayer(shell, scene, list) })
    }
}

/**
 * Record over the settled library: from the idle stage (T1′) or the empty library's resting instrument (T1′e). The
 * scene registers its dock only once it is composed, as in the app, so the library's dock morphs into Pause | Finish
 * (T1′e: Pause grows 0 → 56 while Record's left edge moves 24 → 92).
 */
@Composable
private fun RecordFrame(fromEmpty: Boolean) {
    val shell = rememberShellState()
    val model = if (fromEmpty) LibraryModel(loaded = true) else idleModel()
    LaunchedEffect(shell) {
        waitFrames(RECORD_AT_MS)
        shell.record(fromEmpty, reducedMotion = false)
    }
    FocalShell(shell, destination = { LibraryDestination(model) }, scene = { SceneLayer(shell, SceneUi(hint = true)) })
}

/** Finish in the scene (T7): the scene settles into the dock's pill while the note's page arrives under it. */
@Composable
private fun SettleFrame() {
    val shell = rememberShellState()
    val scene = live()
    LaunchedEffect(shell) {
        shell.live.expansion.snapTo(1f)
        shell.live.prepareLanding(saturday.id)
        shell.live.land(reducedMotion = false) { shell.open(Destination.Note(saturday.id), PageMotion.Cut) }
    }
    FocalShell(
        shell,
        destination = { destination ->
            when (destination) {
                is Destination.Note -> TranscribingNote(destination)
                else -> LibraryDestination(libraryOf(scene))
            }
        },
        scene = { SceneLayer(shell, scene) },
    )
}

