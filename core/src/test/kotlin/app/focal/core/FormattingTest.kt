package app.focal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormattingTest {
    private fun words(vararg texts: String): List<Word> = texts.mapIndexed { i, t ->
        Word(t, 1_000L * i, 1_000L * i + 400, if (t.startsWith("Pa")) 0.3 else 0.9, punctOnly = false, window = 0)
    }

    @Test
    fun alignmentKeepsTimesOfUnchangedWordsOnly() {
        val source = words("We", "met", "Pablo", "in", "Madrid.")
        val aligned = Alignment.align(source, "We  met paolo in sunny Madrid!")
        assertEquals(listOf("We", "met", "paolo", "in", "sunny", "Madrid!"), aligned.map { it.text })
        assertEquals(listOf(0L, 1_000L, null, 3_000L, null, 4_000L), aligned.map { it.startMs })
        assertEquals(listOf(0, 1, null, 3, null, 4), aligned.map { it.sourceIndex })
        // 'Madrid!' matched by its letters: time kept. Doubt survives only on literally unchanged words.
        assertEquals(listOf(false, false, false, false, false, false), aligned.map { it.doubtful })
        assertEquals(listOf(true), Alignment.align(source, "We met Pablo").filter { it.text == "Pablo" }.map { it.doubtful })
        assertEquals(listOf(false), Alignment.align(source, "We met pablo").filter { it.text == "pablo" }.map { it.doubtful })
        assertEquals(4, aligned[1].start)
        assertEquals(7, aligned[1].end)
    }

    @Test
    fun alignmentHandlesDeletionsMovesAndEmptyText() {
        val source = words("one", "two", "three", "four", "five")
        assertEquals(listOf(0, 1, 3, 4), Alignment.align(source, "one two four five").map { it.sourceIndex })
        // Reordered words: a longest common subsequence keeps only one of them in order.
        assertEquals(1, Alignment.align(source, "five four three two one").count { it.sourceIndex != null })
        assertTrue(Alignment.align(source, "   ").isEmpty())
        assertTrue(Alignment.align(emptyList(), "new text").all { it.startMs == null })
    }

    @Test
    fun alignmentOfAHugeRewriteStaysBounded() {
        val source = (0 until 3_000).map { Word("w$it", it * 10L, it * 10L + 5, 1.0, false, 0) }
        val edited = "start " + (0 until 3_000).joinToString(" ") { "x$it" } + " w2999"
        val aligned = Alignment.align(source, edited)
        assertEquals(3_002, aligned.size)
        assertEquals(2_999, aligned.last().sourceIndex) // the common suffix still matches
        assertTrue(aligned.dropLast(1).all { it.sourceIndex == null })
    }

    @Test
    fun seekUsesTheNearestEarlierTimedWord() {
        val source = words("alpha", "beta", "gamma")
        val aligned = Alignment.align(source, "alpha BETTER gamma")
        assertEquals(0L, Alignment.seekMs(aligned, 0))
        assertEquals(0L, Alignment.seekMs(aligned, 8)) // inside the changed word: play from 'alpha'
        assertEquals(2_000L, Alignment.seekMs(aligned, 14))
        assertNull(Alignment.seekMs(Alignment.align(source, "new start alpha"), 2))
    }

    @Test
    fun doubtCanBeSwitchedOff() {
        val doubtful = Word("Pablo", 0, 1, 0.3, false, 0)
        assertTrue(Doubt.isDoubtful(doubtful))
        assertEquals(emptyList(), Doubt.flagged(listOf(doubtful), enabled = false))
        assertTrue(!Doubt.isDoubtful(doubtful.copy(confidence = null)))
        assertTrue(Doubt.isDoubtful(doubtful.copy(confidence = null, unknownPiece = true)))
        assertTrue(!Doubt.isDoubtful(doubtful.copy(punctOnly = true)))
    }

    @Test
    fun titleNeverSplitsASurrogatePair() {
        val long = "😀".repeat(30)
        val title = Title.of(listOf(long))
        assertEquals("😀".repeat(23) + "…", title)
    }

    @Test
    fun assemblerStartsANewParagraphAfterLegacyText() {
        val window = DecodedWindow(0, null, null, listOf(AsrToken(" Hello", 0.24, 0.16, 0.0), AsrToken(".", 0.4, 0.08, 0.0)))
        val update = TranscriptAssembler().append(window, 3)
        assertEquals(listOf(listOf("Hello.")), update.paragraphs.map { p -> p.map { it.text } })
        assertEquals(3, update.paragraphs[0][0].window)
        assertEquals(emptyList(), TranscriptAssembler().append(DecodedWindow(0, null, null, emptyList()), 0).paragraphs)
    }
}
