package app.focal.ui.library

import app.focal.ui.components.DockState
import app.focal.ui.state.LibraryDay
import app.focal.ui.state.LibraryUiState
import app.focal.ui.state.LiveParagraph
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteRow
import app.focal.ui.state.PauseReason
import app.focal.ui.state.RecordingPhase
import app.focal.ui.state.RecordingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class LibraryDatesTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 6)
    private val dates = LibraryDates(
        Locale.UK, zone,
        DatePatterns("HH:mm", "EEE", "EEEE", "d MMM", "d MMM y", "d MMMM", "d MMMM y"),
        DayWords("Today", "Yesterday"),
    )
    private fun at(day: LocalDate, hour: Int = 16, minute: Int = 5) =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).toInstant(zone).toEpochMilli()
    private val dateline = SpokenDateline({ "Today, $it" }, { "Yesterday, $it" }, { date, time -> "$date, $time" })

    @Test fun theStageTimeIsBareForTodayAndShortensWithAge() {
        assertEquals("16:05", dates.stageWhen(at(today), today))
        assertEquals("Yesterday", dates.stageWhen(at(today.minusDays(1)), today))
        assertEquals("Mon", dates.stageWhen(at(LocalDate.of(2026, 11, 2)), today))
        assertEquals("28 Oct", dates.stageWhen(at(LocalDate.of(2026, 10, 28)), today))
        assertEquals("28 Oct 2025", dates.stageWhen(at(LocalDate.of(2025, 10, 28)), today))
    }

    @Test fun dayLabelsNameTheLastWeekThenTheDate() {
        assertEquals("Today", dates.dayLabel(today.toEpochDay(), today))
        assertEquals("Yesterday", dates.dayLabel(today.minusDays(1).toEpochDay(), today))
        assertEquals("Monday", dates.dayLabel(LocalDate.of(2026, 11, 2).toEpochDay(), today))
        assertEquals("Saturday", dates.dayLabel(LocalDate.of(2026, 10, 31).toEpochDay(), today))
        assertEquals("28 October", dates.dayLabel(LocalDate.of(2026, 10, 28).toEpochDay(), today))
        assertEquals("3 November 2025", dates.dayLabel(LocalDate.of(2025, 11, 3).toEpochDay(), today))
    }

    @Test fun searchRunInsAndSpokenFormsFollowTheSameDays() {
        assertEquals("Today", dates.searchDay(at(today), today))
        assertEquals("Mon", dates.searchDay(at(LocalDate.of(2026, 11, 2)), today))
        assertEquals("3 Nov 2025", dates.searchDay(at(LocalDate.of(2025, 11, 3)), today))
        assertEquals("Today, 16:05", dates.spoken(at(today), today, dateline))
        assertEquals("Monday, 09:12", dates.spoken(at(LocalDate.of(2026, 11, 2), 9, 12), today, dateline))
        assertEquals("28 October 2025, 16:05", dates.spoken(at(LocalDate.of(2025, 10, 28)), today, dateline))
    }
}

class LengthsTest {
    @Test fun lengthsAreNeverMinutesAndSeconds() {
        assertEquals(listOf(LengthPart(42, LengthUnit.Seconds)), Lengths.parts(42_000))
        assertEquals(listOf(LengthPart(12, LengthUnit.Minutes)), Lengths.parts((12 * 60 + 4) * 1000L))
        assertEquals(listOf(LengthPart(1, LengthUnit.Minutes)), Lengths.parts(59_600))
        assertEquals(listOf(LengthPart(1, LengthUnit.Hours), LengthPart(12, LengthUnit.Minutes)), Lengths.parts(72 * 60_000L))
        assertEquals(listOf(LengthPart(2, LengthUnit.Hours)), Lengths.parts(120 * 60_000L))
    }

    @Test fun theTimerReservesItsFormatsWidth() {
        assertEquals("0:00", Lengths.timer(0))
        assertEquals("12:48", Lengths.timer(12 * 60 + 48))
        assertEquals("1:02:03", Lengths.timer(3723))
        assertEquals("0:00", Lengths.timerTemplate(599))
        assertEquals("00:00", Lengths.timerTemplate(600))
        assertEquals("0:00:00", Lengths.timerTemplate(3600))
        assertEquals(Triple(1L, 2L, 3L), Lengths.spokenParts(3723))
    }
}

class WordCutTest {
    @Test fun aCutPreviewEndsOnAWholeWordWithoutTrailingPunctuation() {
        val text = "whether to trust the app at all. Then the rest"
        // The last fitting line ends inside "Then": back to "all", and the full stop goes.
        assertEquals("whether to trust the app at all", text.substring(0, WordCut.cut(text, text.indexOf("hen"))!!))
        // Mid-word: "the way h" is cut back to "the way".
        val way = "counts them the way her father did"
        assertEquals("counts them the way", way.substring(0, WordCut.cut(way, way.indexOf("her") + 1)!!))
        assertEquals("counts them the", way.substring(0, WordCut.previousWord(way, way.indexOf(" her"))!!))
        assertNull(WordCut.cut("word", 2))
    }
}

