package app.focal.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.ui.components.inert
import app.focal.ui.design.FocalFullMotion
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.smoothstep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings is the library's second page (spec 4.23): one value s, 0 = the library, 1 = the settings page beside it.
 * Every input moves the same value (the finger, springs, Back, predictive back, Ctrl+, the accessibility action), and
 * everything on screen is a function of it read in the layer and draw phases, so a swipe never recomposes.
 *
 * The swipe gesture, the hint and the accessibility action belong to the library screen (it knows its own touch
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

    /** The spring of the last settle: a touch that catches it and lets it go on resumes on the same one. */
    internal var settleSpring: AnimationSpec<Float> = FocalMotion.moveUnit()
        private set

    /**
     * Opens from wherever s is with no finger on it (the hint's Open, the keyboard, Back's close): the whole window
     * slides, so on `stage`, which `move` would cross in a blink (the reduced-motion crossfade under [reducedMotion]).
     */
    suspend fun open(reducedMotion: Boolean = false) = settle(1f, reducedMotion, spring = FocalMotion.stageUnit())

    suspend fun close(reducedMotion: Boolean = false) = settle(0f, reducedMotion, spring = FocalMotion.stageUnit())

    /** For the swipe's release: `move` with the finger's velocity, in s per second, clamped at the bounds. */
    suspend fun settle(target: Float, reducedMotion: Boolean, velocity: Float = 0f, spring: AnimationSpec<Float> = FocalMotion.moveUnit()) {
        composedSettings = composedSettings || target > 0f
        settleSpring = spring
        if (reducedMotion) withContext(FocalFullMotion) { progress.animateTo(target, FocalMotion.reducedCrossfade) }
        else progress.animateTo(target, spring, initialVelocity = velocity)
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
 * settings' only while it is open or opening: a close in flight leaves it to the library (the app).
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
    // Settings is composed off screen once the library has had a moment to settle, not in the first frame of the
    // first swipe: that frame would otherwise build the page, its view model and its controls while the finger moves.
    LaunchedEffect(state) {
        if (state.composedSettings) return@LaunchedEffect
        delay(SETTINGS_WARM_MS)
        state.composedSettings = true
    }
    val corner = FocalShapes.sheetRadius
    // One shape per side for the whole swipe, not one per frame.
    val librarySeam = remember(corner) { SeamSideShape(corner, seamAtEnd = true) }
    val settingsSeam = remember(corner) { SeamSideShape(corner, seamAtEnd = false) }
    // The handler's own coroutine is cancelled with the gesture, so the spring back runs in this scope; a back that
    // follows at once stops it before it can undo the close.
    val scope = rememberCoroutineScope()
    val springBack = remember { PendingJob() }
    // The gesture's own previews move s's target below 1; the handler stays with the gesture it started.
    var backing by remember { mutableStateOf(false) }
    // The licences pushed from settings cover the strip, which stays open beneath them (4.23.4). The library's pane
    // stays composed under them and its handlers come after the display's, so Back would close the hidden strip and
    // leave the licences on screen: Back is theirs.
    val covered = LocalShell.current.libraryCovered
    PredictiveBackHandler(enabled = (state.isOpen || backing) && !covered) { events ->
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
                    if (reduced) {
                        // Reduced motion crossfades in place: the page faded out stands aside, so it takes no tap.
                        alpha = 1 - smoothstep(0f, HALF, s)
                        translationX = if (alpha <= 0f) -direction * ASIDE * size.width else 0f
                    } else {
                        translationX = -direction * s * settingsTravel(size.width, this)
                        clip = s > 0f && s < 1f
                        shape = if (clip) librarySeam else RectangleShape
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
                        if (reduced) {
                            // Composed ahead of the first swipe, it lies over the library: unseen, it is aside.
                            alpha = smoothstep(HALF, 1f, s)
                            translationX = if (alpha <= 0f) direction * ASIDE * size.width else 0f
                        } else {
                            translationX = direction * (1 - s) * settingsTravel(size.width, this)
                            clip = s > 0f && s < 1f
                            shape = if (clip) settingsSeam else RectangleShape
                        }
                    }
                    .background(colors.paper),
            ) { settings() }
        }
    }
}

private const val HALF = 0.5f
/** A page faded out under reduced motion is placed this many widths aside. */
private const val ASIDE = 2f
/** How long after the library first shows the settings page is composed beside it, off screen. */
private const val SETTINGS_WARM_MS = 1_500L

/** A page in motion: the `sheet` radius on its seam side only (spec 4.23.2). Mirrored in RTL. */
internal class SeamSideShape(private val radius: Dp, private val seamAtEnd: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val end = seamAtEnd == (layoutDirection == LayoutDirection.Ltr)
        val right = if (end) CornerRadius(r) else CornerRadius.Zero
        val left = if (end) CornerRadius.Zero else CornerRadius(r)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, left, right, right, left))
    }
}
