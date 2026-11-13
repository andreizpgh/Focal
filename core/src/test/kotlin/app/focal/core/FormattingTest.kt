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
        // One word longer than the limit is the only title cut inside a word, and the only one with an ellipsis.
        val long = "😀".repeat(30)
        val title = Title.of(listOf(long))
        assertEquals("😀".repeat(17) + "…", title)
    }

    @Test
    fun aPreviewContinuesAfterTheWordsOfItsDerivedTitle() {
        val text = "So the plan for Saturday is simple. We meet at the trailhead at eight."
        assertEquals("So the plan for Saturday is simple", Title.of(TextWords.split(text)))
        assertEquals("We meet at the trailhead at eight.", Title.remainder(text))
        // A shortened title shows fewer words than its sentence: the preview picks up the rest of the sentence.
        val long = "Start with the recording screen because that is the moment people decide whether to trust it"
        val shown = Title.of(TextWords.split(long))
        assertEquals("Start with the recording screen", shown)
        assertEquals(long.removePrefix(shown).trimStart(), Title.remainder(long))
        assertEquals("", Title.remainder("Pick up the prescription before six."))
        assertEquals("", Title.remainder("   "))
    }

    @Test
    fun aLongFirstSentenceIsShortenedToWholeWordsWithoutAnEllipsis() {
        fun title(text: String) = Title.of(TextWords.split(text))
        // Whole words that fit, never an ellipsis.
        assertEquals("Сегодня мы наконец починили этот баг", title("Сегодня мы наконец починили этот баг с логином в приложении."))
        // Never left on a dangling function word.
        assertEquals("We looked at the cache", title("We looked at the cache and then at the network stack again."))
        assertEquals("Нужно не забыть позвонить", title("Нужно не забыть позвонить в страховую компанию и уточнить сроки."))
        // The last clause that fills at least half of the room wins over a longer run of words.
        assertEquals("If the build fails again", title("If the build fails again, the release waits for the next one."))
        assertEquals("Встреча перенесена на завтра", title("Встреча перенесена на завтра, в десять часов у главного входа."))
        // Nor on the correlative that a clause can end with.
        assertEquals("Сегодня я хотел поговорить", title("Сегодня я хотел поговорить о том, что нам нужно сделать до конца недели."))
        // A clause too short to be a title is passed over.
        assertEquals("So, we keep going over the numbers", title("So, we keep going over the numbers and the plan for the next quarter."))
        // A dash is a clause end, and never the last thing in a title.
        assertEquals("One thing about the release", title("One thing about the release - check the logs before you go home."))
        // At least two words stay, whatever they are.
        assertEquals("Но и", title("Но и в Константинопольско-Иерусалимском патриархате думают иначе."))
        // Nor on a bare number whose unit went with the rest.
        assertEquals("Wir treffen uns morgen", title("Wir treffen uns morgen am 3. oder am 4. Mai in Berlin."))
        // A sentence that fits is the title, whole.
        assertEquals("Wir treffen uns am 3. oder am 4. Mai", title("Wir treffen uns am 3. oder am 4. Mai. Dann sehen wir weiter."))
    }

    @Test
    fun aFunctionWordOfAnotherLanguageMayEndATitle() {
        fun title(text: String) = Title.of(TextWords.split(text))
        // "do" and "no" are Portuguese function words and English content words: the sentence's own words decide.
        assertEquals("This is something I will never do", title("This is something I will never do and I mean every word of it."))
        assertEquals("I have no idea what to do", title("I have no idea what to do about the release next week."))
        // In its own language the same word still never ends a title.
        assertEquals("Eu preciso falar com o gerente", title("Eu preciso falar com o gerente do banco sobre a conta nova."))
        // German "die" is an article there and a verb in English.
        assertEquals("Ich habe gestern mit dem Team", title("Ich habe gestern mit dem Team über die Planung gesprochen."))
        assertEquals("The old batteries in the shed die", title("The old batteries in the shed die when the winter gets cold."))
    }

    @Test
    fun aShortenedTitleAndItsRemainderCoverTheSentence() {
        val texts = listOf(
            "Сегодня я хотел поговорить о том, что нам нужно сделать до конца недели, и первым делом про ремонт.",
            "Remember to call the insurance company about the claim before the end of the month.",
            "Wir prüfen zuerst die Daten und danach den Bericht und alle weiteren Unterlagen dazu.",
        )
        for (text in texts) {
            val shown = Title.of(TextWords.split(text))
            assertTrue(shown.length <= SpeechTuning.TITLE_MAX_CHARS, shown)
            assertTrue(!shown.endsWith("…"), shown)
            val rest = Title.remainder(text)
            assertTrue(rest.isNotEmpty() && text.endsWith(rest), rest)
            // Nothing between them but the punctuation the title dropped.
            val between = text.removeSuffix(rest).trim().removePrefix(shown)
            assertTrue(between.all { it in ",;:–—- " }, "'$between'")
        }
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

    /** Sentences of [size] words, 350 ms a word, each followed by its pause in [pausesMs]. */
    private fun dictation(size: Int, pausesMs: List<Long>): List<Word> {
        val out = ArrayList<Word>()
        var at = 0L
        for ((sentence, pause) in pausesMs.withIndex()) {
            for (i in 0 until size) {
                val text = if (i == size - 1) "w$sentence." else "w$sentence"
                out += Word(text, at, at + 300, 0.9, punctOnly = false, window = 0)
                at += 350
            }
            at += pause
        }
        return out
    }

    @Test
    fun dictationWithoutLongPausesIsNotOneBlock() {
        // Twenty sentences a breath apart: no pause reaches a threshold, so the word limit alone closes paragraphs.
        val even = Paragraphs.split(dictation(10, List(20) { 600L }))
        assertTrue(even.size >= 3, "paragraphs ${even.size}")
        assertTrue(even.all { it.count() <= SpeechTuning.PARAGRAPH_MAX_WORDS + 10 }, "largest ${even.maxOf { it.count() }}")
    }

    @Test
    fun aPauseOfAThoughtClosesAParagraphAfterTwoSentences() {
        // The measured pause counts from the word's end: 50 ms more than what follows the sentence here.
        val thought = SpeechTuning.PARAGRAPH_PAUSE_MS
        val paragraphs = Paragraphs.split(dictation(8, listOf(500L, thought, 500L, 500L, thought, 500L)))
        assertEquals(listOf(0 until 16, 16 until 40, 40 until 48), paragraphs)
        // One sentence and the same pause: not yet a paragraph of its own; the long pause makes it one.
        assertEquals(listOf(0 until 16), Paragraphs.split(dictation(8, listOf(thought, 500L))))
        assertEquals(listOf(0 until 8, 8 until 16), Paragraphs.split(dictation(8, listOf(SpeechTuning.LONG_PAUSE_MS, 500L))))
    }
}
