package app.focal.ui.scene

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import app.focal.ui.shell.LiveSurfaceState
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The drags that move p (spec 6.5, T28). Every drag tracks p = clamp(p0 + dy / D, 0, 1), D being the travel from the
 * stage's bottom to the window's bottom, so the card's bottom edge follows the finger 1:1; a release settles on
 * `stage` with the finger's velocity (or crossfades under reduced motion), by [Morph.release].
 */
@Stable
abstract class SurfaceDrag(protected val live: LiveSurfaceState, protected val scope: CoroutineScope) {
    /** The finger's travel for p 0 to 1, in pixels. */
    var travel: () -> Float = { 1f }
    var density = 1f
    var reduced = false

    /** True while this drag moves p. */
    var tracking = false
        private set
    private var from = 0f

    protected fun start() {
        tracking = true
        from = live.expansion.value
        live.dragging = true
    }

    /** Puts p at [from] (p when the drag was claimed) + [dy] pixels of travel. */
    protected fun moveFromStart(dy: Float) {
        val next = (from + dy / travel().coerceAtLeast(1f)).coerceIn(0f, 1f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { live.expansion.snapTo(next) }
    }

    /** Moves p by [dy] pixels; returns the pixels it took. */
    protected fun moveBy(dy: Float): Float {
        val p = live.expansion.value
        val next = (p + dy / travel().coerceAtLeast(1f)).coerceIn(0f, 1f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { live.expansion.snapTo(next) }
        return (next - p) * travel()
    }

    /** Ends the drag: [velocity] in pixels per second, positive downward. */
    protected fun release(velocity: Float) {
        tracking = false
        val target = Morph.release(from, live.expansion.value, velocity / density)
        scope.launch {
            try {
                live.settle(target, reduced, velocity = if (reduced) 0f else velocity / travel().coerceAtLeast(1f))
            } finally {
                live.dragging = false
            }
        }
    }
}

/**
 * Drag the live stage down (T28), as the library list's nested-scroll parent: it takes the list's downward leftovers
 * at its top (where an overscroll would stretch) into p, and upward deltas while p > 0, before the list scrolls; a
 * fling settles. It is armed per stream by [arm]: only while a capture runs, the scene is folded, the touch-down is
 * on the stage and the list is at its very top (spec 6.5); and it keeps its arming only if, at the first move past the
 * touch slop, the finger heads down more than sideways from its touch-down ([track]). No capture, no drag: only
 * Record starts a recording.
 */
@Stable
class ExpandDrag(live: LiveSurfaceState, scope: CoroutineScope) : SurfaceDrag(live, scope), NestedScrollConnection {
    private var armed = false
    private var decided = false

    /** At every touch-down on the list: whether this stream may expand the scene. */
    fun arm(enabled: Boolean) {
        armed = enabled && !tracking
        decided = false
    }

    /** An armed stream whose direction is not decided yet: the stage feeds it the finger's moves ([track]). */
    val deciding: Boolean get() = armed && !decided

    /** The finger's travel from its touch-down, in pixels, until the stream's direction is decided. */
    fun track(dx: Float, dy: Float, slop: Float) {
        if (!deciding) return
        val claims = claims(dx, dy, slop) ?: return
        decided = true
        if (!claims) armed = false
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!tracking || available.y >= 0f || live.expansion.value <= 0f) return Offset.Zero
        return Offset(0f, moveBy(available.y))
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Only a stream decided as a downward drag: the list's vertical slop alone lets a diagonal one through.
        if (!armed || !decided || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!tracking) {
            live.beginDrag()
            start()
        }
        moveBy(available.y)
        // All of it: no overscroll stretch while the stage holds the drag.
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        armed = false
        if (!tracking) return Velocity.Zero
        release(available.y)
        return available
    }

    companion object {
        /**
         * Whether a stream that moved ([dx], [dy]) from its touch-down expands the scene: null until it has travelled
         * past [slop] (straight-line, as the settings swipe measures it); then only if it heads down and more down
         * than sideways.
         */
        fun claims(dx: Float, dy: Float, slop: Float): Boolean? {
            if (hypot(dx, dy) <= slop) return null
            return dy > 0f && abs(dy) > abs(dx)
        }
    }
}

/**
 * The swipe up that folds the scene, where it starts on the text region (spec 5.3.7): the region scrolls first; its
 * upward leftovers (the region at its end, follow on) fold the scene, and while folded part way the downward deltas
 * open it again before the region scrolls.
 */
@Stable
class FoldScroll(live: LiveSurfaceState, scope: CoroutineScope) : SurfaceDrag(live, scope), NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!tracking || available.y <= 0f || live.expansion.value >= 1f) return Offset.Zero
        return Offset(0f, moveBy(available.y))
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f || live.landingNote != null) return Offset.Zero
        if (!tracking) start()
        moveBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!tracking) return Velocity.Zero
        release(available.y)
        return available
    }
}

/**
 * The surface's own touch (spec 6.5): a touch-down anywhere on it catches a settle mid-flight; past the slop a
 * vertical drag that starts above the dock row ([foldLimit], window y) is claimed: upward from the open scene, either
 * way once caught. A tap on a caught settle lets it go on to where it was heading. Streams the text region or a
 * control takes are left to them (the region folds through [FoldScroll]).
 */
@Stable
class SurfaceTouch(live: LiveSurfaceState, scope: CoroutineScope, internal val region: FoldScroll) : SurfaceDrag(live, scope) {
    /** Whether a drag starting at this window point may fold the scene: not on the dock's row, nor on the rail. */
    var mayFold: (Offset) -> Boolean = { true }

    internal suspend fun PointerInputScope.handle() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (live.landingNote != null) return@awaitEachGesture
            val caught = live.expansion.isRunning
            val heading = live.expansion.targetValue
            if (caught) scope.launch(start = CoroutineStart.UNDISPATCHED) { live.expansion.stop() }
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val mayFold = mayFold(down.position)
            var claimed = false
            var moved = false
            var lockY = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    when {
                        claimed -> {
                            change.consume()
                            release(velocity.calculateVelocity().y)
                        }
                        // A control took the tap (the chevron folds the scene): what it started stands. A drag that a
                        // child kept (the text scrolling) ends consumed as well: the settle it caught goes on.
                        change.isConsumed && !moved -> Unit
                        // A tap, or a stream the region kept without folding: the settle goes on.
                        caught && !region.tracking -> scope.launch { live.settle(heading, reduced) }
                    }
                    break
                }
                if (!claimed) {
                    val delta = change.position - down.position
                    if (delta.getDistance() > viewConfiguration.touchSlop) moved = true
                    if (delta.getDistance() <= viewConfiguration.touchSlop || change.isConsumed) continue
                    val vertical = abs(delta.y) > abs(delta.x)
                    if (!vertical || !mayFold || (!caught && delta.y >= 0f)) {
                        if (caught) scope.launch { live.settle(heading, reduced) }
                        break
                    }
                    claimed = true
                    lockY = change.position.y
                    start()
                }
                change.consume()
                moveFromStart(change.position.y - lockY)
            }
        }
    }
}

/** The surface's own touch (see [SurfaceTouch]). */
fun Modifier.surfaceTouch(touch: SurfaceTouch): Modifier = pointerInput(touch) { with(touch) { handle() } }
