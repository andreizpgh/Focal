package app.focal.ui.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page rules that hold without a frame clock (reduced motion: every change has arrived at once). */
class PageTransitionsTest {
    private val pages = PageTransitions(CoroutineScope(Dispatchers.Unconfined)).apply { reduced = true }
    private val a = Destination.Note("a")
    private val b = Destination.Note("b")

    @Test fun aPageThatLeftBeforeTheNextChangeIsNeitherDrawnNorTouched() {
        pages.push(a, PageMotion.Row, other = Destination.Library)
        pages.pop(a, other = Destination.Library)
        pages.push(b, PageMotion.Row, other = Destination.Library)
        // Back from a, then a tap on b while NavDisplay still holds a: a is no part of b's change.
        assertEquals(PageLook.Gone, pages.look(a))
        assertTrue(pages.leaving(a))
        assertTrue(pages.blocks(a))
        assertEquals(PageLook.Shown, pages.look(b))
        assertFalse(pages.leaving(b))
        assertFalse(pages.blocks(b))
    }

    @Test fun withNoChangeOnlyTheTopPageIsDrawn() {
        pages.push(a, PageMotion.Row, other = Destination.Library)
        pages.pop(a, other = Destination.Library)
        pages.left(a)
        assertEquals(PageLook.Gone, pages.look(a))
        assertEquals(PageLook.Shown, pages.look(Destination.Library))
    }

    @Test fun anArrivedPageTakesTouches() {
        pages.push(a, PageMotion.Stage, other = Destination.Library)
        assertFalse(pages.blocks(a))
        assertEquals(null, pages.provisional())
    }
}
