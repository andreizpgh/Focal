package app.focal.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekSnapTest {
    private fun word(start: Int, end: Int, startMs: Long?, endMs: Long?): WordUi =
        WordUi(start = start, end = end, startMs = startMs, endMs = endMs, doubtful = false)

    private fun paragraph(
        id: Long,
        startMs: Long,
        endMs: Long,
        words: List<WordUi>,
        exactTimes: Boolean = true,
    ): ParagraphUi = ParagraphUi(
        id = id,
        revision = 1L,
        text = "sample paragraph text",
        startMs = startMs,
        endMs = endMs,
        words = words,
        exactTimes = exactTimes,
        edited = !exactTimes,
        canRestore = false,
        open = false,
    )

    @Test
    fun insideTimedWordLightsWordAndAppliesLeadClampedByPrevWord() {
        val w0 = word(0, 5, 1000L, 1400L)
        val w1 = word(6, 11, 1420L, 1900L)
        val w2 = word(12, 17, 2500L, 2900L)
        val paragraphs = listOf(paragraph(1L, 1000L, 2900L, listOf(w0, w1, w2)))

        // Scrubbing inside w0 (no previous word): full 150 ms lead.
        val res0 = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1250L, durationMs = 5000L)
        assertEquals(WordLocation(0, 0), res0.location)
        assertEquals(850L, res0.targetMs)

        // Scrubbing inside w1 (20 ms after w0.end): lead = clamp(20 + 40, 80, 150) = 80 ms.
        val res1 = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1650L, durationMs = 5000L)
        assertEquals(WordLocation(0, 1), res1.location)
        assertEquals(1340L, res1.targetMs)

        // Scrubbing inside w2 (600 ms after w1.end): full 150 ms lead.
        val res2 = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 2700L, durationMs = 5000L)
        assertEquals(WordLocation(0, 2), res2.location)
        assertEquals(2350L, res2.targetMs)
    }

    @Test
    fun silenceBetweenWordsPicksNearestBoundaryAndLongPausePlaysRaw() {
        val w0 = word(0, 4, 1000L, 1200L)
        val w1 = word(5, 9, 1700L, 2100L)
        val w2 = word(10, 15, 4500L, 4900L)
        val paragraphs = listOf(paragraph(1L, 1000L, 4900L, listOf(w0, w1, w2)))

        // Release 100 ms after w0.end vs 400 ms before w1.start -> lights w0 and plays from w0.start - 150.
        val nearPrev = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1300L, durationMs = 6000L)
        assertEquals(WordLocation(0, 0), nearPrev.location)
        assertEquals(850L, nearPrev.targetMs)

        // Release 100 ms before w1.start vs 400 ms after w0.end -> lights w1 and plays from w1.start - 150.
        val nearNext = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1600L, durationMs = 6000L)
        assertEquals(WordLocation(0, 1), nearNext.location)
        assertEquals(1550L, nearNext.targetMs)

        // Long pause (2100..4500): 3200 is > 600 ms from both -> lights no word and plays raw.
        val longPause = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 3200L, durationMs = 6000L)
        assertEquals(WordLocation(0, null), longPause.location)
        assertEquals(3200L, longPause.targetMs)
    }

    @Test
    fun gapTakesPrecedenceAndShowsNoWord() {
        val w0 = word(0, 4, 5000L, 5400L)
        val paragraphs = listOf(paragraph(1L, 5000L, 5400L, listOf(w0)))
        val gaps = listOf(GapUi(startMs = 1000L, endMs = 4000L, afterParagraphId = null))

        val insideGap = SeekSnap.resolve(paragraphs, gaps, scrubMs = 2000L, durationMs = 6000L)
        assertNull(insideGap.location)
        assertEquals(1850L, insideGap.targetMs)

        // Near start of gap: clamped to gap.startMs
        val nearGapStart = SeekSnap.resolve(paragraphs, gaps, scrubMs = 1050L, durationMs = 6000L)
        assertNull(nearGapStart.location)
        assertEquals(1000L, nearGapStart.targetMs)

        // Release in the final 300 ms inside a trailing gap must park (null) rather than rewinding 150 ms and playing
        val endGap = listOf(GapUi(startMs = 5500L, endMs = 10_000L, afterParagraphId = 1L))
        val endInGap = SeekSnap.resolve(paragraphs, endGap, scrubMs = 9750L, durationMs = 10_000L)
        assertNull(endInGap.location)
        assertNull(endInGap.targetMs)
    }

    @Test
    fun editedPartialBridgeUsesPreviousTimedEndMinus80() {
        val w0 = word(0, 4, 1000L, 1400L)
        val edited = word(5, 11, null, null)
        val w2 = word(12, 16, 2000L, 2400L)
        val paragraphs = listOf(paragraph(1L, 1000L, 2400L, listOf(w0, edited, w2), exactTimes = false))

        val bridge = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1600L, durationMs = 4000L)
        assertEquals(WordLocation(0, 1), bridge.location)
        assertEquals(1320L, bridge.targetMs)
    }

    @Test
    fun legacyParagraphWithoutWordTimesSubtracts150WithoutJumpingBackToParagraphStart() {
        val paragraphs = listOf(
            paragraph(1L, 0L, 30_000L, listOf(word(0, 6, null, null)), exactTimes = false),
        )
        val res = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 20_000L, durationMs = 30_000L)
        assertEquals(WordLocation(0, null), res.location)
        assertEquals(19_850L, res.targetMs)

        // Release in the final 300 ms of a legacy paragraph also parks (null) rather than playing
        val endLegacy = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 29_750L, durationMs = 30_000L)
        assertEquals(WordLocation(0, null), endLegacy.location)
        assertNull(endLegacy.targetMs)
    }

    @Test
    fun crossParagraphBoundaryPicksCloserOnset() {
        val p0 = paragraph(1L, 0L, 1200L, listOf(word(0, 4, 500L, 900L)))
        val p1 = paragraph(2L, 1200L, 2000L, listOf(word(0, 4, 1500L, 1900L)))
        // Release at 1350 ms -> 150 ms before p1.w0 (1500), 450 ms after p0.w0 (900)
        val res = SeekSnap.resolve(listOf(p0, p1), emptyList(), scrubMs = 1350L, durationMs = 3000L)
        assertEquals(WordLocation(1, 0), res.location)
        assertEquals(1350L, res.targetMs)
    }

    @Test
    fun clampsZeroParksNearEndAndCapsRewindAt1500Ms() {
        // Near t = 0: clamped >= 0
        val earlyWord = word(0, 4, 50L, 250L)
        val paragraphs = listOf(paragraph(1L, 50L, 10_000L, listOf(earlyWord)))
        val atZero = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 80L, durationMs = 10_000L)
        assertEquals(0L, atZero.targetMs)

        // Rewind cap: long noisy/bad span > 1500 ms before scrub falls back to raw - 150
        val longBadSpan = word(0, 5, 1000L, 4000L)
        val capParagraphs = listOf(paragraph(1L, 1000L, 4000L, listOf(longBadSpan)))
        val capped = SeekSnap.resolve(capParagraphs, emptyList(), scrubMs = 3500L, durationMs = 10_000L)
        assertEquals(3350L, capped.targetMs)

        // Near end within 300 ms with no lit word -> parks (targetMs = null)
        val endedParagraphs = listOf(paragraph(1L, 1000L, 2000L, listOf(word(0, 4, 1000L, 1500L))))
        val nearEnd = SeekSnap.resolve(endedParagraphs, emptyList(), scrubMs = 9850L, durationMs = 10_000L)
        assertEquals(WordLocation(0, null), nearEnd.location)
        assertNull(nearEnd.targetMs)

        // Near end but still lighting the final word -> plays the word instead of parking
        val finalWordParagraphs = listOf(paragraph(1L, 1000L, 9900L, listOf(word(0, 4, 9600L, 9850L))))
        val nearFinalWord = SeekSnap.resolve(finalWordParagraphs, emptyList(), scrubMs = 9800L, durationMs = 10_000L)
        assertEquals(WordLocation(0, 0), nearFinalWord.location)
        assertEquals(9450L, nearFinalWord.targetMs)
    }

    @Test
    fun gapAfterLegacyParagraphSnapsIntoNextTimedParagraph() {
        val legacyBefore = paragraph(1L, 0L, 1200L, listOf(word(0, 6, null, null)), exactTimes = false)
        val timedAfter = paragraph(2L, 2000L, 4000L, listOf(word(0, 5, 2000L, 2400L)))
        val paragraphs = listOf(legacyBefore, timedAfter)

        // Inside the legacy paragraph's own range: legacy lead applies and no word is lit.
        val insideLegacy = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1000L, durationMs = 5000L)
        assertEquals(WordLocation(0, null), insideLegacy.location)
        assertEquals(850L, insideLegacy.targetMs)

        // Past the legacy paragraph's endMs, 100 ms before the timed paragraph's first word: snap to that word.
        val inPauseBeforeTimed = SeekSnap.resolve(paragraphs, emptyList(), scrubMs = 1900L, durationMs = 5000L)
        assertEquals(WordLocation(1, 0), inPauseBeforeTimed.location)
        assertEquals(1850L, inPauseBeforeTimed.targetMs)
    }
}
