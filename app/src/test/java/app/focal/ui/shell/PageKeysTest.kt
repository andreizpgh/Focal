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

    @Test fun aFocusedControlKeepsTheKeysItAnswersItself() {
        val focused = FocusedKeys()
        val search = Any()
        val rename = Any()
        assertFalse(focused.owns(PageKey.PlayPause))
        focused.update(search, true) { FocusedKeys.Typing }
        // Focus moves from one field to the other: the new one reports before or after the old one lets go.
        focused.update(rename, true) { FocusedKeys.Typing }
        focused.update(search, false) { emptySet() }
        assertTrue(PageKey.entries.all { focused.owns(it) })
        focused.update(rename, false) { emptySet() }
        assertFalse(PageKey.entries.any { focused.owns(it) })
        // A field that leaves twice changes nothing.
        focused.update(rename, false) { emptySet() }
        assertFalse(focused.owns(PageKey.Copy))
        // A control's own keys are read when a key arrives: the stage turns from live to a note while focused.
        val stage = Any()
        var live = true
        focused.update(stage, true) { if (live) setOf(PageKey.PlayPause) else setOf(PageKey.Copy) }
        assertTrue(focused.owns(PageKey.PlayPause))
        assertFalse(focused.owns(PageKey.Copy))
        live = false
        assertFalse(focused.owns(PageKey.PlayPause))
        assertTrue(focused.owns(PageKey.Copy))
    }

    /**
     * The two-pane library hands an open note its shortcuts (spec 8.5) except the keys the focused control answers:
     * Space on the live stage expands it, Ctrl+C on a stage holding a note copies that note, a rename types them all.
     */
    @Test fun theLibraryPaneLeavesAFocusedControlItsOwnKeys() {
        val focused = FocusedKeys()
        val taken = mutableListOf<PageKey>()
        val keys = ShortcutKeys(focused.leaving { key -> taken += key; true })
        fun down(code: Int, repeat: Int = 0, ctrl: Boolean = false) = keys.down(code, repeat, !ctrl, ctrl, shift = false)

        // A row (or a button) answers none of them: the note takes Space and Ctrl+C.
        assertTrue(down(KeyEvent.KEYCODE_SPACE))
        assertTrue(down(KeyEvent.KEYCODE_C, ctrl = true))
        assertEquals(listOf(PageKey.PlayPause, PageKey.Copy), taken)
        // The live stage: Space and its repeats are its own (it expands); the Ctrl shortcuts still reach the note.
        taken.clear()
        val stage = Any()
        focused.update(stage, true) { setOf(PageKey.PlayPause) }
        assertFalse(down(KeyEvent.KEYCODE_SPACE))
        assertFalse(down(KeyEvent.KEYCODE_SPACE, repeat = 1))
        assertTrue(down(KeyEvent.KEYCODE_F, ctrl = true))
        assertTrue(down(KeyEvent.KEYCODE_C, ctrl = true))
        assertEquals(listOf(PageKey.Find, PageKey.Copy), taken)
        // A stage holding a note: Ctrl+C copies its own note; Space plays the open one.
        taken.clear()
        focused.update(stage, true) { setOf(PageKey.Copy) }
        assertFalse(down(KeyEvent.KEYCODE_C, ctrl = true))
        assertTrue(down(KeyEvent.KEYCODE_SPACE))
        assertEquals(listOf(PageKey.PlayPause), taken)
        // A rename types every key.
        taken.clear()
        focused.update(stage, false) { emptySet() }
        focused.update(Any(), true) { FocusedKeys.Typing }
        assertFalse(down(KeyEvent.KEYCODE_SPACE))
        assertFalse(down(KeyEvent.KEYCODE_Z, ctrl = true))
        assertEquals(emptyList<PageKey>(), taken)
    }
}
