package app.focal.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focal.data.Preferences
import app.focal.ui.design.FocalFullMotion
import app.focal.ui.design.FocalGestures
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.WindowLayout
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.SettingsStrip
import app.focal.ui.shell.SettingsStripState
import app.focal.ui.shell.settingsTravel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** The settings swipe's decisions (spec 2.8.7, 4.23.3), as pure functions. */
object SettingsGesture {
    /** A touch-down at least this far from either side may start the swipe (or the system's inset, if wider). */
    val edgeZone = FocalGrid.x32
    /** The peek's reach: the seam and the settings page's blank 24 dp margin (spec 4.23.5). */
    val peekReach = FocalGrid.x32

    /**
     * Where a released swipe settles. [s0] is s when the direction locked, [s] now, [velocity] the finger's speed in
     * dp/s toward opening. A fling completes in its direction; otherwise a net travel of 35 % of the strip commits in
     * its direction; otherwise the strip returns to the end nearer to [s0] (from rest: where it started).
     */
    fun release(s0: Float, s: Float, velocity: Float): Float = when {
        velocity <= -FocalGestures.FLING_DP_PER_S -> 0f
        velocity >= FocalGestures.FLING_DP_PER_S -> 1f
        s - s0 >= FocalGestures.SETTINGS_THRESHOLD -> 1f
        s0 - s >= FocalGestures.SETTINGS_THRESHOLD -> 0f
        else -> if (s0 >= HALF) 1f else 0f
    }

    /**
     * Whether the first move past the slop claims the stream (spec 4.23.3): horizontal when abs(dx) > 1.5·abs(dy),
     * in the opening direction from the library at rest, the closing one from the settings page at rest, either
     * while a settle or the peek was caught mid-way. [opening] is the sign of dx that opens (-1 in LTR).
     */
    fun locks(dx: Float, dy: Float, s0: Float, opening: Float): Boolean {
        if (abs(dx) <= FocalGestures.DIRECTION_LOCK * abs(dy)) return false
        val towardOpen = dx * opening > 0f
        return when {
            s0 <= 0f -> towardOpen
            s0 >= 1f -> !towardOpen
            else -> true
        }
    }

    private const val HALF = 0.5f
}

/**
 * When the one-time peek may play (spec 4.23.5): never after settings was opened, never more than twice; the first
 * time the library is idle; the second time only with a note in the library and in a later visit than the first.
 */
object PeekRules {
    fun allowed(opened: Boolean, peeks: Int, hasNotes: Boolean, visit: Int, firstPeekVisit: Int?): Boolean = when {
        opened || peeks >= FocalTiming.PEEK_MAX -> false
        peeks == 0 -> true
        else -> hasNotes && (firstPeekVisit == null || visit > firstPeekVisit)
    }
}

/** Library visits in this process (each time the library becomes the visible page), for the second peek. */
object LibraryVisits {
    var count = 0
        private set
    var firstPeekVisit: Int? = null

    fun arrive() { count++ }
}

/** The settings layer's own state: who opened it (focus hand-off) and the peek in flight. */
@Stable
class SettingsLayerState {
    /** Settings was opened by the accessibility action or a key: focus goes to its heading on arrival. */
    var focusOnArrival by mutableStateOf(false)
    internal var peek: Job? = null
    internal var caught = false
    /** The finger is on the strip or it is settling: request a high frame rate (API 35+ adaptive refresh). */
    var moving by mutableStateOf(false)
        internal set
    /** The reduced-motion peek: the edge's alpha (the library does not move). */
    internal val edgePeek = Animatable(0f)
}

/**
 * The library and the settings page beside it (spec 4.23): the strip, the finger-tracked swipe that moves it, the
 * one-time peek, and the bookkeeping of settings having been opened. [library] and [settings] are the two pages;
 * [armed] says whether the library is at rest (no search, menu, rename or keyboard); [hasNotes], [capturing] and
 * [blocked] (a sheet or the feedback pill) feed the peek's idle rule. [layer] carries who opened settings.
 */
