package app.focal.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.zIndex
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FullMotion
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.scene.Morph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the recording surface was opened: what plays on top of the morph (spec 6.1). */
enum class SceneEntry {
    /** A tap, Enter or the drag on the live stage (T6′, T28): the wave keeps its live level. */
    Stage,
    /** Record over a note stage (T1′): the note leaves with the clip, the wave lights up, "0:00" arrives. */
    Record,
    /** Record in the empty library (T1′e): the material grows out of the resting instrument. */
    RecordEmpty,
    /** The shortcut or the recording notification (5.13): already open, the bars light up, the chrome fades in. */
    Direct,
}

/**
 * The recording surface (spec 5, 6.4): not a destination but a root-level overlay driven by one value, the
 * expansion p (0 = the stage in the library, 1 = the full scene). Taps, the drag, Back and predictive back all move
 * the same value; everything drawn is a function of it, read in layout and draw lambdas. Finish settles the scene
 * into the dock's pill instead ([landing], T7) while the note's page arrives under it.
 *
 * The library reports what the morph grows out of: the stage's window bounds and its note content, or the empty
 * library's resting instrument.
 */
@Stable
class LiveSurfaceState internal constructor(open: Boolean) {
    /** p. Read it in layout or draw lambdas only. */
    val expansion = Animatable(if (open) 1f else 0f).apply { updateBounds(0f, 1f) }

    /**
     * T1′e: the material's alpha a as it grows out of the empty library's resting instrument (`fadeFast` from Record),
     * so its box never pops dark; the resting bars change dress with it and the invitation leaves at (1 - a)^2.
     */
    val wake = Animatable(1f)

    /** T7: q, the settle into the dock pill (0 = the full scene, 1 = the pill's bounds). */
    val landing = Animatable(0f)
    /** T7: the scene's content (fadeFast from Finish) and the whole scene (fade, from half the travel). */
    val landingContent = Animatable(1f)
    val landingAlpha = Animatable(1f)

    /** The note Finish is settling into, while T7 runs. The page under the scene develops for it. */
    var landingNote by mutableStateOf<String?>(null)
        private set

    /** The stage's bounds in the window (the morph's origin), reported by the stage while it is laid out. */
    var stageBounds by mutableStateOf<Rect?>(null)

    /** The empty library's resting instrument box in the window (T1′e's origin), while it is laid out. */
    var restBounds by mutableStateOf<Rect?>(null)

    /** The note stage's content as drawn (T1′: it leaves with the clip's top-left). Not state: drawn, not read. */
    var stageNote: GraphicsLayer? = null

    /** Where focus returns after a fold (spec 8.6): the stage. */
    var stageFocus: FocusRequester? = null

    /** The library brings its stage into view (the list to its top), animated or at once. */
    var revealStage: (suspend (animated: Boolean) -> Unit)? = null

    /**
     * The window y of the material's bottom edge while the scene is on screen (the morph's clip), which wipes the
     * dock's dress (spec 2.8.6). Set by the scene; null while there is no material behind the dock's row.
     */
    var materialEdge by mutableStateOf<(() -> Float)?>(null)

    /** Whether the surface morphs in space (true) or crossfades in place (reduced motion, or no origin on screen). */
    var spatial by mutableStateOf(true)

    /** A finger is on the surface: the morph requests a high frame rate (spec 6.5). */
    var dragging by mutableStateOf(false)

    /**
     * Whether the scene has covered the library since Record (p reached 0.95): until then the library keeps its
     * layout from before Record, and switches to its live layout only while covered (spec 2.8.2 `liveLayoutSwitch`).
     * A fold that starts before that (Back within the first frames of T1′, a fling back) counts as covered too, so a
     * capture that goes on is never left behind the library's idle layout.
     */
    var covered by mutableStateOf(true)
        internal set

    /** How the surface was last opened, and a count that keys each opening. */
    var entry by mutableStateOf(SceneEntry.Stage)
        private set
    var entries by mutableIntStateOf(0)
        private set

    /** What plays on top of the morph for this opening (T1′, T1′e, the shortcut), started with p. */
    var choreography by mutableStateOf(SceneChoreography(SceneEntry.Stage, reduced = false))
        private set

    /** Composed while p > 0; removed in the frame it returns to 0. */
    val visible: Boolean by derivedStateOf { expansion.value > 0f }

