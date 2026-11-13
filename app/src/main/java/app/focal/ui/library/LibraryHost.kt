package app.focal.ui.library

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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focal.R
import app.focal.data.Preferences
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FeedbackResult
import app.focal.ui.design.FocalGestures
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.WindowLayout
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.SettingsStrip
import app.focal.ui.shell.SettingsStripState
import app.focal.ui.shell.settingsTravel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** The settings swipe's decisions (spec 2.8.7, 4.23.3), as pure functions. */
object SettingsGesture {
    /** A touch-down at least this far from either side may start the swipe (or the system's inset, if wider). */
    val edgeZone = FocalGrid.x32

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
     * while a settle was caught mid-way. [opening] is the sign of dx that opens (-1 in LTR).
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
 * When the hint that settings are a swipe away may show (spec 4.23.5): never after settings was opened, never more
 * than twice, and only once the library holds a note (an empty library has its own invitation, to record); the second
 * time only in a later visit than the first.
 */
object HintRules {
    fun allowed(opened: Boolean, shown: Int, hasNotes: Boolean, visit: Int, firstHintVisit: Int?): Boolean = when {
        opened || shown >= FocalTiming.SETTINGS_HINT_MAX || !hasNotes -> false
        shown == 0 -> true
        else -> firstHintVisit == null || visit > firstHintVisit
    }
}

/** Library visits in this process (each time the library becomes the visible page), for the second hint. */
object LibraryVisits {
    var count = 0
        private set
    var firstHintVisit: Int? = null

    fun arrive() { count++ }
}

/** The settings layer's own state: who opened it (focus hand-off). */
@Stable
class SettingsLayerState {
    /** Settings was opened by the accessibility action or a key: focus goes to its heading on arrival. */
    var focusOnArrival by mutableStateOf(false)
    /** The finger is on the strip or it is settling: request a high frame rate (API 35+ adaptive refresh). */
    var moving by mutableStateOf(false)
        internal set
    /** The settings hint is in the feedback pill: a touch on the library puts it away (the person has moved on). */
    internal var hinting by mutableStateOf(false)
}

/**
 * The library and the settings page beside it (spec 4.23): the strip, the finger-tracked swipe that moves it, the
 * one-time hint that says so, and the bookkeeping of settings having been opened. [library] and [settings] are the
 * two pages; [armed] says whether the library is at rest (no search, selection, rename or keyboard); [hasNotes],
 * [capturing] and [blocked] (a sheet) feed the hint's idle rule. [layer] carries who opened settings.
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
    SettingsHint(strip, preferences, layer, touched, hasNotes, capturing, blocked, armed)

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
                    if (layer.hinting) shell.bottom.feedback.dismiss()
                    // Catch: a touch stops a settle at once (I2).
                    val previousTarget = strip.progress.targetValue
                    val start = strip.progress.value
                    val settling = strip.progress.isRunning
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
                                // A tap: the settle goes on to where it was heading.
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
 * The hint that settings are a swipe away (spec 4.23.5). Nothing on the library says that a page lies beside it, so
 * it is said once, in words, where every other message is said: after the library has been at rest for a moment the
 * feedback pill reads "Swipe left for settings" with "Open", which opens them with the swipe's own motion (the page
 * slides in from the side it was said to be on). Nothing moves by itself: a page that shifts with no finger on it reads
 * as a glitch. It shows at most twice ([HintRules]) and never after settings was opened; anything that ends the calm
 * before it shows (a touch, a recording, a sheet) puts it off to the next calm.
 */
@Composable
private fun SettingsHint(
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
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val focused = LocalWindowInfo.current.isWindowFocused
    // Settings sit past the page's end: a swipe to the left, or to the right where the layout is mirrored.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val message = stringResource(if (rtl) R.string.hint_settings_rtl else R.string.hint_settings)
    val open = stringResource(R.string.action_open)
    val atRest by remember(strip) {
        derivedStateOf { strip.progress.value <= 0f && !strip.progress.isRunning && shell.live.expansion.value <= 0f }
    }
    val isArmed = armed()
    // The hint gives way to what the person does next: a recording, a sheet, search, a selection.
    if (layer.hinting && (capturing || blocked || !isArmed)) LaunchedEffect(Unit) { shell.bottom.feedback.dismiss() }
    val idle = resumed.isAtLeast(Lifecycle.State.RESUMED) && focused && atRest && !touched && !capturing && !blocked && hasNotes &&
        isArmed && shell.bottom.feedback.current == null
    LaunchedEffect(idle) {
        if (!idle) return@LaunchedEffect
        delay(FocalTiming.SETTINGS_HINT_IDLE_MS)
        val visit = LibraryVisits.count
        if (!HintRules.allowed(preferences.settingsOpened, preferences.settingsPeeks, hasNotes, visit, LibraryVisits.firstHintVisit)) return@LaunchedEffect
        preferences.settingsPeeks += 1
        if (LibraryVisits.firstHintVisit == null) LibraryVisits.firstHintVisit = visit
        // In the page's own scope: showing the pill ends the calm this effect is keyed on, which must not take the
        // pill down with it.
        scope.launch {
            layer.hinting = true
            val result = try { shell.bottom.feedback.show(message, FeedbackAction(open)) } finally { layer.hinting = false }
            if (result == FeedbackResult.ActionPerformed) strip.open(reduced)
        }
    }
}