@Composable
fun LibraryHost(
    strip: SettingsStripState,
    preferences: Preferences?,
    armed: () -> Boolean,
    hasNotes: Boolean,
    capturing: Boolean,
    blocked: Boolean,
    layer: SettingsLayerState,
    library: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    val shell = LocalShell.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gestures = WindowInsets.systemGestures
    val status = WindowInsets.statusBars
    val sides = WindowInsets.safeDrawing
    // A short window's dock is a 96 dp rail at the end edge (spec 5.14): it is excluded instead of the bottom row.
    val rail = LocalWindowLayout.current.rail
    var touched by remember { mutableStateOf(false) }
    val currentArmed by rememberUpdatedState(armed)
    val expansion = shell.live.expansion

    DisposableEffect(Unit) {
        LibraryVisits.arrive()
        onDispose { }
    }
    // Settings counts as opened the first time it is fully on screen; a pending Delete or Remove audio commits then.
    LaunchedEffect(strip) {
        snapshotFlow { strip.progress.value >= 1f }.collect { open ->
            if (open) {
                preferences?.settingsOpened = true
                shell.bottom.feedback.dismiss()
            }
        }
    }
    SettingsPeek(strip, preferences, layer, touched, hasNotes, capturing, blocked, armed)

    Box(
        Modifier
            .fillMaxSize()
            .preferredFrameRate(if (layer.moving) FrameRateCategory.High else FrameRateCategory.Normal)
            .pointerInput(strip, rtl, reduced, rail) {
                val slop = viewConfiguration.touchSlop
                val opening = if (rtl) 1f else -1f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched = true
                    // Catch: a touch stops a settle or the peek at once, the peek's hold included (I2).
                    val wasPeeking = layer.peek?.isActive == true
                    val previousTarget = if (wasPeeking) 0f else strip.progress.targetValue
                    layer.caught = wasPeeking
                    layer.peek?.cancel()
                    val start = strip.progress.value
                    val settling = strip.progress.isRunning || wasPeeking
                    if (settling) scope.launch(start = CoroutineStart.UNDISPATCHED) { strip.progress.stop() }
                    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                    val edge = edgeZone(gestures.getLeft(this, direction), gestures.getRight(this, direction))
                    val x = down.position.x
                    val onRail = rail && run {
                        val width = WindowLayout.railWidth.toPx()
                        if (rtl) x <= sides.getLeft(this, LayoutDirection.Ltr) + width
                        else x >= size.width - sides.getRight(this, LayoutDirection.Ltr) - width
                    }
                    val inZone = x >= edge.first && x <= size.width - edge.second && down.position.y >= status.getTop(this) && !onRail
                    val allowed = when {
                        expansion.value > 0f -> false
                        start <= 0f -> currentArmed() && down.position.y <= shell.bottom.touchLimit(this)
                        start >= 1f -> true
                        else -> true
                    }
                    if (!inZone || !allowed) {
                        if (settling) resume(scope, strip, previousTarget, reduced)
                        return@awaitEachGesture
                    }
                    val travel = settingsTravel(size.width.toFloat(), this)
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var lockX = Float.NaN
                    var lockS = start
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            if (lockX.isNaN()) {
                                // A tap: the settle (or the peek's return) goes on to where it was heading.
                                if (settling) resume(scope, strip, previousTarget, reduced)
                            } else {
                                change.consume()
                                val velocity = tracker.calculateVelocity().x
                                val towardOpen = velocity * opening / density
                                val target = SettingsGesture.release(lockS, strip.progress.value, towardOpen)
                                scope.launch {
                                    try {
                                        strip.settle(target, reduced, velocity = if (reduced) 0f else velocity * opening / travel)
                                    } finally {
                                        layer.moving = false
                                    }
                                }
                            }
                            break
                        }
                        if (lockX.isNaN()) {
                            val delta = change.position - down.position
                            if (delta.getDistance() <= slop) continue
                            if (!SettingsGesture.locks(delta.x, delta.y, start, opening)) {
                                // The list, the expand drag and long-press keep this stream.
                                if (settling) resume(scope, strip, previousTarget, reduced)
                                return@awaitEachGesture
                            }
                            lockX = change.position.x
                            lockS = strip.progress.value
                            layer.moving = true
                        }
                        // Claimed: nothing under the strip sees this stream any more.
                        event.changes.forEach { it.consume() }
                        val s = (lockS + opening * (change.position.x - lockX) / travel).coerceIn(0f, 1f)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { strip.track(s) }
                    }
                }
            },
    ) {
        SettingsStrip(strip, library = library, settings = settings)
        if (reduced) EdgePeek(layer.edgePeek)
    }
}

