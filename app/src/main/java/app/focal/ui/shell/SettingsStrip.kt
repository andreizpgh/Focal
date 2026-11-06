package app.focal.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FullMotion
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.smoothstep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings is the library's second page (spec 4.23): one value s, 0 = the library, 1 = the settings page beside it.
 * Every input moves the same value (the finger, springs, Back, predictive back, Ctrl+, the accessibility action), and
 * everything on screen is a function of it read in the layer and draw phases, so a swipe never recomposes.
 *
 * The swipe gesture, the peek and the accessibility action belong to the library screen (it knows its own touch
 * regions); this state, the strip's layout, Back and the keyboard shortcut are here.
 */
@Stable
class SettingsStripState internal constructor(open: Boolean) {
    /** s. Read it in layout or draw lambdas only. */
    val progress = Animatable(if (open) 1f else 0f).apply { updateBounds(0f, 1f) }

    /** True once settings has been on screen in this session: it stays composed, so the next swipe starts warm. */
    var composedSettings by mutableStateOf(open)
        internal set

    val isOpen: Boolean get() = progress.targetValue >= 1f

    /**
     * Whether the library lets settings open now (spec 4.23.3: not while search, a menu, a rename, the sheet or the
     * keyboard is open). The library writes it; Ctrl+, reads it.
     */
    var armed by mutableStateOf(true)

    /** Opens with `move` from wherever s is (the reduced-motion crossfade under [reducedMotion]). */
    suspend fun open(reducedMotion: Boolean = false) = settle(1f, reducedMotion)

    suspend fun close(reducedMotion: Boolean = false) = settle(0f, reducedMotion)

    /** For the swipe's release: `move` with the finger's velocity, in s per second, clamped at the bounds. */
    suspend fun settle(target: Float, reducedMotion: Boolean, velocity: Float = 0f) {
        composedSettings = composedSettings || target > 0f
        if (reducedMotion) withContext(FullMotion) { progress.animateTo(target, FocalMotion.reducedCrossfade) }
        else progress.animateTo(target, FocalMotion.move(), initialVelocity = velocity)
    }

    /** The finger's tracking: composes the settings page the first time s leaves 0, then moves s there. */
    suspend fun track(s: Float) {
        if (s > 0f) composedSettings = true
        progress.snapTo(s)
    }

    /** The scene opened over the library from outside (notification, shortcut): s snaps to 0 under it. */
    suspend fun snapClosed() = progress.snapTo(0f)

    companion object {
        /** Saves only whether it is open; on restore s snaps to 0 or 1 without animation. */
        val Saver: Saver<SettingsStripState, Boolean> = Saver(save = { it.isOpen }, restore = { SettingsStripState(it) })
        /** The strip's seam between the two pages. */
        val seam = 8.dp
    }
}

@Composable
fun rememberSettingsStripState(): SettingsStripState = rememberSaveable(saver = SettingsStripState.Saver) { SettingsStripState(false) }

/** The strip's travel: the window width plus the seam (D_s). */
fun settingsTravel(widthPx: Float, density: Density): Float = widthPx + with(density) { SettingsStripState.seam.toPx() }

/**
 * The library and the settings page side by side (spec 4.23.1-4.23.2): the library at x = -s * D_s, settings at
 * (1 - s) * D_s, the 8 dp `s2` seam between them and 28 dp seam-side corners while 0 < s < 1. Under reduced motion
 * nothing travels: a fade-through on paper. The page that is off screen is inert (no semantics, no focus). Back and
 * predictive back close settings with the same motion: s = 1 - progress, commit on `move`, cancel back to 1. Back is
 * settings' only while it is open or opening: the peek and a close in flight leave it to the library (the app).
 */
@Composable
fun SettingsStrip(state: SettingsStripState, library: @Composable () -> Unit, settings: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = FocalTheme.colors
    // RTL mirrors the strip: settings is the page on the start side (spec 4.23.7).
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val libraryHidden by remember(state) { derivedStateOf { state.progress.value >= 1f } }
    val settingsHidden by remember(state) { derivedStateOf { state.progress.value <= 0f } }
    if (!settingsHidden) SideEffect { state.composedSettings = true }
    val corner = FocalShapes.sheetRadius
    // The handler's own coroutine is cancelled with the gesture, so the spring back runs in this scope; a back that
    // follows at once stops it before it can undo the close.
    val scope = rememberCoroutineScope()
    val springBack = remember { PendingJob() }
    // The gesture's own previews move s's target below 1; the handler stays with the gesture it started.
    var backing by remember { mutableStateOf(false) }
    PredictiveBackHandler(enabled = state.isOpen || backing) { events ->
        springBack.cancel()
        backing = true
        // From where s is (1 at rest; less while an opening swipe still settles), so a back never snaps the page in.
        val from = state.progress.value
        var committed = false
        try {
            events.collect { event -> state.progress.snapTo(from * (1f - event.progress)) }
            committed = true
            state.close(reduced)
        } catch (cancelled: CancellationException) {
            // Only a cancelled gesture springs back; a committed close may end by disabling this handler.
            if (!committed) springBack.job = scope.launch { state.settle(1f, reduced) }
            throw cancelled
        } finally {
            backing = false
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                // The 8 dp `s2` seam between the pages while the strip is in flight. It also runs under both pages'
                // seam-side corners, so their rounding reveals the seam's grey, not paper.
                val s = state.progress.value
                if (!reduced && s > 0f && s < 1f) {
                    val seam = SettingsStripState.seam.toPx()
                    val radius = corner.toPx()
                    val travel = settingsTravel(size.width, this)
                    val left = if (direction > 0) size.width - s * travel else s * travel - seam
                    drawRect(colors.s2, Offset(left - radius, 0f), Size(seam + 2 * radius, size.height))
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .inert(libraryHidden)
                .graphicsLayer {
                    val s = state.progress.value
                    if (reduced) alpha = 1 - smoothstep(0f, HALF, s) else {
                        translationX = -direction * s * settingsTravel(size.width, this)
                        clip = s > 0f && s < 1f
                        shape = if (clip) SeamSideShape(corner, seamAtEnd = true) else RectangleShape
                    }
                }
                // Each page is opaque paper, so the seam shows only between them and under their rounded corners.
                .background(colors.paper),
        ) { library() }
        if (state.composedSettings) {
            Box(
                Modifier
                    .fillMaxSize()
                    .inert(settingsHidden)
                    .graphicsLayer {
                        val s = state.progress.value
                        if (reduced) alpha = smoothstep(HALF, 1f, s) else {
                            translationX = direction * (1 - s) * settingsTravel(size.width, this)
                            clip = s > 0f && s < 1f
                            shape = if (clip) SeamSideShape(corner, seamAtEnd = false) else RectangleShape
                        }
                    }
                    .background(colors.paper),
            ) { settings() }
        }
    }
}

private const val HALF = 0.5f

/** A page in motion: the `sheet` radius on its seam side only (spec 4.23.2). Mirrored in RTL. */
private class SeamSideShape(private val radius: Dp, private val seamAtEnd: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val end = seamAtEnd == (layoutDirection == LayoutDirection.Ltr)
        val right = if (end) CornerRadius(r) else CornerRadius.Zero
        val left = if (end) CornerRadius.Zero else CornerRadius(r)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, left, right, right, left))
    }
}

/** An off-screen page is excluded from accessibility and from keyboard and D-pad focus (spec 4.23.1, I3). */
private fun Modifier.inert(inert: Boolean): Modifier =
    if (inert) clearAndSetSemantics { }.focusProperties { canFocus = false } else this
