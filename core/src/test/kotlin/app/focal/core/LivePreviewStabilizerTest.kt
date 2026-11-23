package app.focal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePreviewStabilizerTest {
    private fun word(text: String, startMs: Long, endMs: Long, confidence: Double = 0.92, unknown: Boolean = false) =
        Word(text = text, startMs = startMs, endMs = endMs, confidence = confidence, punctOnly = false, window = 0, unknownPiece = unknown)

    @Test fun trimsTrailingHalfSpokenWordWhileSpeaking() {
        val stabilizer = LivePreviewStabilizer()
        val words = listOf(
            word("We", 0, 300),
            word("meet", 350, 700),
            word("at", 750, 1000),
            word("eight", 1100, 1600),
            word("garbl", 2400, 2750), // within 450 ms of tailEnd=2800
        )
        assertEquals("We meet at eight", stabilizer.accept(0L, 2800L, words, paused = false))
    }

    @Test fun withholdsLowConfidenceEarlyGuessUntilContextStabilizes() {
        val stabilizer = LivePreviewStabilizer()
        val noisy = listOf(
            word("kto", 0, 400, confidence = 0.25),
            word("tam", 500, 900, confidence = 0.30),
            word("byl", 1000, 1400, confidence = 0.35),
        )
        assertNull(stabilizer.accept(0L, 2800L, noisy, paused = false))

        val clear = listOf(
            word("Note", 0, 400, confidence = 0.94),
            word("for", 500, 800, confidence = 0.91),
            word("tomorrow", 900, 1500, confidence = 0.89),
            word("morning", 1600, 2200, confidence = 0.90),
        )
        assertEquals("Note for tomorrow morning", stabilizer.accept(0L, 4800L, clear, paused = false))
    }

    @Test fun revealsCompleteShortPhrasePromptlyOnBriefPause() {
        val stabilizer = LivePreviewStabilizer()
        val phrase = listOf(
            word("Buy", 200, 600, confidence = 0.88),
            word("milk", 650, 1200, confidence = 0.90),
        )
        // Only 2 words: withheld mid-speech, shown cleanly once the speaker pauses.
        assertNull(stabilizer.accept(0L, 2000L, phrase, paused = false))
        assertEquals("Buy milk", stabilizer.accept(0L, 2200L, phrase, paused = true))
    }

    @Test fun keepsShownTextStableInsteadOfThrashingOnNoisyRefresh() {
        val stabilizer = LivePreviewStabilizer()
        val first = listOf(
            word("Start", 0, 400),
            word("with", 450, 800),
            word("the", 850, 1100),
            word("design", 1150, 1700),
        )
        assertEquals("Start with the design", stabilizer.accept(0L, 3000L, first, paused = false))

        // A transient noisy refresh must not overwrite or erase already-shown calm text.
        val noisy = listOf(
            word("Star", 0, 400, confidence = 0.20),
            word("vid", 450, 800, confidence = 0.20),
            word("de", 850, 1100, confidence = 0.30),
            word("sine", 1150, 1700, confidence = 0.25),
        )
        assertEquals("Start with the design", stabilizer.accept(0L, 5000L, noisy, paused = false))

        // Next confident refresh extends smoothly forward.
        val extended = first + listOf(
            word("review", 1800, 2400, confidence = 0.91),
            word("today", 2500, 3100, confidence = 0.90),
        )
        assertEquals("Start with the design review today", stabilizer.accept(0L, 6000L, extended, paused = false))
    }

    @Test fun resetsCleanlyOnNewOpenWindow() {
        val stabilizer = LivePreviewStabilizer()
        val first = listOf(word("First", 0, 400), word("window", 500, 1000), word("text", 1100, 1600))
        assertEquals("First window text", stabilizer.accept(0L, 2800L, first, paused = false))

        val nextWindowNoisy = listOf(word("uh", 6000, 6400, confidence = 0.20))
        assertNull(stabilizer.accept(6000L, 8800L, nextWindowNoisy, paused = false))
    }
}