/** The two edge zones (left, right) in pixels: max(32 dp, the system gesture inset on that side). */
private fun androidx.compose.ui.input.pointer.PointerInputScope.edgeZone(left: Int, right: Int): Pair<Float, Float> {
    val zone = SettingsGesture.edgeZone.toPx()
    return max(zone, left.toFloat()) to max(zone, right.toFloat())
}

/** Where a touch may start the swipe: above the topmost bottom-column element, less 8 dp (spec 4.23.3). */
private fun app.focal.ui.components.BottomColumnState.touchLimit(density: androidx.compose.ui.unit.Density): Float {
    val top = topTarget
    return if (top.isNaN()) Float.POSITIVE_INFINITY else top - with(density) { FocalGrid.x8.toPx() }
}

private fun resume(scope: kotlinx.coroutines.CoroutineScope, strip: SettingsStripState, target: Float, reduced: Boolean) {
    scope.launch { strip.settle(target, reduced) }
}

/**
 * The one-time peek (spec 4.23.5): after the library has been idle for 1200 ms, s goes to 32/420 on `move`, holds
 * 400 ms and springs back on `move`. A touch catches it (its job is cancelled at once, the hold included); anything
 * else that ends the calm springs s back. Under reduced motion the library does not move: the seam and the page's
 * leading 32 dp fade in at the edge, hold and fade out.
 */
@Composable
private fun SettingsPeek(
    strip: SettingsStripState,
    preferences: Preferences?,
    layer: SettingsLayerState,
    touched: Boolean,
    hasNotes: Boolean,
    capturing: Boolean,
    blocked: Boolean,
    armed: () -> Boolean,
) {
    if (preferences == null) return
    val shell = LocalShell.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val focused = LocalWindowInfo.current.isWindowFocused
    val atRest by remember(strip) {
        derivedStateOf { strip.progress.value <= 0f && !strip.progress.isRunning && shell.live.expansion.value <= 0f }
    }
    val idle = resumed.isAtLeast(Lifecycle.State.RESUMED) && focused && atRest && !touched && !capturing && !blocked &&
        armed() && shell.bottom.feedback.current == null
    LaunchedEffect(idle) {
        if (!idle) return@LaunchedEffect
        delay(FocalTiming.PEEK_IDLE_MS)
        val visit = LibraryVisits.count
        if (!PeekRules.allowed(preferences.settingsOpened, preferences.settingsPeeks, hasNotes, visit, LibraryVisits.firstPeekVisit)) return@LaunchedEffect
        preferences.settingsPeeks += 1
        if (LibraryVisits.firstPeekVisit == null) LibraryVisits.firstPeekVisit = visit
        layer.caught = false
        layer.peek = coroutineContext[Job]
        try {
            if (reduced) {
                withContext(FocalFullMotion) {
                    layer.edgePeek.animateTo(1f, FocalMotion.reducedCrossfade)
                    delay(FocalTiming.PEEK_HOLD_MS)
                    layer.edgePeek.animateTo(0f, FocalMotion.reducedCrossfade)
                }
            } else {
                val travel = settingsTravel(window.containerSize.width.toFloat(), density)
                strip.settle(with(density) { SettingsGesture.peekReach.toPx() } / travel, reducedMotion = false)
                delay(FocalTiming.PEEK_HOLD_MS)
                strip.close()
            }
        } finally {
            layer.peek = null
            // Interrupted by anything but a touch: s springs back to 0 (the peek still counts as played).
            if (!layer.caught) withContext(NonCancellable) {
                if (reduced) layer.edgePeek.snapTo(0f) else if (strip.progress.value > 0f) strip.close()
            }
        }
    }
}

/**
 * The reduced-motion peek: the seam and the settings page's leading 32 dp at the window's end edge, faded in place
 * (the library does not move).
 */
@Composable
private fun EdgePeek(alpha: Animatable<Float, *>) {
    val colors = FocalTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier.fillMaxSize().drawWithContent {
            val a = alpha.value
            if (a <= 0f) return@drawWithContent
            val reach = SettingsGesture.peekReach.toPx()
            val seam = SettingsStripState.seam.toPx()
            val radius = FocalShapes.sheetRadius.toPx()
            val left = if (rtl) 0f else size.width - reach
            drawRect(colors.s2, Offset(left, 0f), Size(reach, size.height), alpha = a)
            val pageLeft = if (rtl) -radius else left + seam
            drawRoundRect(colors.paper, Offset(pageLeft, 0f), Size(reach - seam + radius, size.height), CornerRadius(radius), alpha = a)
        },
    )
}
