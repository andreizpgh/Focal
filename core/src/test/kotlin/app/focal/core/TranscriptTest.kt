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
    @Test fun `duration does not wrap after an hour`() {
        assertEquals("01:05", Transcript.time(65_000))
        assertEquals("2:03:04", Transcript.time(7_384_000))
    }
}
