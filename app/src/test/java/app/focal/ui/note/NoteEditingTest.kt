package app.focal.ui.note

import app.focal.data.TextMatch
import app.focal.ui.library.DatePatterns
import app.focal.ui.library.DayWords
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.SpokenDateline
import app.focal.ui.state.ParagraphUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

internal fun paragraph(id: Long, text: String, revision: Long = 1) = ParagraphUi(
    id = id, revision = revision, text = text, startMs = id * 1000, endMs = id * 1000 + 999, words = emptyList(),
    exactTimes = false, edited = false, canRestore = false, open = false,
)

class FindStateTest {
    private fun match(paragraph: Long, start: Int) = TextMatch(paragraph, start, start + 6)

    @Test fun theCurrentMatchStartsAtTheReadingPositionAndWraps() {
        val find = FindState()
        val found = listOf(match(1, 0), match(2, 4), match(3, 0))
        find.show("screen", found) { it.paragraphId >= 2 }
        assertEquals(match(2, 4), find.currentMatch)
        find.next()
        find.next()
        assertEquals(match(1, 0), find.currentMatch)
        find.previous()
        assertEquals(match(3, 0), find.currentMatch)
        assertEquals(listOf(4 until 10), find.matchesIn(2))
    }

    @Test fun theSameQueryAgainKeepsThePlace() {
        val find = FindState()
        find.show("screen", listOf(match(1, 0), match(2, 4), match(3, 0))) { true }
        find.next()
        find.show("screen", listOf(match(2, 4), match(3, 0))) { true }
        assertEquals(match(2, 4), find.currentMatch)
        find.show("screen", emptyList()) { true }
        assertTrue(find.none)
        assertNull(find.currentMatch)
    }
}

class NoteFormatTest {
    @Test fun playerTimesAreMinutesThenHours() {
        assertEquals("0:00", NoteTimes.clock(0))
        assertEquals("3:08", NoteTimes.clock(188_400))
        assertEquals("12:04", NoteTimes.clock(724_000))
        assertEquals("1:02:03", NoteTimes.clock(3_723_000))
    }

    @Test fun theAdjustablePlayerMovesFiveSecondsPerStep() {
        val duration = 24 * 60_000L + 2_000
        val end = PlayerSteps.rangeEnd(duration)
        val steps = PlayerSteps.steps(duration)
        assertEquals(PlayerSteps.STEP_MS.toFloat(), end / (steps + 1))
        assertTrue(end >= duration)
        assertEquals(0, PlayerSteps.steps(1_000))
    }

    @Test fun followKeepsThePlayingLineInTheMiddleThird() {
        // Band 100..400: the middle third is 200..300.
        assertEquals(0f, Follow.distance(220f, 248f, 100f, 400f))
        assertEquals(110f, Follow.distance(310f, 338f, 100f, 400f))
        assertEquals(-50f, Follow.distance(150f, 178f, 100f, 400f))
    }

    @Test fun aLineAcrossTheTopFadeLandsAnEdgeOnTheBand() {
        // The band's solid paper ends at 100, its fade at 116; lines are 28 tall.
        val paragraph = listOf(Span(80f, 108f), Span(108f, 136f), Span(136f, 164f))
        // Nothing crosses: the scroll stands.
        assertEquals(0f, TopEdge.snap(0f, paragraph.map { it.shifted(-20f) }, 100f, 16f, null, 0f, 1000f))
        // The first line crosses the band: the nearest edge lands on it (8 up hides it).
        assertEquals(8f, TopEdge.snap(0f, paragraph, 100f, 16f, null, 0f, 1000f))
        // Where the brought line may not rise past 130, the other way: the line shows whole from the band down.
        assertEquals(-20f, TopEdge.snap(0f, paragraph, 100f, 16f, Span(136f, 164f), 130f, 1000f))
        // A paragraph that starts inside the fade, the gap above it on the band: the last line above lands instead.
        val gap = listOf(Span(60f, 88f), Span(104f, 132f))
        assertEquals(4f, TopEdge.snap(0f, gap, 100f, 16f, null, 0f, 1000f))
        assertEquals(-12f, TopEdge.snap(0f, gap, 100f, 16f, Span(200f, 228f), 200f, 1000f))
        // A planned scroll is adjusted from where it would leave the lines: 20 would cut the second line, so 8.
        assertEquals(8f, TopEdge.snap(20f, paragraph, 100f, 16f, null, 0f, 1000f))
        // No way to keep the brought line where it must stay: the scroll stands.
        assertEquals(0f, TopEdge.snap(0f, paragraph, 100f, 16f, Span(136f, 164f), 136f, 164f))
    }

    @Test fun editMovesThePageOnlyWhenTheCaretIsOutOfItsBand() {
        // The band between the bar and the keyboard is 200..600; the caret's line is 28 tall.
        assertEquals(0f, caretScroll(300f, 328f, 200f, 600f))
        assertEquals(0f, caretScroll(200f, 228f, 200f, 600f))
        // Under the keyboard: up by what brings its bottom to the band's; above the bar: down to the band's top.
        assertEquals(40f, caretScroll(612f, 640f, 200f, 600f))
        assertEquals(-50f, caretScroll(150f, 178f, 200f, 600f))
        // A line taller than the band (a very large font in a short window) keeps its top in view.
        assertEquals(10f, caretScroll(210f, 700f, 200f, 600f))
    }

    @Test fun aTapSelectsTheWordWithoutItsPunctuation() {
        val text = "Плитку привезут в понедельник, но — затирку выбрать."
        assertEquals(18 until 29, wordIn(text, 20))
        // On the comma that hangs on the word, the word still; at its very end too.
        assertEquals(18 until 29, wordIn(text, 29))
        assertEquals(18 until 29, wordIn(text, 30))
        // A dash on its own is not a word, nor is the space beside it.
        assertNull(wordIn(text, 35))
        assertEquals(0 until 6, wordIn(text, 0))
        assertNull(wordIn("", 0))
    }

    @Test fun theMetaOpensWithAShortDateAndTime() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 6)
        val dates = LibraryDates(
            Locale.UK, zone, DatePatterns("HH:mm", "EEE", "EEEE", "d MMM", "d MMM y", "d MMMM", "d MMMM y"),
            DayWords("Today", "Yesterday"),
        )
        val dateline = SpokenDateline({ "Today, $it" }, { "Yesterday, $it" }, { date, time -> "$date, $time" })
        fun at(day: LocalDate) = LocalDateTime.of(day, LocalTime.of(9, 12)).toInstant(zone).toEpochMilli()
        assertEquals("Today, 09:12", dates.noteWhen(at(today), today, dateline))
        assertEquals("Yesterday, 09:12", dates.noteWhen(at(today.minusDays(1)), today, dateline))
        assertEquals("Mon, 09:12", dates.noteWhen(at(LocalDate.of(2026, 11, 2)), today, dateline))
        assertEquals("28 Oct, 09:12", dates.noteWhen(at(LocalDate.of(2026, 10, 28)), today, dateline))
        assertEquals("28 Oct 2025, 09:12", dates.noteWhen(at(LocalDate.of(2025, 10, 28)), today, dateline))
    }
}
