package app.focal.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockLayoutTest {
    private val totalPx = 728f
    private val circlePx = 112f
    private val resumePx = 296f
    private val gapPx = 24f

    @Test
    fun targetsIncludeGapOnlyWhenSlotIsPresent() {
        val empty = DockSlotLayout.targets(0f, 0f, totalPx, gapPx)
        assertEquals(DockSlots(0f, 0f, totalPx), empty)

        val idle = DockSlotLayout.targets(circlePx, 0f, totalPx, gapPx)
        assertEquals(DockSlots(circlePx + gapPx, 0f, totalPx - circlePx - gapPx), idle)

        val noteAppend = DockSlotLayout.targets(circlePx, circlePx, totalPx, gapPx)
        assertEquals(DockSlots(136f, 136f, totalPx - 272f), noteAppend)

        val paused = DockSlotLayout.targets(resumePx, 0f, totalPx, gapPx)
        assertEquals(DockSlots(resumePx + gapPx, 0f, totalPx - resumePx - gapPx), paused)
    }

    @Test
    fun interpolateGapsAnimateSmoothlyFromZeroWithZeroSnap() {
        val twoButtons = DockSlotLayout.targets(circlePx, 0f, totalPx, gapPx)
        val threeButtons = DockSlotLayout.targets(circlePx, circlePx, totalPx, gapPx)

        val start = DockSlotLayout.interpolate(twoButtons, threeButtons, 0f, totalPx)
        assertEquals(twoButtons, start)

        // Frame 0 right after tap/open (~5 %): slot opens ~7 px, strictly less than the 24 px gap (no 0->12dp snap).
        val frame0 = DockSlotLayout.interpolate(twoButtons, threeButtons, 0.05f, totalPx)
        assertEquals(136f, frame0.first, 0.01f)
        assertEquals(6.8f, frame0.second, 0.01f)
        assertTrue(frame0.second < gapPx)
        assertEquals(totalPx, frame0.first + frame0.second + frame0.primary, 0.01f)
        assertEquals(0f, DockSlotLayout.circleDiameter(frame0.second, circlePx, gapPx), 0.01f)

        val mid = DockSlotLayout.interpolate(twoButtons, threeButtons, 0.5f, totalPx)
        assertEquals(68f, mid.second, 0.01f)
        assertEquals(totalPx, mid.first + mid.second + mid.primary, 0.01f)

        val end = DockSlotLayout.interpolate(twoButtons, threeButtons, 1f, totalPx)
        assertEquals(threeButtons, end)
    }

    @Test
    fun horizontalLayoutPlacesActualSlotsAndMirrorsCleanlyInRtl() {
        val totalInt = totalPx.toInt()
        val from = DockSlotLayout.targets(circlePx, 0f, totalPx, gapPx)
        val to = DockSlotLayout.targets(circlePx, circlePx, totalPx, gapPx)

        // Early frame (g = 0.1 -> second slot 13.6 px < gap): gap opens smoothly without drawing a squashed second circle.
        val early = DockSlotLayout.layoutHorizontal(DockSlotLayout.interpolate(from, to, 0.1f, totalPx), totalInt, circlePx, gapPx)
        assertEquals(112, early.firstWidth)
        assertEquals(0, early.secondWidth)
        assertTrue(early.pillX in 137..155)
        assertEquals(totalInt, early.pillX + early.pillWidth)

        // Mid-flight (g = 0.5 -> second slot 68 px, content = 44 px true circle): gap 24 px sits cleanly on both sides.
        val midSlots = DockSlotLayout.interpolate(from, to, 0.5f, totalPx)
        val ltr = DockSlotLayout.layoutHorizontal(midSlots, totalInt, circlePx, gapPx, rtl = false)
        assertEquals(44, ltr.secondWidth)
        assertEquals(44, ltr.secondHeight)
        assertEquals(136, ltr.visualSecondX)
        assertEquals(136 + 44 + 24, ltr.visualPillX)
        assertEquals(totalInt, ltr.visualPillX + ltr.pillWidth)

        // RTL mirrors the exact same physical gaps in screen coordinates.
        val rtl = DockSlotLayout.layoutHorizontal(midSlots, totalInt, circlePx, gapPx, rtl = true)
        assertEquals(totalInt - ltr.firstWidth, rtl.visualFirstX)
        assertEquals(24, rtl.visualFirstX - (rtl.visualSecondX + rtl.secondWidth))
        assertEquals(24, rtl.visualSecondX - (rtl.visualPillX + rtl.pillWidth))
        assertEquals(0, rtl.visualPillX)
        assertEquals(ltr.pillWidth, rtl.pillWidth)
    }

    @Test
    fun railLayoutGrowsCircleAbovePillAndKeepsCompactCircleForResume() {
        val railWidth = circlePx.toInt()
        val railHeight = 256
        val idle = DockSlotLayout.targets(0f, 0f, 0f, gapPx)
        val live = DockSlotLayout.targets(circlePx, 0f, 0f, gapPx)

        val midSlots = DockSlotLayout.interpolate(idle, live, 0.5f, 0f)
        val midPlacement = DockSlotLayout.layoutRail(midSlots.first, railWidth, railHeight, circlePx, gapPx)
        assertEquals(midPlacement.firstWidth, midPlacement.firstHeight)
        assertEquals(circlePx.toInt(), midPlacement.pillSize)
        assertTrue(midPlacement.pillY > midPlacement.firstY + midPlacement.firstHeight)

        // Both Pause and paused Resume keep the exact circlePx x circlePx rail footprint above the primary circle.
        val settled = DockSlotLayout.layoutRail(live.first, railWidth, railHeight, circlePx, gapPx)
        assertEquals(circlePx.toInt(), settled.firstWidth)
        assertEquals(circlePx.toInt(), settled.firstHeight)
        assertEquals(0, settled.firstX)
        assertTrue(abs(settled.pillY - (settled.firstY + settled.firstHeight) - gapPx.toInt()) <= 1)
    }

    @Test
    fun oneButtonToThreeButtonsKeepsTotalWidthExact() {
        val one = DockSlotLayout.targets(0f, 0f, totalPx, gapPx)
        val three = DockSlotLayout.targets(circlePx, circlePx, totalPx, gapPx)
        for (step in 0..10) {
            val g = step / 10f
            val s = DockSlotLayout.interpolate(one, three, g, totalPx)
            assertEquals(totalPx, s.first + s.second + s.primary, 0.01f)
        }
    }

    @Test
    fun emergingCircleIsAlwaysSquareUntilFullDiameterAndNeverCapsule() {
        // Before the gap has finished opening: diameter and box are 0.
        assertEquals(0f, DockSlotLayout.circleDiameter(12f, circlePx, gapPx), 0.01f)
        assertEquals(0f to 0f, DockSlotLayout.circleBox(12f, circlePx, gapPx))

        // Mid emergence: width == height == diameter (true circle).
        val slotMid = gapPx + circlePx * 0.4f
        val dMid = DockSlotLayout.circleDiameter(slotMid, circlePx, gapPx)
        assertEquals(circlePx * 0.4f, dMid, 0.01f)
        val (wMid, hMid) = DockSlotLayout.circleBox(slotMid, circlePx, gapPx)
        assertEquals(wMid, hMid, 0.01f)
        assertEquals(dMid, wMid, 0.01f)

        // Pause -> Resume widening past circlePx: height stays capped at circlePx while width grows to resumePx.
        val (wWide, hWide) = DockSlotLayout.circleBox(resumePx + gapPx, circlePx, gapPx)
        assertEquals(resumePx, wWide, 0.01f)
        assertEquals(circlePx, hWide, 0.01f)
    }

    @Test
    fun emergingAlphasAreSymmetricAndContainerLeadsGlyph() {
        assertEquals(0f, DockSlotLayout.emergingContainerAlpha(0f, circlePx), 0.001f)
        assertEquals(1f, DockSlotLayout.emergingContainerAlpha(circlePx * 0.5f, circlePx), 0.001f)
        assertEquals(0f, DockSlotLayout.emergingGlyphAlpha(circlePx * 0.5f, circlePx), 0.001f)
        assertEquals(0f, DockSlotLayout.emergingGlyphAlpha(circlePx * 0.6f, circlePx), 0.001f)
        assertTrue(DockSlotLayout.emergingGlyphAlpha(circlePx * 0.8f, circlePx) in 0.1f..0.9f)
        assertEquals(1f, DockSlotLayout.emergingGlyphAlpha(circlePx, circlePx), 0.001f)
    }
}
