package app.focal.ui.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import app.focal.ui.shell.BackEase
import app.focal.ui.state.LiveParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scene and the morph on the spec's 412 x 915 reference frame (1 px = 1 dp here): every ref value of 4.21.7 and 6.4. */
class MorphTest {

    @Test fun onlyADownwardDragPastTheSlopExpandsTheLiveStage() {
        val slop = 18f
        // Undecided inside the slop, whichever way it leans.
        assertNull(ExpandDrag.claims(10f, 12f, slop))
        // Straight or mostly down: it expands.
        assertEquals(true, ExpandDrag.claims(0f, 19f, slop))
        assertEquals(true, ExpandDrag.claims(12f, 16f, slop))
        // Diagonal at 45° or flatter, sideways, or up: the list keeps it (spec 6.5).
        assertEquals(false, ExpandDrag.claims(14f, 14f, slop))
        assertEquals(false, ExpandDrag.claims(17f, 9f, slop))
        assertEquals(false, ExpandDrag.claims(-20f, 0f, slop))
        assertEquals(false, ExpandDrag.claims(2f, -19f, slop))
    }
    private val density = Density(1f)
    private val slots = SceneSlots.of(width = 412f, height = 915f, statusTop = 40f, navigationBottom = 24f, timerLine = 84f, compact = false, density = density)
    /** The stage at rest (24, 56, 364 x 240); "0:00" is 99.4 dp wide at 48 sp. */
    private val stage = MorphOrigin.stage(Rect(24f, 56f, 388f, 296f), headHeight = 56f, timerWidth = 99.4f, timerScale = 48f / 76f, rtl = false, density = density)

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.05f) = assertEquals(expected, actual, tolerance)

    @Test fun theSceneIsLaidOutFromTheInsets() {
        near(236f, slots.start.waveTop)
        near(176f, slots.start.waveHeight)
        near(452f, slots.start.timerTop)
        near(540f, slots.start.stateTop)
        near(668f, slots.hintTop)
        near(116f, slots.live.waveTop)
        near(268f, slots.live.timerTop)
        near(354f, slots.live.stateTop)
        near(398f, slots.textTop)
        near(795f, slots.textBottom)
        near(48f, slots.chevronTop)
        assertEquals(Rect(92f, 819f, 388f, 875f), slots.pill)
    }

    @Test fun theStageIsTheOrigin() {
        val f = Morph.frame(0f, 1f, stage, slots)
        assertEquals(Rect(24f, 56f, 388f, 296f), f.clip)
        near(28f, f.radius)
        near(44f, f.wave.left)
        near(76f, f.wave.top)
        near(116f, f.wave.right)
        near(132f, f.wave.bottom)
        near(132f + 49.7f, f.timerCentre.x)
        near(104f, f.timerCentre.y)
        near(48f / 76f, f.timerScale, 1e-4f)
        near(148f, f.textTop)
        near(276f, f.textBottom)
        near(44f, f.stageTextLeft)
        near(1f, f.stageTextAlpha)
        near(0f, f.sceneTextAlpha)
        near(108f, f.badgeCentre.x)
        near(123f, f.badgeCentre.y)
        near(22f, f.badgeDiameter)
    }

    @Test fun theSceneIsTheEnd() {
        val f = Morph.frame(1f, 1f, stage, slots)
        assertEquals(slots.window, f.clip)
        near(0f, f.radius)
        near(118.6f, f.wave.left)
        near(116f, f.wave.top)
        near(293.4f, f.wave.right)
        near(252f, f.wave.bottom)
        near(206f, f.timerCentre.x)
        near(310f, f.timerCentre.y)
        near(1f, f.timerScale)
        near(398f, f.textTop)
        near(795f, f.textBottom)
        near(128f, f.textMask)
        near(274.0f, f.badgeCentre.x)
        near(230.1f, f.badgeCentre.y)
        near(28f, f.badgeDiameter)
        assertEquals(f.clip, Morph.rest(1f, slots).clip)
        assertEquals(f.wave, Morph.rest(1f, slots).wave)
    }

    /** Spec 6.4, measured: the clip's x at p 0.25, 0.6 and 0.9; every edge linear, the bottom following the finger. */
    @Test fun theClipGrowsLinearly() {
        for ((p, left, right) in listOf(Triple(0.25f, 18f, 394f), Triple(0.6f, 9.6f, 402.4f), Triple(0.9f, 2.4f, 409.6f))) {
            val clip = Morph.frame(p, 1f, stage, slots).clip
            near(left, clip.left)
            near(right, clip.right)
            near(296f + 619f * p, clip.bottom, 0.1f)
        }
    }

    /**
     * Q2: the text region's top stays 17.5-46 dp below the timer's box at every p, so no line tucks under the digits.
     * (The spec's upper figure is its end value; the gap peaks at 47.4 dp near p 0.82.)
     */
    @Test fun theTextRegionNeverMeetsTheTimer() {
        var p = 0f
        while (p <= 1f) {
            val f = Morph.frame(p, 1f, stage, slots)
            val timerBottom = f.timerCentre.y + slots.timerLine * f.timerScale / 2
            val gap = f.textTop - timerBottom
            assertTrue("p $p: gap $gap", gap in 17.4f..47.4f)
            p += 0.01f
        }
    }

    /** Q3: the two typesettings never show at once; the late elements arrive only in the last 30 %. */
    @Test fun typesettingsFadeThrough() {
        var p = 0f
        while (p <= 1f) {
            val f = Morph.frame(p, 1f, stage, slots)
            assertTrue("p $p", f.stageTextAlpha == 0f || f.sceneTextAlpha == 0f)
            if (p < 0.7f) assertEquals(0f, f.lateAlpha)
            p += 0.01f
        }
    }

    /** The wave's x eases in (smoothstep(0.1, 1, p)): it waits while the timer steps down. */
    @Test fun theWaveWaitsForTheTimer() {
        near(80f, Morph.frame(0.1f, 1f, stage, slots).wave.center.x)
        assertTrue(Morph.frame(0.3f, 1f, stage, slots).wave.center.x < 80f + (206f - 80f) * 0.3f)
    }

    /** T1′e: the bars keep their centre on the resting instrument; "0:00" is born in its slot at 0.92. */
    @Test fun theRestingInstrumentWakesInPlace() {
        val rest = MorphOrigin.rest(Rect(134f, 268f, 278f, 380f), Offset(206f, 494f))
        for (p in listOf(0f, 0.3f, 0.7f, 1f)) {
            val f = Morph.frame(p, 0f, rest, slots)
            near(206f, f.wave.center.x)
            near(324f, f.wave.center.y)
            near(206f, f.timerCentre.x)
            near(494f, f.timerCentre.y)
            near(0f, f.ringAlpha)
        }
        near(112f, Morph.frame(0f, 0f, rest, slots).wave.height)
        near(0.92f, Morph.frame(0f, 0f, rest, slots).timerScale)
    }

    @Test fun landingSettlesIntoThePill() {
        val (clip, radius) = Morph.landing(1f, slots)
        assertEquals(slots.pill, clip)
        near(28f, radius)
        assertEquals(slots.window, Morph.landing(0f, slots).first)
    }

    /** Spec 6.5: flings win; otherwise 40 % of the travel in the drag's direction; otherwise back to where it was. */
    @Test fun releasesSettleBySpecRules() {
        assertEquals(1f, Morph.release(0f, 0.4f, 0f))
        assertEquals(0f, Morph.release(0f, 0.39f, 0f))
        assertEquals(1f, Morph.release(0f, 0.05f, 1000f))
        assertEquals(0f, Morph.release(0f, 0.9f, -1000f))
        assertEquals(0f, Morph.release(1f, 0.6f, 0f))
        assertEquals(1f, Morph.release(1f, 0.61f, 0f))
        assertEquals(0f, Morph.release(1f, 0.95f, -1200f))
        // Caught mid-flight at 0.7: a small drag goes back to the nearer end.
        assertEquals(1f, Morph.release(0.7f, 0.6f, 0f))
        assertEquals(0f, Morph.release(0.3f, 0.4f, 0f))
    }

    @Test fun predictiveBackPreviewsTheThreshold() {
        near(0.6f, Morph.backPreview(1f, ease = BackEase::transform))
        near(1f, Morph.backPreview(0f, ease = BackEase::transform))
        // Halfway through the gesture the eased curve is already most of the way (PathInterpolator(0, 0, 0, 1)).
        assertTrue(Morph.backPreview(0.5f, ease = BackEase::transform) < 0.7f)
        // A back while an expand still settles scales p from where it was: it never jumps the surface open.
        near(0.3f, Morph.backPreview(0f, from = 0.3f, ease = BackEase::transform))
        near(0.18f, Morph.backPreview(1f, from = 0.3f, ease = BackEase::transform))
    }

    @Test fun rtlMirrorsTheStageRow() {
        val mirrored = MorphOrigin.stage(Rect(24f, 56f, 388f, 296f), 56f, 99.4f, 48f / 76f, rtl = true, density = density)
        near(388f - 56f, mirrored.waveCentre.x)
        near(388f - 108f - 49.7f, mirrored.timerCentre.x)
    }
}

