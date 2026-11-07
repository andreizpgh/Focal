package app.focal.ui

import android.graphics.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature
import androidx.window.layout.FoldingFeature.Orientation.Companion.HORIZONTAL
import androidx.window.layout.FoldingFeature.Orientation.Companion.VERTICAL
import androidx.window.layout.FoldingFeature.State.Companion.FLAT
import androidx.window.layout.FoldingFeature.State.Companion.HALF_OPENED
import androidx.window.testing.layout.FoldingFeature
import app.focal.ui.design.Hinge
import app.focal.ui.shell.separatingHinge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which of androidx.window's folds splits two panes (spec 5.14), and where, from their bounds in the window's pixels
 * (window-testing's FoldingFeature, as a device reports it): a vertical hinge with a width, and a seamless fold in book
 * posture (no width), do; a tabletop fold and a flat seamless fold do not. Left to right and mirrored, at a phone's
 * density. A pure check: it needs a device only for android.graphics.Rect.
 */
@RunWith(AndroidJUnit4::class)
class WindowFoldsTest {
    private val density = Density(2.625f)
    /** 1000 x 800 dp. */
    private val window = Rect(0, 0, 2625, 2100)
    private val width = 1000.dp

    private fun fold(center: Int, size: Int, state: FoldingFeature.State, orientation: FoldingFeature.Orientation) =
        FoldingFeature(window, center, size, state, orientation)

    @Test fun onlyAVerticalFoldThatSeparatesTheWindowSplitsThePanes() {
        val left = (1050 / 2.625f).dp
        val right = (1114 / 2.625f).dp
        val seam = (1181 / 2.625f).dp
        for (rtl in listOf(false, true)) {
            val hinge = separatingHinge(listOf(fold(center = 1082, size = 64, FLAT, VERTICAL)), width, density, rtl)
            assertEquals(if (rtl) Hinge(width - right, width - left) else Hinge(left, right), hinge)
            val book = separatingHinge(listOf(fold(center = 1181, size = 0, HALF_OPENED, VERTICAL)), width, density, rtl)
            assertEquals(if (rtl) Hinge(width - seam, width - seam) else Hinge(seam, seam), book)
            assertNull(separatingHinge(listOf(fold(center = 1050, size = 0, HALF_OPENED, HORIZONTAL)), width, density, rtl))
            assertNull(separatingHinge(listOf(fold(center = 1181, size = 0, FLAT, VERTICAL)), width, density, rtl))
            // The first that splits, among others that do not.
            val many = listOf(fold(center = 1050, size = 0, HALF_OPENED, HORIZONTAL), fold(center = 1082, size = 64, FLAT, VERTICAL))
            assertEquals(hinge, separatingHinge(many, width, density, rtl))
        }
    }
}
