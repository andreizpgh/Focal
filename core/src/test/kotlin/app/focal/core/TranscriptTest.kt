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
    @Test fun `a manual NUL correction stops context without losing a newer trusted tail`() {
        val correction = Transcript.Segment(1000, 2000, "Preserve\u0000this manual correction")
        val previous = listOf(Transcript.Segment(0, 1000, "Earlier unrelated words"), correction,
            Transcript.Segment(2000, 3000, "Последние слова 🙂"))
        val context = Transcript.trustedContext(previous, 3100)
        assertEquals("Последние слова 🙂", context)
        assertFalse('\u0000' in context)
        assertEquals("", Transcript.trustedContext(previous.take(2), 2100))
        assertEquals("Preserve\u0000this manual correction", correction.text)
    }
    @Test fun `legacy multi megabyte corrections keep only the needed overlap tail across passages`() {
        val legacy = "old ".repeat(500_000) + "До свидания"
        val previous = listOf(Transcript.Segment(0, 15700, legacy),
            Transcript.Segment(15700, 16000, "«Кафе» e\u0301lan FINISH"))
        val next = Transcript.Segment(16020, 17000, "Дальше домой.")
        val decoded = listOf(Transcript.Segment(15500, 15600, "До"),
            Transcript.Segment(15600, 15700, "свидания"),
            Transcript.Segment(15700, 15950, "кафе ÉLAN finish."), next)
        assertEquals(listOf(next), Transcript.reconcileBoundary(previous, decoded, 15500, 17000, 16000))
        assertTrue(legacy.endsWith("До свидания"))
    }
    @Test fun `bounded overlap preserves Unicode normalization and regex word boundaries`() {
        assertEquals("дальше", Transcript.removeOverlap("Preceding «Кафе» e\u0301lan.", "кафе ÉLAN, дальше"))
        assertEquals("one two more", Transcript.removeOverlap("one\u00a0two", "one two more"))
    }
    @Test fun `oversized words retain ambiguous speech for review without normalization`() {
        val allowed = "a".repeat(4096)
        assertEquals("continue", Transcript.removeOverlap("Before $allowed again", "$allowed again continue"))
        val giant = "Ж".repeat(4097)
        val hypothesis = "$giant again continue"
        assertEquals(hypothesis, Transcript.removeOverlap("Before $giant again", hypothesis))
        val previous = listOf(Transcript.Segment(0, 16000, "Before $giant"))
        val oldAudio = Transcript.Segment(15500, 15950, giant)
        assertEquals(listOf(oldAudio.copy(uncertain = true), Transcript.Segment(16000, 17000, "", true)),
            Transcript.reconcileBoundary(previous, listOf(oldAudio), 15500, 17000, 16000))
        val mixed = Transcript.Segment(15500, 17000, "$giant again")
        assertEquals(listOf(mixed.copy(uncertain = true)),
            Transcript.reconcileBoundary(previous, listOf(mixed), 15500, 17000, 16000))
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
    @Test fun `automatic titles retain complete emoji within the UTF16 limit`() {
        assertEquals("a".repeat(71), Transcript.title("a".repeat(71) + "🙂"))
        assertEquals("a".repeat(70) + "🙂", Transcript.title("a".repeat(70) + "🙂tail"))
        assertEquals("a".repeat(70) + "🙂", Transcript.title("a".repeat(70) + "🙂"))
        assertEquals("a".repeat(72), Transcript.title("a".repeat(73)))
        assertEquals("one two three four five six seven", Transcript.title(" one  two three four five six seven eight "))
        assertEquals("Untitled note", Transcript.title(" "))
    }
    @Test fun `manual title prefix keeps the UTF16 cap without splitting emoji`() {
        assertEquals("a".repeat(119), Transcript.truncateUtf16("a".repeat(119) + "🙂", 120))
        assertEquals("a".repeat(118) + "🙂", Transcript.truncateUtf16("a".repeat(118) + "🙂tail", 120))
        assertEquals("a".repeat(118) + "🙂", Transcript.truncateUtf16("a".repeat(118) + "🙂", 120))
        assertEquals("a".repeat(120), Transcript.truncateUtf16("a".repeat(121), 120))
        assertEquals("🙂", Transcript.truncateUtf16("🙂", 120))
        assertEquals("", Transcript.truncateUtf16("🙂", 1))
        assertEquals("", Transcript.truncateUtf16("🙂", 0))
        assertEquals("\uD83Dx", Transcript.truncateUtf16("\uD83Dx", 120))
        assertFailsWith<IllegalArgumentException> { Transcript.truncateUtf16("", -1) }
    }
}