    /** Fully open and not settling: the scene is the screen (the library behind is inert). */
    val open: Boolean by derivedStateOf { expansion.value >= 1f && landingNote == null }

    private fun begin(kind: SceneEntry, reducedMotion: Boolean = false) {
        entry = kind
        entries++
        covered = kind != SceneEntry.Record && kind != SceneEntry.RecordEmpty
        choreography = SceneChoreography(kind, reducedMotion)
    }

    /** How much the pages behind the morph are scaled at p (spec 6.4: 1 - 0.06 p about the window's centre). */
    fun pagesScale(p: Float = expansion.value): Float = if (spatial && landingNote == null && p > 0f) 1 - PAGES_SCALE * p else 1f

    /**
     * Reports a box laid out in the pages (the stage, the resting instrument) by its [laidOut] window rect, undoing
     * the pages' scale behind the morph about the window's centre [centre]: the morph grows out of the box itself.
     */
    fun unscaled(laidOut: Rect, centre: androidx.compose.ui.geometry.Offset): Rect {
        val k = pagesScale()
        if (k == 1f) return laidOut
        fun x(v: Float) = centre.x + (v - centre.x) / k
        fun y(v: Float) = centre.y + (v - centre.y) / k
        return Rect(x(laidOut.left), y(laidOut.top), x(laidOut.right), y(laidOut.bottom))
    }

    /** The scene reports that it covers the library (p >= 0.95). */
    fun cover() {
        covered = true
    }

    /** A tap or Enter on the live stage (T6′): `stage` from rest (the crossfade under reduced motion). */
    suspend fun expand(reducedMotion: Boolean = false) {
        if (expansion.value <= 0f) begin(SceneEntry.Stage)
        settle(1f, reducedMotion)
    }

    /** Record (T1′, T1′e): the stage comes into view while the scene grows out of it. */
    suspend fun record(fromEmpty: Boolean, reducedMotion: Boolean = false) = coroutineScope {
        begin(if (fromEmpty) SceneEntry.RecordEmpty else SceneEntry.Record, reducedMotion)
        val motion = choreography
        launch(start = CoroutineStart.UNDISPATCHED) { motion.play(wake) }
        revealStage?.let { reveal -> launch { reveal(true) } }
        settle(1f, reducedMotion)
    }

    /** The shortcut or the notification: the scene is already open (no morph); the bars light up, the chrome fades in. */
    suspend fun show(reducedMotion: Boolean = false) {
        if (expansion.value >= 1f && landingNote == null) return
        begin(SceneEntry.Direct, reducedMotion)
        expansion.snapTo(1f)
        choreography.play(wake)
    }

    /** The drag claimed the surface from rest (T28). */
    fun beginDrag() {
        if (expansion.value <= 0f) begin(SceneEntry.Stage)
    }

    /** The chevron, Back, Esc: the same frames backwards (T5′); a scrolled list snaps to its top first. */
    suspend fun collapse(reducedMotion: Boolean = false) = coroutineScope {
        revealStage?.let { reveal -> launch { reveal(false) } }
        settle(0f, reducedMotion)
    }

    /**
     * A release or a tap: `stage` with the finger's velocity (in p per second), clamped at the bounds: no bounce. It
     * counts from the release or the tap (FocalMotion.fromTrigger), so the first frame after it has already moved.
     */
    suspend fun settle(target: Float, reducedMotion: Boolean, velocity: Float = 0f) {
        if (target <= 0f) cover()
        if (reducedMotion || !spatial) withContext(FullMotion) { expansion.animateTo(target, FocalMotion.fromTrigger(FocalMotion.reducedCrossfade)) }
        else expansion.animateTo(target, FocalMotion.fromTrigger(FocalMotion.stage()), initialVelocity = velocity)
    }

    /**
     * Finish was tapped in the scene: from this frame the surface settles into [noteId]'s page (T7) rather than
     * folding, whatever the recording's state does next. [land] then plays it.
     */
    fun prepareLanding(noteId: String) {
        landingNote = noteId
    }

