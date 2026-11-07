package app.focal.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routing of the page shortcuts the activity hears (spec 8.5). */
class PageKeysTest {
    @Test fun thePageRegisteredLastIsAskedFirstAndADeclinedKeyGoesOn() {
        val keys = PageKeys()
        // Nothing registered: the activity hands the key back to the system.
        assertFalse(keys.dispatch(PageKey.Find))
        val heard = mutableListOf<String>()
        val below: (PageKey) -> Boolean = { key -> heard += "below $key"; key == PageKey.Copy }
        val top: (PageKey) -> Boolean = { key -> heard += "top $key"; key == PageKey.Find }
        keys.register(below)
        keys.register(top)
        assertTrue(keys.dispatch(PageKey.Find))
        assertEquals(listOf("top Find"), heard)
        heard.clear()
        assertTrue(keys.dispatch(PageKey.Copy))
        assertEquals(listOf("top Copy", "below Copy"), heard)
        heard.clear()
        assertFalse(keys.dispatch(PageKey.Undo))
        assertEquals(listOf("top Undo", "below Undo"), heard)
        // A page that leaves answers no more.
        keys.unregister(top)
        heard.clear()
        assertFalse(keys.dispatch(PageKey.Find))
        assertEquals(listOf("below Find"), heard)
        keys.unregister(below)
        heard.clear()
        assertFalse(keys.dispatch(PageKey.Copy))
        assertEquals(emptyList<String>(), heard)
    }
}
