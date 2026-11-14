package app.focal.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import app.focal.ui.scene.ExpandDrag
import app.focal.ui.scene.Morph
import app.focal.ui.shell.Destination
import app.focal.ui.shell.PageTransitions

/**
 * Pull the stage down to open its note (spec 6.5): the gesture the live stage has for its scene, for the note the
 * stage holds at rest, and the same in every way. As the library list's nested-scroll parent it takes the list's
 * downward leftovers at its top (where an overscroll would stretch): the card's bottom edge follows the finger 1:1 to
 * the window's ([PageTransitions.drag]). The note's page is opened as the pull begins ([open], the stage's own tap),
 * so the card turns into the page under the finger. Let go, it settles as the live stage does ([Morph.release]): a
 * fling down, or 40 % of the way, opens the page; anything less goes back into the stage, and the page is dropped
 * unseen ([drop]). Armed per stream by [arm], like [ExpandDrag]: only for a touch-down on a stage that holds a note,
 * with the list at its very top, and only if the finger then heads down more than sideways ([track]).
 */
@Stable
internal class StagePull(private val pages: PageTransitions) : NestedScrollConnection {
    /** The finger's travel for the card's bottom edge to reach the window's, in pixels. */
    var travel: () -> Float = { 1f }
    var density = 1f
    /** The stage's note, what opens its page (the same as a tap on the stage), and what drops it again. */
    var note: String? = null
    var open: () -> Unit = {}
    var drop: (Destination) -> Unit = {}

    private var armed = false
    private var decided = false
    private var tracking = false
    private var pulled = 0f
    private var page: Destination? = null

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

    private fun fraction(): Float = (pulled / travel().coerceAtLeast(1f)).coerceIn(0f, 1f)

    /**
     * Moves the card's edge by [dy] within its way (0 at the stage, the whole travel at the window's edge) and returns
     * what it took: the finger keeps its place on the card at both ends instead of running ahead of it.
     */
    private fun moveBy(dy: Float): Float {
        val old = pulled
        pulled = (old + dy).coerceIn(0f, travel().coerceAtLeast(1f))
        pages.drag(fraction())
        return pulled - old
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!tracking || available.y >= 0f) return Offset.Zero
        // The finger going back up takes the card back with it before the list scrolls; once the card is the stage
        // again, the rest of the way up scrolls the list.
        return Offset(0f, moveBy(available.y))
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!armed || !decided || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!tracking) {
            val id = note
            val destination = id?.let { Destination.Note(it) }
            if (destination == null || !pages.beginDrag(destination)) {
                armed = false
                return Offset.Zero
            }
            tracking = true
            pulled = 0f
            page = destination
            open()
        }
        moveBy(available.y)
        // All of it: no overscroll stretch while the stage holds the drag (past the window's edge the card is the page).
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        armed = false
        if (!tracking) return Velocity.Zero
        tracking = false
        val destination = page
        page = null
        val opens = Morph.release(0f, fraction(), available.y / density) >= 1f
        // The card goes on at the finger's speed, in its way per second.
        pages.endDrag(open = opens, velocity = available.y / travel().coerceAtLeast(1f)) { destination?.let(drop) }
        return available
    }
}
