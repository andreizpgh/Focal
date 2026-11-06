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
    fun everyUnicodeSpaceSeparatesWords() {
        val source = listOf(Word("мир!", 0, 400, 0.9, false, 0), Word("STRASSE", 500, 900, 0.3, false, 0),
            Word("Hello,", 1_000, 1_400, 0.9, false, 0))
        // No-break space, thin space and ideographic space, as pasted or typed on a phone keyboard.
        val edited = "мир!\u00A0STRASSE\u2009Hello,\u3000"
        val aligned = Alignment.align(source, edited)
        assertEquals(listOf("мир!", "STRASSE", "Hello,"), aligned.map { it.text })
        assertEquals(listOf(0L, 500L, 1_000L), aligned.map { it.startMs })
        assertEquals(listOf(false, true, false), aligned.map { it.doubtful })
        assertEquals(listOf(1..1, 3..4), TextWords.ranges("\u202Fa\tbc\n"))
        assertEquals(listOf("a", "b"), TextWords.split(" a\u00A0\u00A0b "))
        assertTrue(TextWords.ranges("\u00A0 \u2007").isEmpty())
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
    fun aPreviewContinuesAfterTheWordsOfItsDerivedTitle() {
        val text = "So the plan for Saturday is simple. We meet at the trailhead at eight."
        assertEquals("So the plan for Saturday is simple", Title.of(TextWords.split(text)))
        assertEquals("We meet at the trailhead at eight.", Title.remainder(text))
        // A clipped title shows fewer words than its sentence: the preview picks up the rest of the sentence.
        val long = "Start with the recording screen because that is the moment people decide whether to trust it"
        val clipped = Title.of(TextWords.split(long))
        assertTrue(clipped.endsWith("…"))
        val shown = clipped.removeSuffix("…").trimEnd()
        assertEquals(long.removePrefix(shown).trimStart(), Title.remainder(long))
        assertEquals("", Title.remainder("Pick up the prescription before six."))
        assertEquals("", Title.remainder("   "))
    }

    /**
     * Four hours of speech the recognizer never punctuated (35,000 words over 14,000 s, in 20 s windows) once
     * made one open paragraph whose stored words outgrew a cursor window. Every paragraph now closes at the cap,
     * at the longest pause near its end, and the worker's window-by-window assembly gives the same paragraphs.
     */
    @Test
    fun speechWithoutSentenceEndsNeverGrowsOneUnboundedParagraph() {
        val config = FormatConfig()
        val vocabulary = listOf(" and", " then", " we", " keep", " talking", " about", " the", " plan", " for", " it")
        val windows = (0 until 700).map { w ->
            // 50 words per 20 s window; every 7th gap is a longer breath, so cuts land on pauses.
            DecodedWindow(w * 20_000L, null, null, (0 until 50).map { k ->
                val n = w * 50 + k
                AsrToken(vocabulary[n % vocabulary.size], 0.1 + k * 0.38 + (k / 7) * 0.06 + if (n % 13 == 0) 0.02 else 0.0, 0.16, -0.01)
            })
        }
        val batch = Formatter.format(windows, config)
        assertEquals(35_000, batch.words.size)
        assertTrue(batch.words.last().endMs > 13_990_000)
        assertTrue(batch.words.none { TextRules.endsSentence(it.text) })
        val sizes = batch.paragraphs.map { it.count() }
        assertTrue(sizes.all { it <= config.capWords }, "largest paragraph ${sizes.max()}")
        assertTrue(sizes.dropLast(1).all { it >= config.capWords - config.capLookbackWords }, "smallest closed ${sizes.dropLast(1).min()}")
        // Each cut is the longest pause in its range (the latest of equal ones).
        var start = 0
        for (range in batch.paragraphs.dropLast(1)) {
            val cut = range.last + 1
            val reach = start + config.capWords
            val pauses = (maxOf(start + 1, reach - config.capLookbackWords)..reach).associateWith {
                batch.words[it].startMs - batch.words[it - 1].endMs
            }
            assertEquals(pauses.entries.last { it.value == pauses.values.max() }.key, cut)
            start = cut
        }

        var open = emptyList<Word>()
        val assembled = ArrayList<List<Word>>()
        windows.forEachIndexed { index, window ->
            val update = TranscriptAssembler(config, open).append(window, index)
            assembled += update.paragraphs.dropLast(1)
            open = update.paragraphs.last()
            assertTrue(open.size <= config.capWords, "window $index open ${open.size}")
        }
        assembled += open
        assertEquals(batch.paragraphs.map { r -> r.map { batch.words[it] } }, assembled)

        // A stored open paragraph from before the cap closes at the next word, near its end.
        val oversized = batch.words.subList(0, 1_000)
        val next = DecodedWindow(batch.words[999].endMs + 400, null, null, listOf(AsrToken(" more", 0.1, 0.16, -0.01)))
        val update = TranscriptAssembler(config, oversized).append(next, 700)
        assertEquals(2, update.paragraphs.size)
        assertEquals(oversized + update.paragraphs.last().last(), update.paragraphs.flatten())
        assertEquals("more", update.paragraphs.last().last().text)
        assertTrue(update.paragraphs.last().size <= config.capLookbackWords + 1)
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
