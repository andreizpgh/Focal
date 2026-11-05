package app.focal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.Synthetic.assertSearchIndexIntact
import app.focal.Synthetic.commit
import app.focal.Synthetic.plan
import app.focal.Synthetic.savedAudio
import app.focal.Synthetic.speech
import app.focal.core.EndKind
import app.focal.core.WindowGate
import app.focal.data.EditResult
import app.focal.data.NoteState
import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.ULTRA_ENGINE
import app.focal.data.WindowState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class NoteStoreTest {
    private lateinit var root: File
    private lateinit var store: NoteStore

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "note-store-").toFile()
        store = NoteStore(IsolatedContext(app, root))
    }

    @After fun cleanup() {
        if (::store.isInitialized) store.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    /** A recording of [ms] with three windows: two sentences, a short pause, then a long pause. */
    private fun transcribedNote(ms: Long = 20_000): String {
        val id = store.create()
        savedAudio(store, id, ms)
        commit(store, id, plan(0, 0, 6_500, EndKind.PAUSE), speech("Hello world. This is Focal.", startSec = 5.0))
        commit(store, id, plan(1, 6_500, 13_000, EndKind.PAUSE, EndKind.PAUSE), speech("it keeps going.", startSec = 0.4))
        commit(store, id, plan(2, 13_000, ms, EndKind.FINAL, EndKind.PAUSE), speech("A new thought starts here.", startSec = 3.0))
        return id
    }

    @Test fun windowsCommitRawResultsAndParagraphsTogether() {
        val id = transcribedNote()
        val windows = store.windows(id)
        assertEquals(listOf(0, 1, 2), windows.map { it.index })
        assertTrue(windows.all { it.state == WindowState.DONE && it.engine == ULTRA_ENGINE && it.attempts == 1 })
        assertEquals("Hello world. This is Focal.", windows[0].rawText)
        assertEquals(speech("Hello world. This is Focal.", startSec = 5.0), store.windowTokens(windows[0].id))
        val paragraphs = store.paragraphs(id)
        // The seam repair removes the period before a lowercase continuation; a long pause starts a paragraph.
        assertEquals(listOf("Hello world. This is Focal it keeps going.", "A new thought starts here."), paragraphs.map { it.text })
        assertEquals(paragraphs.map { it.text }, paragraphs.map { it.sourceText })
        assertEquals(listOf(false, true), paragraphs.map { it.open })
        assertEquals(5_000L, paragraphs[0].startMs)
        assertEquals(8, paragraphs[0].sourceWords()!!.size)
        assertEquals("Hello world", store.note(id)!!.title)
        assertTrue(store.markReady(id))
        assertEquals(NoteState.READY, store.note(id)!!.state)
        assertTrue(store.paragraphs(id).none { it.open })
        assertEquals("Hello world. This is Focal it keeps going.\n\nA new thought starts here.", store.transcript(id))
        assertEquals(0L, store.progress(id).remainingMs)
        assertThrows(IllegalStateException::class.java) { store.addWindow(id, plan(3, 20_000, 21_000, EndKind.FINAL)) }
        assertSearchIndexIntact(store)
    }

    @Test fun windowsMustTileTheRecordingInOrderAndStopAtTheEnd() {
        val id = store.create()
        savedAudio(store, id, 10_000)
        assertThrows(IllegalStateException::class.java) { store.addWindow(id, plan(0, 100, 6_000, EndKind.PAUSE)) }
        assertThrows(IllegalStateException::class.java) { store.addWindow(id, plan(1, 0, 6_000, EndKind.PAUSE)) }
        store.addWindow(id, plan(0, 0, 6_000, EndKind.PAUSE))
        assertThrows(IllegalStateException::class.java) { store.addWindow(id, plan(1, 5_000, 9_000, EndKind.PAUSE)) }
        store.addWindow(id, plan(1, 6_000, 10_000, EndKind.FINAL))
        assertThrows(IllegalStateException::class.java) { store.addWindow(id, plan(2, 10_000, 11_000, EndKind.FINAL)) }
        assertEquals(2, store.pendingWindows(id).size)
        assertFalse("Pending windows block completion", store.markReady(id))
        assertEquals(1, store.lastWindow(id)!!.index)
    }

    @Test fun gateSkippedWindowsAndHiddenResultsAddNoText() {
        val id = store.create()
        savedAudio(store, id, 13_000)
        val silent = store.addWindow(id, plan(0, 0, 6_000, EndKind.PAUSE, peak = 10), WindowGate.Skip.SILENT)
        assertEquals(WindowState.SILENT, silent.state)
        assertEquals("SILENT", silent.skip)
        assertTrue(store.pendingWindows(id).isEmpty())
        // No detected speech and at most two words: stored raw, not shown.
        val quiet = store.addWindow(id, plan(1, 6_000, 13_000, EndKind.FINAL, EndKind.PAUSE, speechMs = 0))
        store.beginDecode(quiet.id)
        assertTrue(store.commitDecoded(quiet.id, ULTRA_ENGINE, RawAsr("Thank you.", speech("Thank you."))))
        assertFalse("A second commit is ignored", store.commitDecoded(quiet.id, ULTRA_ENGINE, RawAsr("x", speech("x"))))
        val stored = store.window(quiet.id)!!
        assertTrue(stored.hidden)
        assertEquals("Thank you.", stored.rawText)
        assertTrue(store.paragraphs(id).isEmpty())
        assertEquals("", store.note(id)!!.title)
        assertTrue(store.markReady(id))
    }

    @Test fun aWindowThatFailsTwiceIsMarkedFailedAndCanBeRetriedInPlace() {
        val id = store.create()
        savedAudio(store, id, 20_000)
        commit(store, id, plan(0, 0, 6_500, EndKind.PAUSE), speech("First part is here."))
        val crashing = store.addWindow(id, plan(1, 6_500, 13_000, EndKind.PAUSE, EndKind.PAUSE))
        assertEquals(1, store.beginDecode(crashing.id)!!.attempts)
        assertEquals(2, store.beginDecode(crashing.id)!!.attempts) // the process died during the first attempt
        assertNull("A window that was killed twice is not decoded again", store.beginDecode(crashing.id))
        assertEquals(WindowState.FAILED, store.window(crashing.id)!!.state)
        commit(store, id, plan(2, 13_000, 20_000, EndKind.FINAL, EndKind.PAUSE), speech("and the last part.", startSec = 0.4))
        // The failure closed the paragraph before it, so later text does not merge across the missing part.
        assertEquals(listOf("First part is here.", "and the last part."), store.paragraphs(id).map { it.text })
        assertTrue(store.markReady(id))
        assertEquals(1, store.note(id)!!.failedWindows)

        assertEquals(1, store.retryFailed(id))
        assertEquals(NoteState.INTERRUPTED, store.note(id)!!.state)
        val retried = store.pendingWindows(id).single()
        assertEquals(0, retried.attempts)
        assertNotNull(store.beginDecode(retried.id))
        assertTrue(store.commitDecoded(retried.id, ULTRA_ENGINE, RawAsr("The middle part.", speech("The middle part.", startSec = 1.0))))
        assertEquals(listOf("First part is here.", "The middle part.", "and the last part."), store.paragraphs(id).map { it.text })
        assertEquals(listOf(0, 1, 2), store.paragraphs(id).map { it.ord })
        assertTrue(store.markReady(id))
        assertEquals(0, store.note(id)!!.failedWindows)
        assertSearchIndexIntact(store)
    }

    @Test fun cancelledDecodeDoesNotCountAsAnAttempt() {
        val id = store.create()
        savedAudio(store, id, 7_000)
        val window = store.addWindow(id, plan(0, 0, 7_000, EndKind.FINAL))
        store.beginDecode(window.id)
        store.cancelDecode(window.id)
        assertEquals(0, store.window(window.id)!!.attempts)
    }

    @Test fun editsNeedAReadyNoteAndTheCurrentRevision() {
        val id = transcribedNote()
        val first = store.paragraphs(id).first()
        assertEquals(EditResult.NotEditable, store.editParagraph(first.id, first.revision, "Too early"))
        store.markReady(id)
        val ready = store.paragraphs(id).first()
        val saved = store.editParagraph(ready.id, ready.revision, "  Hello there. This is Focal it keeps going.  ")
        val edited = (saved as EditResult.Saved).paragraphs.single()
        assertEquals("Hello there. This is Focal it keeps going.", edited.text)
        assertTrue(edited.edited)
        assertTrue(edited.canRestore)
        assertEquals(ready.revision + 1, edited.revision)
        assertEquals("The automatic title follows the text", "Hello there", store.note(id)!!.title)
        assertEquals(EditResult.Conflict, store.editParagraph(ready.id, ready.revision, "Stale editor"))
        assertEquals(EditResult.TooLarge, store.editParagraph(edited.id, edited.revision, "x".repeat(200_000)))
        val restored = (store.restoreOriginal(edited.id, edited.revision) as EditResult.Saved).paragraphs.single()
        assertEquals(ready.text, restored.text)
        assertFalse(restored.edited)
        assertEquals(EditResult.Missing, store.editParagraph(-5, 0, "Nothing"))
        assertSearchIndexIntact(store)
    }

    @Test fun splitAndMergeRedistributeSourceWordsWithoutInventingTimes() {
        val id = transcribedNote()
        store.markReady(id)
        val first = store.paragraphs(id).first()
        val offset = first.text.indexOf("This")
        val split = (store.splitParagraph(first.id, first.revision, offset) as EditResult.Saved).paragraphs
        assertEquals(listOf("Hello world.", "This is Focal it keeps going."), split.map { it.text })
        assertEquals(split.map { it.text }, split.map { it.sourceText })
        assertEquals(listOf(2, 6), split.map { it.sourceWords()!!.size })
        assertTrue(split.none { it.edited })
        assertEquals(split[1].sourceWords()!!.first().startMs, split[1].startMs)
        assertEquals(listOf("Hello world.", "This is Focal it keeps going.", "A new thought starts here."),
            store.paragraphs(id).map { it.text })
        assertEquals(listOf(0, 1, 2), store.paragraphs(id).map { it.ord })

        val (left, right) = store.paragraphs(id)
        val merged = (store.mergeParagraphs(left.id, left.revision, right.id, right.revision) as EditResult.Saved).paragraphs.single()
        assertEquals(first.text, merged.text)
        assertEquals(first.sourceText, merged.sourceText)
        assertEquals(first.sourceWords(), merged.sourceWords())
        assertFalse(merged.edited)
        assertEquals(2, store.paragraphs(id).size)
        // Only neighbours merge.
        val (a, b) = store.paragraphs(id)
        assertEquals(EditResult.Conflict, store.mergeParagraphs(b.id, b.revision, a.id, a.revision))
        assertSearchIndexIntact(store)
    }

    @Test fun splittingOffAddedTextAndMergingBackKeepsRestoreOriginal() {
        val id = transcribedNote()
        store.markReady(id)
        val first = store.paragraphs(id).first()
        // Enter at the end, type into the new paragraph, then Backspace at its start.
        val (kept, empty) = (store.splitParagraph(first.id, first.revision, first.text.length) as EditResult.Saved).paragraphs
        assertEquals("", empty.sourceText)
        assertFalse(empty.canRestore)
        assertEquals(EditResult.NoOriginal, store.restoreOriginal(empty.id, empty.revision))
        val typed = (store.editParagraph(empty.id, empty.revision, "My addition") as EditResult.Saved).paragraphs.single()
        assertTrue(typed.edited)
        val merged = (store.mergeParagraphs(kept.id, kept.revision, typed.id, typed.revision) as EditResult.Saved).paragraphs.single()
        assertEquals("${first.text} My addition", merged.text)
        assertEquals(first.sourceText, merged.sourceText)
        assertTrue(merged.canRestore)
        // Split before the added words, then undo the split with a merge.
        val (left, right) = (store.splitParagraph(merged.id, merged.revision, merged.text.indexOf("My")) as EditResult.Saved).paragraphs
        assertEquals(first.sourceText to "", left.sourceText to right.sourceText)
        val again = (store.mergeParagraphs(left.id, left.revision, right.id, right.revision) as EditResult.Saved).paragraphs.single()
        assertEquals(first.sourceText, again.sourceText)
        assertTrue(again.canRestore)
        val restored = (store.restoreOriginal(again.id, again.revision) as EditResult.Saved).paragraphs.single()
        assertEquals(first.text, restored.text)
        assertFalse(restored.edited)
        assertSearchIndexIntact(store)
    }

    @Test fun findAndReplaceAllIgnoreCaseAndYoAndCanBeUndone() {
        val id = store.insertNoteForTests(listOf("Ёлка стоит. Елка растёт.", "No tree here.", "ЕЛКА"))
        assertEquals(3, store.findInNote(id, "елка").size)
        assertEquals(listOf(0, 12), store.findInNote(id, "ёлка").filter { it.paragraphId == store.paragraphs(id)[0].id }.map { it.start })
        val result = store.replaceAll(id, "елка", "Сосна")
        assertEquals(3, result.count)
        assertEquals(listOf("Сосна стоит. Сосна растёт.", "No tree here.", "Сосна"), store.paragraphs(id).map { it.text })
        assertTrue(store.paragraphs(id)[0].edited)
        assertTrue(store.search("елка").isEmpty())
        assertEquals(id, store.search("сосна").single().note.id)
        assertTrue(store.undoReplace(result.undo!!) is EditResult.Saved)
        assertEquals(listOf("Ёлка стоит. Елка растёт.", "No tree here.", "ЕЛКА"), store.paragraphs(id).map { it.text })
        assertFalse(store.paragraphs(id)[0].edited)

        val again = store.replaceAll(id, "елка", "Сосна")
        val changed = store.paragraphs(id)[2]
        store.editParagraph(changed.id, changed.revision, "Сосна!")
        assertEquals(EditResult.Conflict, store.undoReplace(again.undo!!))
        assertSearchIndexIntact(store)
    }

    @Test fun searchMatchesTitlesAndVisibleTextWithHitPositions() {
        val first = store.insertNoteForTests(listOf("Morning notes about the café.", "Ёжик в тумане ищет лошадку."), title = "Planning")
        val second = store.insertNoteForTests(listOf("Nothing relevant."), title = "Café review")
        val hit = store.search("ежик").single()
        assertEquals(first, hit.note.id)
        assertEquals(store.paragraphs(first)[1].id, hit.paragraphId)
        assertEquals(0, hit.hitStart)
        assertEquals(4, hit.hitEnd)
        assertTrue(hit.snippet.startsWith("Ёжик"))
        // Latin diacritics and prefixes: the paragraph of one note, the title of the other.
        val cafe = store.search("cafe")
        assertEquals(setOf(first, second), cafe.map { it.note.id }.toSet())
        assertNull(cafe.single { it.note.id == second }.paragraphId)
        assertEquals(first, store.search("лошад").single().note.id)
        assertEquals(listOf(second), store.search("rev").map { it.note.id })
        assertTrue(store.search("  ").isEmpty())
        assertTrue(store.search("\"*) OR (").isEmpty())
        assertSearchIndexIntact(store)
    }

    @Test fun renameSetsACustomTitleAndBlankReturnsToTheAutomaticOne() {
        val id = transcribedNote()
        store.rename(id, "  Weekly plan  ")
        assertEquals("Weekly plan", store.note(id)!!.title)
        assertTrue(store.note(id)!!.titleIsCustom)
        assertEquals(id, store.search("weekly").single().note.id)
        store.rename(id, " ")
        assertEquals("Hello world", store.note(id)!!.title)
        assertFalse(store.note(id)!!.titleIsCustom)
        assertTrue(store.search("weekly").isEmpty())
        assertSearchIndexIntact(store)
    }

    @Test fun deletionCanBeUndoneUntilItIsPurged() {
        val id = store.insertNoteForTests(listOf("Keep or delete me."))
        val audio = savedAudio(store, id, 1_000)
        assertTrue(store.deleteWithUndo(id))
        assertNull(store.note(id))
        assertTrue(store.library().none { it.id == id })
        assertTrue(store.search("delete").isEmpty())
        assertTrue(store.undoDelete(id))
        assertEquals("Keep or delete me.", store.transcript(id))
        assertArrayEquals(audio, store.audioFile(id).readBytes())
        assertTrue(store.deleteWithUndo(id))
        store.recover() // the next start purges the trash
        assertFalse(store.undoDelete(id))
        assertFalse(store.audioFile(id).exists())
        assertTrue(store.paragraphs(id).isEmpty())
        store.writableDatabase.rawQuery("SELECT count(*) FROM pending_audio_deletions", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        assertSearchIndexIntact(store)
    }

    @Test fun activeRecordingsCannotBeTrashed() {
        val id = store.create()
        assertFalse(store.deleteWithUndo(id))
        store.delete(id)
        assertNull(store.note(id))
    }

    @Test fun removingAudioKeepsTextAndWaitsForTranscription() {
        val id = store.create()
        savedAudio(store, id, 7_000)
        store.state(id, NoteState.INTERRUPTED)
        assertThrows(IllegalStateException::class.java) { store.removeAudio(id) }
        commit(store, id, plan(0, 0, 7_000, EndKind.FINAL), speech("Keep me."))
        store.markReady(id)
        store.removeAudio(id)
        assertFalse(store.audioFile(id).exists())
        assertFalse(store.note(id)!!.audioPresent)
        assertEquals("Keep me.", store.transcript(id))
        assertEquals(0, store.retryFailed(id))
    }

    @Test fun recoveryMakesInterruptedRecordingsWaitWithTheirSyncedDuration() {
        val id = store.create()
        savedAudio(store, id, 2_000)
        store.duration(id, 1_000)
        store.pauseAutomatically(id, "MICROPHONE")
        store.addBreak(id, 16_000)
        store.recover()
        val note = store.note(id)!!
        assertEquals(NoteState.INTERRUPTED, note.state)
        assertEquals(2_000L, note.duration)
        assertNull(note.pauseReason)
        assertEquals("PROCESS_ENDED", note.endReason)
        assertEquals(listOf(16_000L), store.breaks(id))
        assertTrue(store.hasUntranscribedAudio(note))
        assertEquals(listOf(id), store.waitingNotes())
        assertEquals(2_000L, store.progress(id).remainingMs)
    }

    @Test fun durationTicksAreNotLibraryChanges() {
        val id = store.create()
        val before = store.changes.value
        store.duration(id, 5_000)
        assertEquals(before, store.changes.value)
        assertEquals(5_000L, store.note(id)!!.duration)
    }

    @Test fun constantPcmIsAnEmptyRecording() {
        val id = store.create()
        assertTrue(store.audioIsEmpty(id))
        savedAudio(store, id, 500) { 7 }
        assertTrue(store.audioIsEmpty(id))
        savedAudio(store, id, 500) { (it % 5).toByte() }
        assertFalse(store.audioIsEmpty(id))
        assertEquals(800, store.readAudio(id, 0, 50).size)
        assertEquals(0, store.readAudio(id, 600, 700).size)
    }

    @Test fun storeSurvivesReopenWithItsIndexes() {
        val id = transcribedNote()
        store.markReady(id)
        store.close()
        store = NoteStore(IsolatedContext(ApplicationProvider.getApplicationContext(), root))
        assertEquals(5, store.readableDatabase.version)
        assertEquals(id, store.search("thought").single().note.id)
        assertEquals(2, store.paragraphs(id).size)
        assertSearchIndexIntact(store)
    }
}
