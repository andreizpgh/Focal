package app.focal.data

import app.focal.core.AsrToken
import app.focal.core.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptJsonTest {
    @Test fun tokensRoundTripExactlyIncludingMissingScores() {
        val tokens = listOf(
            AsrToken(" Hello", 0.08, 0.32, -0.0123), AsrToken(",", 0.4, 0.04, null),
            AsrToken(" \"quoted\\\" \n\t\u0001", 1.0E-4, 0.0, -12.5), AsrToken(" ёлка", 12.48, 0.24, -1.0E-7),
        )
        assertEquals(tokens, TranscriptJson.decodeTokens(TranscriptJson.encodeTokens(tokens)))
        assertEquals(emptyList<AsrToken>(), TranscriptJson.decodeTokens(TranscriptJson.encodeTokens(emptyList())))
    }

    @Test fun nonFiniteScoresAreStoredAsUnknown() {
        val decoded = TranscriptJson.decodeTokens(TranscriptJson.encodeTokens(listOf(AsrToken(" a", 0.0, 0.1, Double.NEGATIVE_INFINITY))))
        assertNull(decoded.single().logProb)
    }

    @Test fun wordsRoundTripWithFlags() {
        val words = listOf(
            Word("Hello,", 80, 400, 0.98, false, 0), Word("—", 400, 440, null, true, 0),
            Word("Müller", 3_600_000_123, 3_600_000_500, 0.31, false, 41, unknownPiece = true),
        )
        assertEquals(words, TranscriptJson.decodeWords(TranscriptJson.encodeWords(words)))
    }

    @Test fun malformedJsonIsRejected() {
        for (bad in listOf("", "[", "[[\"a\",1,2]", "[[\"a\",1,2,3]] x", "[[\"a\",1,2,nan]]", "{}")) {
            assertThrows(IllegalArgumentException::class.java) { TranscriptJson.decodeTokens(bad) }
        }
    }
}

class SearchTextTest {
    @Test fun foldIsOneCharPerCharAndIgnoresCaseYoAndLatinMarks() {
        val text = "Ёлка ÉCOLE Straße Мой İstanbul"
        val folded = SearchText.fold(text)
        assertEquals(text.length, folded.length)
        assertEquals("елка ecole straße мой istanbul", folded)
    }

    @Test fun termsBecomeASafeMatchExpressionWithAPrefixForTheLastWord() {
        val terms = SearchText.terms("  Ёлка, \"OR\" (cafe*) ")
        assertEquals(listOf("елка", "or", "cafe"), terms)
        assertEquals("\"елка\" \"or\" \"cafe*\"", SearchText.matchExpression(terms))
        assertNull(SearchText.matchExpression(SearchText.terms(" -*() ")))
    }

    @Test fun firstHitFindsTheEarliestMatchingWord() {
        val terms = SearchText.terms("лошад")
        val text = "Ёжик в тумане ищет лошадку и лошадь."
        assertEquals(19..25, SearchText.firstHit(text, terms))
        assertEquals(0..3, SearchText.firstHit(text, SearchText.terms("ежик")))
        assertNull(SearchText.firstHit(text, SearchText.terms("медведь")))
    }

    @Test fun snippetCutsAtSpacesAroundTheHit() {
        val text = (1..60).joinToString(" ") { "word$it" }
        val hit = SearchText.firstHit(text, SearchText.terms("word30"))!!
        val (snippet, at) = SearchText.snippet(text, hit)
        assertEquals("word30", snippet.substring(at, at + 6))
        assertEquals('…', snippet.first())
        assertEquals('…', snippet.last())
        assertEquals("short text" to 0, SearchText.snippet("short text", null))
    }

    @Test fun occurrencesAndReplaceKeepOffsets() {
        val text = "Ёлка, ёлка и ЕЛКА; елкАёлка"
        val ranges = SearchText.occurrences(text, "елка")
        assertEquals(listOf(0..3, 6..9, 13..16, 19..22, 23..26), ranges)
        assertEquals("tree, tree и tree; treetree", SearchText.replace(text, ranges, "tree"))
        assertEquals(emptyList<IntRange>(), SearchText.occurrences(text, ""))
        assertEquals(listOf(0..1, 2..3), SearchText.occurrences("aaaa", "aa"))
    }
}

class ParagraphEditsTest {
    private fun words(vararg text: String) = text.mapIndexed { i, t -> Word(t, 1_000L * i, 1_000L * i + 800, 0.9, false, 0) }

    private fun content(text: String, words: List<Word>?) =
        ParagraphContent(text, words?.joinToString(" ") { it.text } ?: text, words, 0, 4_800, false)

    @Test fun splitDividesWordsAndSourceWhereTheTextWasDivided() {
        val source = words("One", "two.", "Three", "four", "five.")
        val (left, right) = ParagraphEdits.split(content("One two. Three four five.", source), 9)
        assertEquals("One two.", left.text)
        assertEquals("Three four five.", right.text)
        assertEquals("One two.", left.sourceText)
        assertEquals("Three four five.", right.sourceText)
        assertEquals(source.subList(0, 2), left.words)
        assertEquals(source.subList(2, 5), right.words)
        assertEquals(0L to 1_800L, left.startMs to left.endMs)
        assertEquals(2_000L to 4_800L, right.startMs to right.endMs)
        assertEquals(false to false, left.edited to right.edited)
    }

