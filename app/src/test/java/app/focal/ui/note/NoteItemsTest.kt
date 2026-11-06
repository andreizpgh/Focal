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
}