    /**
     * T7, after [prepareLanding]: [openNote] puts the note's page under the scene, which contracts into the dock
     * pill's bounds on `stage` while its content leaves on `fadeFast` and, from half the travel, the whole scene fades
     * on `fade`. Under reduced motion the scene crossfades away in 150 ms. Then the surface is gone.
     */
    suspend fun land(reducedMotion: Boolean, openNote: () -> Unit) {
        try {
            landing.snapTo(0f)
            landingContent.snapTo(1f)
            landingAlpha.snapTo(1f)
            openNote()
            // Counted from Finish (FocalMotion.fromTrigger): the first frame after the tap has already moved.
            if (reducedMotion || !spatial) {
                withContext(FullMotion) { landingAlpha.animateTo(0f, FocalMotion.fromTrigger(FocalMotion.reducedCrossfade)) }
            } else coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) { landingContent.animateTo(0f, FocalMotion.fromTrigger(FocalMotion.fadeFast())) }
                launch {
                    snapshotFlow { landing.value >= HALF }.first { it }
                    landingAlpha.animateTo(0f, FocalMotion.fade())
                }
                landing.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.stage()))
            }
        } finally {
            withContext(NonCancellable) {
                expansion.snapTo(0f)
                landing.snapTo(0f)
                landingContent.snapTo(1f)
                landingAlpha.snapTo(1f)
            }
            landingNote = null
        }
    }

    companion object {
        private const val HALF = 0.5f
        /** The pages behind the morph (spec 2.8.2 `libraryScale`). */
        const val PAGES_SCALE = 0.06f

        /** Keeps an open scene across recreation, if a capture is still live then ([capturing]). */
        fun saver(capturing: () -> Boolean): Saver<LiveSurfaceState, Boolean> =
            Saver(save = { it.open }, restore = { LiveSurfaceState(it && capturing()) })
    }
}

/** Predictive back's progress curve on the scene and on pages (spec 2.8.5): PathInterpolator(0, 0, 0, 1). */
val BackEase = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * Hosts the overlay: [scene] is composed only while p > 0, above the destinations and below the dock (above the dock
 * while it settles into it, T7). Back on the scene drives the morph itself: p = 1 - 0.4 * BackEase(progress), so a
 * full back swipe previews exactly the release threshold; commit folds on `stage`, cancel springs back on `move`. API
 * 33 and below (no progress) fold directly. The handler is registered when the scene appears, so it is the first
 * back layer while the scene is open or opening (spec 2.8.5); while it folds, Back belongs to the layer under it.
 */
@Composable
fun LiveSurfaceHost(state: LiveSurfaceState, scene: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    // The handler's own coroutine is cancelled with the gesture, so the spring back runs in this scope; a back that
    // follows at once (the dispatcher may cancel, then complete) stops it before it can undo the fold.
    val scope = rememberCoroutineScope()
    val springBack = remember { PendingJob() }
    // The gesture's own previews move p's target below 1; the handler stays with the gesture it started.
    var backing by remember { mutableStateOf(false) }
    if (!state.visible) return
    PredictiveBackHandler(enabled = state.landingNote == null && (state.expansion.targetValue >= 1f || backing)) { events ->
        springBack.cancel()
        backing = true
        // From where p is: an expand still settling previews from there, never from a surface snapped open.
        val from = state.expansion.value
        var committed = false
        try {
            events.collect { event -> state.expansion.snapTo(Morph.backPreview(event.progress, from, BackEase::transform)) }
            committed = true
            state.collapse(reduced)
        } catch (cancelled: CancellationException) {
            // Only a cancelled gesture springs back; a committed fold ends by removing this handler.
            if (!committed) springBack.job = scope.launch {
                if (reduced || !state.spatial) state.settle(1f, true) else state.expansion.animateTo(1f, FocalMotion.move())
            }
            throw cancelled
        } finally {
            backing = false
        }
    }
    Box(Modifier.fillMaxSize().zIndex(if (state.landingNote != null) LANDING_Z else 0f)) { scene() }
}

/** While the scene settles into the dock's pill (T7) it is drawn above the dock, whose new page shows under it. */
private const val LANDING_Z = 1f

/** Keeps a [LiveSurfaceState] for the activity; an open scene survives recreation while [capturing]. */
@Composable
fun rememberLiveSurfaceState(capturing: () -> Boolean = { false }): LiveSurfaceState =
    rememberSaveable(saver = LiveSurfaceState.saver(capturing)) { LiveSurfaceState(false) }
