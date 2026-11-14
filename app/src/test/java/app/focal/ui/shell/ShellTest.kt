package app.focal.ui.shell

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import app.focal.ui.design.FocalColors
import app.focal.ui.state.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTest {
    private val scope = SaverScope { true }

    @Test fun destinationsSurviveTheirEncoding() {
        for (destination in listOf(Destination.Library, Destination.Licenses, Destination.Note("2b7e:note:ids"))) {
            assertEquals(destination, Destination.decode(Destination.encode(destination)))
        }
        assertNull(Destination.decode("settings"))
    }

    @Test fun aRestoredBackStackIsAlwaysRootedAtTheLibrary() {
        val saved = with(Destination.BackStackSaver) { scope.save(SnapshotStateList<Destination>().apply { add(Destination.Library); add(Destination.Note("a")) }) }!!
        assertEquals(listOf(Destination.Library, Destination.Note("a")), Destination.BackStackSaver.restore(saved)!!.toList())
        assertEquals(listOf(Destination.Library, Destination.Note("b")), Destination.BackStackSaver.restore(listOf("note:b"))!!.toList())
        assertEquals(listOf(Destination.Library), Destination.BackStackSaver.restore(listOf("library", "library", "nonsense"))!!.toList())
    }

    @Test fun aPageOnlyPulledAtIsNotSaved() {
        val saver = Destination.backStackSaver { Destination.Note("pulled") }
        val stack = SnapshotStateList<Destination>().apply {
            add(Destination.Library)
            add(Destination.Note("a"))
            add(Destination.Note("pulled"))
        }
        val saved = with(saver) { scope.save(stack) }!!
        assertEquals(listOf(Destination.Library, Destination.Note("a")), saver.restore(saved)!!.toList())
    }
}

class SystemBarsTest {
    private val density = Density(2f)
    private val navigationTop = 1782f

    private fun clip(top: Float, bottom: Float) = Rect(0f, top, 824f, bottom)

    /** Spec 6.2: light status icons at clip top <= status + 12 dp, back only below + 16 dp (no flicker in between). */
    @Test fun statusIconsFlipWithHysteresis() {
        val bars = SystemBarsState()
        bars.followMaterial(clip(30f, 1000f), navigationTop, density)
        assertFalse(bars.statusOnMaterial)
        bars.followMaterial(clip(24f, 1000f), navigationTop, density)
        assertTrue(bars.statusOnMaterial)
        bars.followMaterial(clip(30f, 1000f), navigationTop, density)
        assertTrue("inside the 4 dp hysteresis", bars.statusOnMaterial)
        bars.followMaterial(clip(33f, 1000f), navigationTop, density)
        assertFalse(bars.statusOnMaterial)
    }

    /** Navigation icons: light once the clip's bottom reaches the inset's top + 12 dp, back above + 8 dp. */
    @Test fun navigationIconsFlipWithHysteresis() {
        val bars = SystemBarsState()
        bars.followMaterial(clip(0f, navigationTop + 20f), navigationTop, density)
        assertFalse(bars.navigationOnMaterial)
        bars.followMaterial(clip(0f, navigationTop + 24f), navigationTop, density)
        assertTrue(bars.navigationOnMaterial)
        bars.followMaterial(clip(0f, navigationTop + 17f), navigationTop, density)
        assertTrue(bars.navigationOnMaterial)
        bars.followMaterial(clip(0f, navigationTop + 15f), navigationTop, density)
        assertFalse(bars.navigationOnMaterial)
        bars.followMaterial(null, navigationTop, density)
        assertFalse(bars.statusOnMaterial)
    }

    /** Three buttons on a phone turned to landscape: the bar is on a side, and the clip's side edge flips it (5.14). */
    @Test fun aSideNavigationBarFlipsWithTheClipsSideEdge() {
        val right = NavigationBar.of(1830f, 824f, left = 0, right = 96, bottom = 0)
        assertEquals(NavigationBar(NavigationBar.Side.Right, 1734f), right)
        val bars = SystemBarsState()
        bars.followMaterial(Rect(0f, 0f, 1734f + 20f, 824f), right, density)
        assertFalse("the bottom edge alone never reaches a side bar", bars.navigationOnMaterial)
        bars.followMaterial(Rect(0f, 0f, 1734f + 24f, 824f), right, density)
        assertTrue(bars.navigationOnMaterial)
        bars.followLanding(Rect(1660f, 500f, 1772f, 612f), statusBottom = 48f, right)
        assertTrue("the settling clip still covers the bar's inner edge", bars.navigationOnMaterial)
        bars.followLanding(Rect(1600f, 500f, 1712f, 612f), statusBottom = 48f, right)
        assertFalse(bars.navigationOnMaterial)
        val left = NavigationBar.of(1830f, 824f, left = 96, right = 0, bottom = 0)
        bars.followMaterial(Rect(96f - 24f, 0f, 1830f, 824f), left, density)
        assertTrue(bars.navigationOnMaterial)
        assertEquals(NavigationBar.bottom(776f), NavigationBar.of(1830f, 824f, left = 0, right = 0, bottom = 48))
    }

    @Test fun reducedMotionFlipsAtHalfTheCrossfade() {
        val bars = SystemBarsState()
        bars.followCrossfade(0.49f)
        assertFalse(bars.statusOnMaterial)
        bars.followCrossfade(0.5f)
        assertTrue(bars.statusOnMaterial && bars.navigationOnMaterial)
    }
}

class ThemeTest {
    @Test fun theSettingWinsOverTheSystem() {
        assertTrue(NightMode.isDark(ThemeMode.DARK, systemDark = false))
        assertFalse(NightMode.isDark(ThemeMode.LIGHT, systemDark = true))
        assertTrue(NightMode.isDark(ThemeMode.SYSTEM, systemDark = true))
    }

    /** Spec 2.1: the contrast setting lifts the receded greys one step; high-contrast text stops receding. */
    @Test fun contrastAndHighContrastTextAdjustThePalette() {
        val standard = FocalColors.of(dark = false)
        val lifted = FocalColors.of(dark = false, contrast = 0.5f)
        assertEquals(0xFF4A4A4A.toInt(), lifted.muted.toArgbInt())
        assertEquals(standard.muted, lifted.faint)
        val dark = FocalColors.of(dark = true, contrast = 1f)
        assertEquals(0xFFB0B0B0.toInt(), dark.muted.toArgbInt())
        val plain = FocalColors.of(dark = false, highContrastText = true)
        assertEquals(plain.ink, plain.recede)
        assertEquals(standard.muted, standard.recede)
    }

    private fun Color.toArgbInt(): Int = toArgb()
}