    @Test fun splittingEditedTextFollowsTheAlignment() {
        val source = words("One", "two.", "Three", "four", "five.")
        val edited = ParagraphContent("One 2. Three for five!", "One two. Three four five.", source, 0, 4_800, true)
        val (left, right) = ParagraphEdits.split(edited, edited.text.indexOf("Three"))
        assertEquals("One two.", left.sourceText)
        assertEquals("Three four five.", right.sourceText)
        assertEquals(listOf(true, true), listOf(left.edited, right.edited))
        // A split in the middle of a word gives that word's source to the right part.
        val (a, b) = ParagraphEdits.split(content("One two. Three four five.", source), 11)
        assertEquals("One two. Th", a.text)
        assertEquals("ree four five.", b.text)
        assertEquals(source.subList(0, 2), a.words)
        assertEquals(true to true, a.edited to b.edited)
    }

    @Test fun legacyTextWithoutTimesSplitsItsSourceButInventsNoTimes() {
        val legacy = ParagraphContent("Alpha beta. Gamma delta.", "Alpha beta. Gamma delta.", null, 1_000, 9_000, false)
        val (left, right) = ParagraphEdits.split(legacy, 12)
        assertEquals("Alpha beta." to "Gamma delta.", left.sourceText to right.sourceText)
        assertNull(left.words)
        assertEquals(1_000L to 9_000L, right.startMs to right.endMs)
        val unknown = ParagraphContent("Alpha beta. Gamma delta.", null, null, 1_000, 9_000, true)
        val (x, y) = ParagraphEdits.split(unknown, 12)
        assertNull(x.sourceText); assertNull(y.sourceText)
        assertEquals(true to true, x.edited to y.edited)
    }

    @Test fun splitAtTheEndLeavesAnEmptyNewParagraph() {
        val source = words("One", "two.")
        val (left, right) = ParagraphEdits.split(content("One two.", source), 8)
        assertEquals("One two.", left.text)
        assertEquals("", right.text)
        assertNull(right.words)
        // Known provenance stays known: the new paragraph has no recognizer text, which is not "unknown".
        assertEquals("", right.sourceText)
        assertEquals(false, right.edited)
    }

    @Test fun enterTypeAndBackspaceKeepsTheSource() {
        val source = words("Hello", "world.")
        val whole = content("Hello world.", source)
        val (left, empty) = ParagraphEdits.split(whole, whole.text.length)
        val typed = empty.copy(text = "My addition", edited = true)
        val merged = ParagraphEdits.merge(left, typed)
        assertEquals("Hello world. My addition", merged.text)
        assertEquals("Hello world.", merged.sourceText)
        assertEquals(source, merged.words)
        assertEquals(true, merged.edited)
    }

    @Test fun splittingOffAddedWordsAndMergingBackKeepsTheSource() {
        val source = words("Hello", "world.")
        val added = ParagraphContent("Hello world. My addition", "Hello world.", source, 0, 1_800, true)
        val (left, right) = ParagraphEdits.split(added, added.text.indexOf("My"))
        assertEquals("Hello world." to "", left.sourceText to right.sourceText)
        assertEquals(false to true, left.edited to right.edited)
        val merged = ParagraphEdits.merge(left, right)
        assertEquals(added.text, merged.text)
        assertEquals("Hello world.", merged.sourceText)
        assertEquals(true, merged.edited)
    }

    @Test fun mergeRestoresWhatSplitDivided() {
        val source = words("One", "two.", "Three", "four", "five.")
        val whole = content("One two. Three four five.", source)
        val (left, right) = ParagraphEdits.split(whole, 9)
        assertEquals(whole, ParagraphEdits.merge(left, right))
        val unknown = ParagraphEdits.merge(left, ParagraphContent("Legacy.", null, null, 9_000, 9_500, false))
        assertNull(unknown.sourceText)
        assertEquals(source.subList(0, 2), unknown.words)
        assertEquals("One two. Legacy.", unknown.text)
        assertEquals(0L to 9_500L, unknown.startMs to unknown.endMs)
    }

    @Test fun coverageListsEveryUncoveredRange() {
        val covered = listOf(Coverage.Range(2_000, 3_000), Coverage.Range(0, 1_000), Coverage.Range(2_500, 3_500))
        assertEquals(listOf(Coverage.Range(1_000, 2_000), Coverage.Range(3_500, 5_000)), Coverage.uncovered(covered, 5_000))
        assertEquals(listOf(Coverage.Range(0, 5_000)), Coverage.uncovered(emptyList(), 5_000))
        assertEquals(emptyList<Coverage.Range>(), Coverage.uncovered(listOf(Coverage.Range(0, 6_000)), 5_000))
        assertEquals(emptyList<Coverage.Range>(), Coverage.uncovered(emptyList(), 0))
    }
}
