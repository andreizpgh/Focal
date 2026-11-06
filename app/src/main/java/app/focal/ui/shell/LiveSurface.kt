package app.focal.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import app.focal.ui.design.FocalGestures
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FullMotion
import app.focal.ui.design.LocalReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * The recording surface (spec 5, 6.4): not a destination but a root-level overlay driven by one value, the
 * expansion p (0 = the stage in the library, 1 = the full scene). Taps, the drag, Back and predictive back all move
 * the same value; everything drawn is a function of it, read in layout and draw lambdas.
 *
 * The morph itself (clip, material, travelling wave and timer, the dress wipe) is the scene's; this holds p, its
 * springs and the back handling.
 */
@Stable
class LiveSurfaceState {
    /** p. Read it in layout or draw lambdas only. */
    val expansion = Animatable(0f).apply { updateBounds(0f, 1f) }

    /** The stage's bounds in the window (the morph's origin), reported by the stage while it is laid out. */
    var stageBounds by mutableStateOf<Rect?>(null)

    /**
     * The window y of the material's bottom edge while the scene is on screen (the morph's clip), which wipes the
     * dock's dress (spec 2.8.6). Set by the scene; null while there is no material behind the dock's row.
     */
    var materialEdge by mutableStateOf<(() -> Float)?>(null)

    /** Composed while p > 0; removed in the frame it returns to 0. */
    val visible: Boolean by derivedStateOf { expansion.value > 0f }

    /** Record, a tap or Enter on the live stage: `stage` from rest (the crossfade under reduced motion). */
    suspend fun expand(reducedMotion: Boolean = false) = settle(1f, reducedMotion)

    /** The chevron, Back, Esc: the same frames backwards. */
    suspend fun collapse(reducedMotion: Boolean = false) = settle(0f, reducedMotion)

    /** A drag's release: `stage` with the finger's velocity (in p per second), clamped at the bounds: no bounce. */
    suspend fun settle(target: Float, reducedMotion: Boolean, velocity: Float = 0f) {
        if (reducedMotion) withContext(FullMotion) { expansion.animateTo(target, FocalMotion.reducedCrossfade) }
        else expansion.animateTo(target, FocalMotion.stage(), initialVelocity = velocity)
    }
}

/** Predictive back's progress curve on the scene and on pages (spec 2.8.5): PathInterpolator(0, 0, 0, 1). */
val BackEase = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * Hosts the overlay: [scene] is composed only while p > 0, above the destinations and below the dock. Back on the
 * scene drives the morph itself: p = 1 - 0.4 * BackEase(progress), so a full back swipe previews exactly the release
 * threshold; commit folds on `stage`, cancel springs back on `move`. API 33 and below (no progress) fold directly.
 */
@Composable
fun LiveSurfaceHost(state: LiveSurfaceState, scene: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    PredictiveBackHandler(enabled = state.visible) { events ->
        try {
            events.collect { event -> state.expansion.snapTo(1f - FocalGestures.BACK_PREVIEW * BackEase.transform(event.progress)) }
            state.collapse(reduced)
        } catch (cancelled: CancellationException) {
            if (reduced) state.settle(1f, true) else state.expansion.animateTo(1f, FocalMotion.move())
            throw cancelled
        }
    }
    if (state.visible) scene()
}

/** Keeps a [LiveSurfaceState] for the activity. */
@Composable
fun rememberLiveSurfaceState(): LiveSurfaceState = remember { LiveSurfaceState() }
