package app.focal.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import app.focal.ui.design.FocalGestures
import app.focal.ui.scene.ExpandDrag
import app.focal.ui.shell.Destination
import app.focal.ui.shell.PageTransitions

/**
 * Pull the stage down to open its note (spec 6.5): the gesture the live stage has for its scene, for the note the
 * stage holds at rest. As the library list's nested-scroll parent it takes the list's downward leftovers at its top
 * (where an overscroll would stretch): the card's bottom edge follows the finger ([PageTransitions.drag]), and a
 * release far enough, or a fling down, opens the note's page from where the card is ([open]); sooner, the card goes
 * back. Armed per stream by [arm], like [ExpandDrag]: only for a touch-down on a stage that holds a note, with the
 * list at its very top, and only if the finger then heads down more than sideways ([track]).
 */
@Stable
internal class StagePull(private val pages: PageTransitions) : NestedScrollConnection {
    /** The finger's travel for the card's bottom edge to reach the window's, in pixels. */
    var travel: () -> Float = { 1f }
    var density = 1f
    /** The stage's note, and what opens its page (the same as a tap on the stage). */
    var note: String? = null
    var open: () -> Unit = {}

    private var armed = false
    private var decided = false
    private var tracking = false
    private var pulled = 0f

    /** At every touch-down on the list: whether this stream may pull the stage open. */
    fun arm(enabled: Boolean) {
        armed = enabled && !tracking
        decided = false
    }

    /** An armed stream whose direction is not decided yet: the stage feeds it the finger's moves ([track]). */
    val deciding: Boolean get() = armed && !decided

    fun track(dx: Float, dy: Float, slop: Float) {
        if (!deciding) return
        val claims = ExpandDrag.claims(dx, dy, slop) ?: return
        decided = true
        if (!claims) armed = false
    }

    private fun fraction(): Float = pulled / travel().coerceAtLeast(1f)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!tracking || available.y >= 0f) return Offset.Zero
        // The finger going back up takes the card back with it before the list scrolls.
        pulled = (pulled + available.y).coerceAtLeast(0f)
        pages.drag(fraction())
        return Offset(0f, available.y)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!armed || !decided || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!tracking) {
            val id = note
            if (id == null || !pages.beginDrag(Destination.Note(id))) {
                armed = false
                return Offset.Zero
            }
            tracking = true
            pulled = 0f
        }
        pulled += available.y
        pages.drag(fraction())
        // All of it: no overscroll stretch while the stage holds the drag.
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        armed = false
        if (!tracking) return Velocity.Zero
        tracking = false
        val flung = available.y / density > FocalGestures.FLING_DP_PER_S
        if (fraction() >= PageTransitions.PULL_OPENS || flung) open()
        // Let go too soon, or the page could not be opened after all: the card goes back. (Once the page has
        // taken the pull over there is nothing left to cancel.)
        pages.cancelDrag()
        return available
    }
}
