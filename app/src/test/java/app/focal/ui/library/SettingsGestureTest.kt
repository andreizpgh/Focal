package app.focal.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGestureTest {
    @Test fun releaseFollowsTheThresholdTheFlingAndTheCatch() {
        assertEquals(1f, SettingsGesture.release(0f, 0.36f, 0f))
        assertEquals(0f, SettingsGesture.release(0f, 0.34f, 0f))
        assertEquals(1f, SettingsGesture.release(0f, 0.1f, 1000f))
        assertEquals(0f, SettingsGesture.release(0f, 0.9f, -1000f))
        assertEquals(0f, SettingsGesture.release(1f, 0.64f, 0f))
        assertEquals(1f, SettingsGesture.release(1f, 0.66f, 0f))
        // A peek caught at 32/420 and barely moved goes back to the end nearer to where it was caught.
        assertEquals(0f, SettingsGesture.release(32f / 420f, 0.2f, 0f))
        assertEquals(1f, SettingsGesture.release(32f / 420f, 0.5f, 0f))
    }

    @Test fun theLockIsHorizontalInTheOpeningDirectionOnly() {
        val ltr = -1f
        assertTrue(SettingsGesture.locks(-20f, 10f, 0f, ltr))
        assertFalse(SettingsGesture.locks(-20f, 14f, 0f, ltr))
        assertFalse(SettingsGesture.locks(20f, 0f, 0f, ltr))
        assertTrue(SettingsGesture.locks(20f, 0f, 1f, ltr))
        assertFalse(SettingsGesture.locks(-20f, 0f, 1f, ltr))
        assertTrue(SettingsGesture.locks(20f, 0f, 0.5f, ltr) && SettingsGesture.locks(-20f, 0f, 0.5f, ltr))
        assertTrue(SettingsGesture.locks(20f, 0f, 0f, 1f))
    }

    @Test fun thePeekPlaysAtMostTwiceAndNeverAfterSettingsOpened() {
        assertTrue(PeekRules.allowed(opened = false, peeks = 0, hasNotes = false, visit = 1, firstPeekVisit = null))
        assertFalse(PeekRules.allowed(opened = false, peeks = 1, hasNotes = false, visit = 2, firstPeekVisit = 1))
        assertFalse(PeekRules.allowed(opened = false, peeks = 1, hasNotes = true, visit = 1, firstPeekVisit = 1))
        assertTrue(PeekRules.allowed(opened = false, peeks = 1, hasNotes = true, visit = 2, firstPeekVisit = 1))
        assertTrue(PeekRules.allowed(opened = false, peeks = 1, hasNotes = true, visit = 1, firstPeekVisit = null))
        assertFalse(PeekRules.allowed(opened = false, peeks = 2, hasNotes = true, visit = 5, firstPeekVisit = 1))
        assertFalse(PeekRules.allowed(opened = true, peeks = 0, hasNotes = true, visit = 1, firstPeekVisit = null))
    }
}