class LibraryModelsTest {
    private fun row(id: String, created: Long, phase: NotePhase = NotePhase.READY, title: String = "Title $id", preview: String = "Text") =
        NoteRow(id, title, false, created, 60_000, preview, phase, null, audioPresent = true, failedParts = 0)
    private fun library(vararg days: List<NoteRow>) =
        LibraryUiState(loaded = true, days = days.mapIndexed { i, rows -> LibraryDay(100L - i, rows) })

    @Test fun theNewestNoteTakesTheStageAndLeavesTheList() {
        val model = LibraryModels.of(library(listOf(row("a", 3), row("b", 2)), listOf(row("c", 1))), RecordingUiState(), emptySet())
        assertEquals("a", (model.stage as StageUi.Note).row.id)
        assertEquals(listOf(listOf("b"), listOf("c")), model.days.map { day -> day.rows.map { it.id } })
        assertEquals(DockState.LibraryIdle, model.dock)
    }

    @Test fun aNoteBecomingTextTakesTheStageBeforeNewerReadyOnes() {
        val model = LibraryModels.of(library(listOf(row("a", 3), row("b", 2, NotePhase.TRANSCRIBING))), RecordingUiState(), setOf("b"))
        val stage = model.stage as StageUi.Note
        assertEquals("b", stage.row.id)
        assertTrue(stage.fresh)
        assertEquals(listOf("a"), model.days.single().rows.map { it.id })
    }

    @Test fun aLiveCaptureTakesTheStageAndKeepsEveryNoteInTheList() {
        val recording = RecordingUiState(
            phase = RecordingPhase.PAUSED, noteId = "live", elapsedSeconds = 768, pauseReason = PauseReason.STORAGE,
            paragraphs = listOf(LiveParagraph(1, "Hello", open = true)),
        )
        val model = LibraryModels.of(library(listOf(row("a", 3), row("b", 2))), recording, emptySet())
        val live = model.stage as StageUi.Live
        assertTrue(live.paused && live.storageFull)
        // Two notes before it: the live one is among the first three recordings, so the hint may show.
        assertTrue(live.hint)
        assertEquals(listOf("a", "b"), model.days.single().rows.map { it.id })
        assertEquals(DockState.Live(paused = true), model.dock)
    }

    @Test fun anEmptyLibraryHasNoStageAndRecordAlone() {
        val model = LibraryModels.of(LibraryUiState(loaded = true), RecordingUiState(), emptySet())
        assertTrue(model.empty)
        assertNull(model.stage)
        assertEquals(DockState.LibraryEmpty, model.dock)
        assertFalse(LibraryModels.of(LibraryUiState(), RecordingUiState(), emptySet()).empty)
    }

    @Test fun noSpeechAndCopyRules() {
        assertTrue(LibraryModels.noSpeech(row("a", 1, title = "", preview = "")))
        assertFalse(LibraryModels.copyable(row("a", 1, title = "", preview = "")))
        assertTrue(LibraryModels.copyable(row("a", 1, NotePhase.PARTIAL_FAILURE)))
        assertFalse(LibraryModels.copyable(row("a", 1, NotePhase.TRANSCRIBING)))
        assertEquals(listOf(MenuItem.Rename, MenuItem.Delete), menuItems(row("a", 1, NotePhase.WAITING)))
        assertEquals(
            listOf(MenuItem.CopyText, MenuItem.Share, MenuItem.Rename, MenuItem.RemoveAudio, MenuItem.Delete),
            menuItems(row("a", 1).copy(canRemoveAudio = true)),
        )
    }

    @Test fun readyIsNoticedOnceWhenANoteFinishesBecomingText() {
        val watch = ReadyWatch()
        val idle = RecordingUiState()
        assertEquals(emptyList<String>(), watch.update(library(listOf(row("a", 2, NotePhase.TRANSCRIBING), row("b", 1))), idle))
        assertEquals(listOf("a"), watch.update(library(listOf(row("a", 2), row("b", 1))), idle))
        assertEquals(emptyList<String>(), watch.update(library(listOf(row("a", 2), row("b", 1))), idle))
        // A note that was live and is Ready the first time the list shows it (a short note).
        watch.update(library(listOf(row("a", 2))), RecordingUiState(phase = RecordingPhase.RECORDING, noteId = "c"))
        assertEquals(listOf("c"), watch.update(library(listOf(row("c", 3), row("a", 2))), idle))
    }
}
