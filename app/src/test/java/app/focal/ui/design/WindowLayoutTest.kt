package app.focal.ui.design

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.focal.ui.components.railDock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window classes of spec 5.14 and the content column of 2.5. */
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
