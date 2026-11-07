package app.focal.ui.shell

import android.view.KeyEvent
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

    @Test fun aFocusAreaHandsThePageItsShortcutsAndAHeldSpaceActsOnce() {
        val taken = mutableListOf<PageKey>()
        var accepts = true
        val keys = ShortcutKeys { key -> taken += key; accepts }
        fun down(code: Int, repeat: Int = 0, ctrl: Boolean = false, shift: Boolean = false, alone: Boolean = !ctrl && !shift) =
            keys.down(code, repeat, alone, ctrl, shift)

        // Space alone plays or pauses; held, its repeats go with the first press and ask nothing again.
        assertTrue(down(KeyEvent.KEYCODE_SPACE))
        assertTrue(down(KeyEvent.KEYCODE_SPACE, repeat = 1))
        assertTrue(down(KeyEvent.KEYCODE_SPACE, repeat = 2))
        assertEquals(listOf(PageKey.PlayPause), taken)
        // Ctrl+F, Ctrl+C, Ctrl+Z and Ctrl+Shift+Z.
        taken.clear()
        assertTrue(down(KeyEvent.KEYCODE_F, ctrl = true))
        assertTrue(down(KeyEvent.KEYCODE_C, ctrl = true))
        assertTrue(down(KeyEvent.KEYCODE_Z, ctrl = true))
        assertTrue(down(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
        assertEquals(listOf(PageKey.Find, PageKey.Copy, PageKey.Undo, PageKey.Redo), taken)
        // Anything else stays with the focused control, unasked: Shift+Space, a plain letter, Enter, Ctrl+A.
        taken.clear()
        assertFalse(down(KeyEvent.KEYCODE_SPACE, shift = true))
        assertFalse(down(KeyEvent.KEYCODE_F))
        assertFalse(down(KeyEvent.KEYCODE_ENTER))
        assertFalse(down(KeyEvent.KEYCODE_A, ctrl = true))
        assertEquals(emptyList<PageKey>(), taken)
        // A Space the page declines (nothing to play) goes to the focused control, its repeats too.
        accepts = false
        assertFalse(down(KeyEvent.KEYCODE_SPACE))
        accepts = true
        assertFalse(down(KeyEvent.KEYCODE_SPACE, repeat = 1))
        assertEquals(listOf(PageKey.PlayPause), taken)
    }

    @Test fun anAreaIsTypingWhileOneOfItsFieldsHasFocus() {
        val typing = TypingFocus()
        val search = Any()
        val rename = Any()
        assertFalse(typing.active)
        typing.update(search, true)
        // Focus moves from one field to the other: the new one reports before or after the old one lets go.
        typing.update(rename, true)
        typing.update(search, false)
        assertTrue(typing.active)
        typing.update(rename, false)
        assertFalse(typing.active)
        // A field that leaves twice changes nothing.
        typing.update(rename, false)
        assertFalse(typing.active)
    }
}