class TranscriptTest {
    private fun p(id: Long, text: String, open: Boolean = false) = LiveParagraph(id, text, open)

    @Test fun whatIsThereAtFirstIsSettled() {
        val view = Transcript.first(listOf(p(1, "One."), p(2, "Two.")))
        assertEquals(0, view.arrival)
        assertEquals(TextMark(2, 0), view.latest)
        assertNull(view.previous)
        // The last paragraph is current, the one before it receded.
        assertEquals(0 to 0, view.spans(1))
        assertEquals(4 to 4, view.spans(0))
        assertFalse(view.opens(1, 0L))
    }

    @Test fun aNewParagraphIsTheLatestStretch() {
        val first = Transcript.first(listOf(p(1, "One.")))
        val next = Transcript.next(first, listOf(p(1, "One."), p(2, "Two.")), nowMs = 1_000L)
        assertEquals(1, next.arrival)
        assertEquals(TextMark(2, 0), next.latest)
        assertEquals(TextMark(1, 0), next.previous)
        assertTrue(next.opens(1, 1_100L))
        assertFalse("settled after 600 ms", next.opens(1, 1_700L))
        // The paragraph before recedes as a whole.
        assertEquals(4 to 0, next.spans(0))
    }

    @Test fun anOpenParagraphGrowsByItsNewWords() {
        val first = Transcript.first(listOf(p(1, "So the plan", open = true)))
        val next = Transcript.next(first, listOf(p(1, "So the plan for Saturday", open = true)), nowMs = 0L)
        assertEquals(TextMark(1, 12), next.latest)
        assertEquals(TextMark(1, 0), next.previous)
        // "So the plan " recedes, "for Saturday" is current.
        assertEquals(12 to 0, next.spans(0))
    }

    @Test fun aRewrittenEndingStartsAtItsWord() {
        val first = Transcript.first(listOf(p(1, "We meet at eight", open = true)))
        val next = Transcript.next(first, listOf(p(1, "We meet at eighty people", open = true)), nowMs = 0L)
        assertEquals(TextMark(1, "We meet at ".length), next.latest)
    }

    @Test fun aWindowAcrossABreakLightsBothParts() {
        val first = Transcript.first(listOf(p(1, "One", open = true)))
        val next = Transcript.next(first, listOf(p(1, "One two."), p(2, "Three", open = true)), nowMs = 0L)
        assertEquals(TextMark(1, 4), next.latest)
        assertEquals(4 to 0, next.spans(0))
        assertEquals(0 to 0, next.spans(1))
    }

    @Test fun unchangedTextKeepsTheView() {
        val first = Transcript.first(listOf(p(1, "One.")))
        assertTrue(first === Transcript.next(first, listOf(p(1, "One.")), nowMs = 5L))
    }
}
