package app.focal.ui.note

import app.focal.ui.state.GapUi
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteItemsTest {
    @Test fun gapsFollowTheParagraphBeforeThem() {
        val paragraphs = listOf(paragraph(1, "One."), paragraph(2, "Two."))
        val gaps = listOf(GapUi(0, 900, null), GapUi(1500, 1900, 1), GapUi(5000, 6000, 9))
        val items = noteItems(paragraphs, gaps, writing = true, noSpeech = false).map { it.key }
        assertEquals(listOf("head", "gap:0", 1L, "gap:1500", 2L, "gap:5000", "writing"), items)
    }

    @Test fun aNoteWithoutWordsSaysSoInsteadOfTheWritingHead() {
        val items = noteItems(emptyList(), emptyList(), writing = false, noSpeech = true).map { it.key }
        assertEquals(listOf("head", "no-speech"), items)
    }

    @Test fun timesHeadEveryParagraphAfterTheFirst() {
        val paragraphs = listOf(paragraph(1, "One."), paragraph(2, "Two."), paragraph(3, "Three."))
        val items = noteItems(paragraphs, listOf(GapUi(1500, 1900, 1)), writing = false, noSpeech = false, times = true).map { it.key }
        assertEquals(listOf("head", 1L, "gap:1500", timeKey(2), 2L, timeKey(3), 3L), items)
        // A note of one paragraph starts where the note does: no time.
        assertEquals(listOf("head", 1L), noteItems(paragraphs.take(1), emptyList(), writing = false, noSpeech = false, times = true).map { it.key })
    }
}
