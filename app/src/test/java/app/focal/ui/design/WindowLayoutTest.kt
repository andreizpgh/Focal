package app.focal.ui.design

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.focal.ui.components.railDock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window classes of spec 5.14 (and the panes' split at a hinge) and the content column of 2.5. */
class WindowLayoutTest {
    @Test fun theWindowsSizePicksTheLayout() {
        val phone = WindowLayout(412.dp, 915.dp)
        assertFalse(phone.rail || phone.twoPane)
        assertEquals(FocalLayout.topBar, phone.topBar)

        // A phone turned to landscape: shorter than 480 dp, so the dock is a rail and the top bar 48 dp, even when wide.
        val landscape = WindowLayout(915.dp, 412.dp)
        assertTrue(landscape.rail)
        assertFalse("a short window never splits into panes", landscape.twoPane)
        assertEquals(48.dp, landscape.topBar)

        val tablet = WindowLayout(800.dp, 1280.dp)
        assertFalse(tablet.rail || tablet.twoPane)
        assertTrue(WindowLayout(840.dp, 600.dp).twoPane)
        assertFalse(WindowLayout(839.dp, 600.dp).twoPane)
        assertTrue(WindowLayout(915.dp, 479.dp).rail)
        assertFalse(WindowLayout(915.dp, 480.dp).rail)
    }

    /** Spec 5.14 on a foldable: two panes split at a separating vertical hinge, so neither lies across it. */
    @Test fun aHingeSplitsTwoPanesAtItself() {
        // Two 540 dp screens joined by a 24 dp hinge: the list pane ends where the hinge starts, the note after it.
        val book = WindowLayout(1104.dp, 800.dp, Hinge(540.dp, 564.dp))
        assertTrue(book.twoPane)
        assertEquals(540.dp, book.listPane)
        assertEquals(564.dp, book.detailStart)
        // A seamless fold in book posture has no width: the panes meet at it.
        val fold = WindowLayout(900.dp, 700.dp, Hinge(450.dp, 450.dp))
        assertEquals(450.dp, fold.listPane)
        assertEquals(450.dp, fold.detailStart)
        // No hinge: the reference phone's 412 dp, the note right after it.
        val wide = WindowLayout(1280.dp, 800.dp)
        assertEquals(412.dp, wide.listPane)
        assertEquals(412.dp, wide.detailStart)
        // Each side keeps at least 360 dp, or the hinge is not where the panes split.
        assertEquals(360.dp, WindowLayout(900.dp, 700.dp, Hinge(360.dp, 540.dp)).listPane)
        assertEquals(540.dp, WindowLayout(900.dp, 700.dp, Hinge(360.dp, 540.dp)).detailStart)
        assertEquals(412.dp, WindowLayout(1000.dp, 800.dp, Hinge(300.dp, 300.dp)).listPane)
        assertEquals(412.dp, WindowLayout(1000.dp, 800.dp, Hinge(700.dp, 700.dp)).detailStart)
        assertEquals(412.dp, WindowLayout(1000.dp, 800.dp, Hinge(520.dp, 480.dp)).listPane)
        // A hinge never splits a window into panes by itself: too narrow, or short (the rail), it stays one pane.
        assertFalse(WindowLayout(800.dp, 900.dp, Hinge(400.dp, 400.dp)).twoPane)
        assertFalse(WindowLayout(1104.dp, 470.dp, Hinge(540.dp, 564.dp)).twoPane)
    }

    @Test fun aHingeIsMeasuredFromTheStartEdge() {
        assertEquals(Hinge(500.dp, 524.dp), Hinge.of(500.dp, 524.dp, 1104.dp, rtl = false))
        // Mirrored, the list pane is on the right: its hinge edge is the hinge's right edge.
        assertEquals(Hinge(580.dp, 604.dp), Hinge.of(500.dp, 524.dp, 1104.dp, rtl = true))
    }

    /** Where the bottom column stands in each pane (spec 5.14), and between them while it crosses. */
    @Test fun thePanesSpansFollowTheHinge() {
        val density = Density(1f)
        val book = WindowLayout(1104.dp, 800.dp, Hinge(540.dp, 564.dp))
        val area = 0f..1104f
        assertEquals(0f..540f, book.paneSpan(1104f, area, detail = 0f, rtl = false, density = density))
        assertEquals(564f..1104f, book.paneSpan(1104f, area, detail = 1f, rtl = false, density = density))
        assertEquals(282f..822f, book.paneSpan(1104f, area, detail = 0.5f, rtl = false, density = density))
        // Mirrored: the list pane at the right, the note to the left of the hinge.
        assertEquals(564f..1104f, book.paneSpan(1104f, area, detail = 0f, rtl = true, density = density))
        assertEquals(0f..540f, book.paneSpan(1104f, area, detail = 1f, rtl = true, density = density))
        // Side insets stay outside both panes.
        assertEquals(24f..540f, book.paneSpan(1104f, 24f..1080f, detail = 0f, rtl = false, density = density))
        assertEquals(564f..1080f, book.paneSpan(1104f, 24f..1080f, detail = 1f, rtl = false, density = density))
        // No hinge: 412 dp, in pixels at the window's density.
        val wide = WindowLayout(1280.dp, 800.dp)
        assertEquals(0f..824f, wide.paneSpan(2560f, 0f..2560f, detail = 0f, rtl = false, density = Density(2f)))
        assertEquals(824f..2560f, wide.paneSpan(2560f, 0f..2560f, detail = 1f, rtl = false, density = Density(2f)))
        assertEquals(0f..1736f, wide.paneSpan(2560f, 0f..2560f, detail = 1f, rtl = true, density = Density(2f)))
        // One pane: the page area itself.
        assertEquals(0f..412f, WindowLayout(412.dp, 915.dp).paneSpan(412f, 0f..412f, detail = 1f, rtl = false, density = density))
    }

    @Test fun theContentColumnIsCappedAt600AndCentred() {
        assertEquals(24.dp, FocalLayout.columnInset(412.dp))
        assertEquals(24.dp, FocalLayout.columnInset(648.dp))
        assertEquals(100.dp, FocalLayout.columnInset(800.dp))
        assertEquals(100f, FocalLayout.columnInset(800f, Density(1f)), 0.001f)
        assertEquals(200f, FocalLayout.columnInset(1600f, Density(2f)), 0.001f)
    }

    /** The rail's two circles (56 + 12 + 56) are centred in the 96 dp beside the area and between the bars. */
    @Test fun theRailStandsBesideTheArea() {
        val density = Density(1f)
        assertEquals(Rect(839f, 144f, 895f, 268f), railDock(0f, 819f, statusTop = 24f, navigationTop = 388f, rtl = false, density = density))
        assertEquals(Rect(20f, 144f, 76f, 268f), railDock(96f, 915f, statusTop = 24f, navigationTop = 388f, rtl = true, density = density))
    }
}
