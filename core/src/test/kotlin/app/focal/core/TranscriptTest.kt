package app.focal.core

import kotlin.test.*
import org.junit.Test

class TranscriptTest {
    @Test fun `overlap handles Russian punctuation and retains the new words`() {
        assertEquals("а затем домой.", Transcript.removeOverlap("Мы идём в парк.", "В парк, а затем домой."))
    }
    @Test fun `a single repeated word is not removed`() {
        assertEquals("Да, продолжим.", Transcript.removeOverlap("Да.", "Да, продолжим."))
    }
    @Test fun `low confidence and empty hypotheses require review`() {
        assertTrue(Transcript.needsReview(emptyList(), 0f))
        assertTrue(Transcript.needsReview(listOf(0.9f, 0.2f, 0.3f), 0f))
        assertTrue(Transcript.needsReview(listOf(0.9f), 0.7f))
        assertFalse(Transcript.needsReview(listOf(0.9f, 0.8f), 0.05f))
    }
    @Test fun `invalid confidence is never presented as trusted text`() {
        assertTrue(Transcript.needsReview(listOf(Float.NaN), 0f))
        assertTrue(Transcript.needsReview(listOf(Float.POSITIVE_INFINITY), 0f))
        assertTrue(Transcript.needsReview(listOf(1.1f), 0f))
        assertTrue(Transcript.needsReview(listOf(.9f), Float.NaN))
        assertTrue(Transcript.needsReview(listOf(.9f), -.1f))
    }
    @Test fun `punctuation alone cannot establish duplicated words`() {
        assertEquals("... — New words", Transcript.removeOverlap("... —", "... — New words"))
    }
    @Test fun `prompt stops at an earlier silence even when the latest passage is nearby`() {
        val previous = listOf(Transcript.Segment(0, 1000, "Unrelated earlier topic."),
            Transcript.Segment(10000, 11000, "Current topic."))
        assertEquals("Current topic.", Transcript.trustedContext(previous, 11500))
    }
    @Test fun `an uncertain tail and audio overlapping the new chunk never enter its prompt`() {
        assertEquals("", Transcript.trustedContext(listOf(Transcript.Segment(0, 2000, "Untrusted", true)), 2200))
        assertEquals("Earlier trusted words", Transcript.trustedContext(listOf(
            Transcript.Segment(10000, 14000, "Earlier trusted words"),
            Transcript.Segment(14000, 16000, "These words are in the new audio too")), 15500))
    }
    @Test fun `prompt budget preserves whole Unicode words`() {
        val result = Transcript.trustedContext(listOf(Transcript.Segment(0, 1000, "alpha🙂 beta")), 1100, maxChars = 7)
        assertEquals("beta", result)
        assertEquals("", Transcript.trustedContext(listOf(Transcript.Segment(0, 1000, "оченьдлинноеслово")), 1100, maxChars = 5))
    }
    @Test fun `identical words spoken after the audio overlap are retained`() {
        val next = Transcript.Segment(16020, 17000, "В парк, а затем домой.")
        assertEquals(listOf(next), Transcript.reconcileBoundary(listOf(
            Transcript.Segment(0, 16000, "Мы идём в парк.")), listOf(next), 15500, 17000, 16000))
    }
    @Test fun `a mixed boundary segment retains new speech and requests review`() {
        val next = Transcript.Segment(15500, 17000, "В парк, а затем домой.")
        assertEquals(listOf(next.copy(uncertain = true)), Transcript.reconcileBoundary(listOf(
            Transcript.Segment(0, 16000, "Мы идём в парк.")), listOf(next), 15500, 17000, 16000))
    }
    @Test fun `duplicate audio can span several short decoder segments`() {
        val next = Transcript.Segment(16020, 17000, "А затем домой.")
        val result = Transcript.reconcileBoundary(listOf(Transcript.Segment(0, 16000, "Мы идём в парк.")),
            listOf(Transcript.Segment(15500, 15650, "В"), Transcript.Segment(15650, 15950, "парк."), next),
            15500, 17000, 16000)
        assertEquals(listOf(next), result)
    }
    @Test fun `a duplicate hypothesis alone cannot hide undecoded new audio`() {
        val result = Transcript.reconcileBoundary(listOf(Transcript.Segment(0, 16000, "Идём в парк.")),
            listOf(Transcript.Segment(15500, 15950, "В парк.")), 15500, 17000, 16000)
        assertEquals(listOf(Transcript.Segment(16000, 17000, "", uncertain = true)), result)
    }
    @Test fun `a conflicting hypothesis for old overlap stays visible for review`() {
        val alternate = Transcript.Segment(15500, 15950, "Идём в бар.")
        val next = Transcript.Segment(16020, 17000, "Потом домой.")
        assertEquals(listOf(alternate.copy(uncertain = true), next), Transcript.reconcileBoundary(
            listOf(Transcript.Segment(0, 16000, "Идём в парк.")), listOf(alternate, next), 15500, 17000, 16000))
    }
    @Test fun `a retained old audio hypothesis does not hide missing new speech`() {
        val alternate = Transcript.Segment(15500, 15950, "Different hypothesis.")
        assertEquals(listOf(alternate.copy(uncertain = true), Transcript.Segment(16000, 17000, "", uncertain = true)),
            Transcript.reconcileBoundary(listOf(Transcript.Segment(0, 16000, "We go home.")),
                listOf(alternate), 15500, 17000, 16000))
    }
    @Test fun `text timed only in decoder padding cannot become a zero length passage`() {
        assertEquals(listOf(Transcript.Segment(0, 200, "", uncertain = true)), Transcript.reconcileBoundary(
            emptyList(), listOf(Transcript.Segment(200, 1000, "Invented in padding")), 0, 200))
    }
    @Test fun `duration does not wrap after an hour`() {
        assertEquals("01:05", Transcript.time(65_000))
        assertEquals("2:03:04", Transcript.time(7_384_000))
    }
}
