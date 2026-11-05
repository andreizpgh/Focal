package app.focal

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.focal.data.LEGACY_WHISPER_ENGINE
import app.focal.data.NoteStore
import app.focal.data.Passage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NoteStoreTest {
    private lateinit var store: NoteStore
    private val created = mutableListOf<String>()
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        kotlinx.coroutines.runBlocking { app.storageReady.await() }
        store = app.store
    }
    @After fun cleanup() { created.forEach { store.delete(it) } }
    // These notes use the Whisper-era range API, so they are legacy notes.
    private fun note() = store.create("ru", LEGACY_WHISPER_ENGINE).also { created += it }

    @Test fun savedAudioSurvivesInterruptionAndCanBeCommittedExactlyOnce() {
        val id = note()
        val chunkId = store.beginChunk(id, 1200, false)
        store.audioFile(id).writeBytes(ByteArray(2200 * 32))
        store.recover()
        assertEquals("INTERRUPTED", store.note(id)?.state)
        val chunk = store.nextChunk(id)!!
        assertEquals(2200L, chunk.end)
        store.complete(chunk, listOf(Passage(noteId = id, chunkId = chunkId, start = 1200, end = 2200, text = "Записанная мысль.", uncertain = true)))
        assertNull(store.nextChunk(id))
        store.complete(chunk, listOf(Passage(noteId = id, chunkId = chunkId, start = 1200, end = 2200, text = "Duplicate", uncertain = true)))
        val passage = store.passages(id).single()
        assertEquals("Записанная мысль.", passage.text)
        store.edit(passage, "Исправленная мысль.")
        assertTrue(store.passages(id).single().reviewed)
        assertEquals(0, store.notes().first { it.id == id }.reviewCount)
    }
    @Test fun deletingNoteCascadesTextAndRemovesItsPrivateAudio() {
        val id = note(); val chunk = store.beginChunk(id, 0, false)
        val file = store.audioFile(id); file.writeBytes(ByteArray(32000))
        store.finishChunk(chunk, 1000)
        store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk, start = 0, end = 1000, text = "Text", uncertain = false)))
        store.delete(id)
        assertNull(store.note(id)); assertTrue(store.passages(id).isEmpty()); assertFalse(file.exists())
    }
    @Test fun removingAudioPreservesEditableText() {
        val id = note(); val chunk = store.beginChunk(id, 0, false)
        store.audioFile(id).writeBytes(ByteArray(32000)); store.finishChunk(chunk, 1000)
        store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk, start = 0, end = 1000, text = "Keep me", uncertain = false)))
        store.removeAudio(id)
        assertEquals(0L, store.audioBytes(id)); assertEquals("Keep me", store.passages(id).single().text)
    }
    @Test fun durableQueueAndContextAreOrderedAndIsolatedBetweenNotes() {
        val id = note()
        repeat(12) { index ->
            val chunk = store.beginChunk(id, index * 1000L, false)
            store.finishChunk(chunk, (index + 1) * 1000L)
            val next = store.nextChunk(id)!!
            assertEquals(chunk, next.id)
            store.complete(next, listOf(Passage(noteId = id, chunkId = chunk, start = index * 1000L,
                end = (index + 1) * 1000L, text = "Phrase $index", uncertain = false)))
        }
        val other = note()
        val pending = store.beginChunk(id, 12000, false); store.finishChunk(pending, 13000)
        store.beginChunk(id, 13000, false)
        assertEquals(2, store.pendingCount(id))
        assertEquals(0, store.pendingCount(other))
        assertEquals(pending, store.nextChunk(id)?.id)
        val context = store.recentPassages(id, 12000)
        assertEquals((6..11).map { "Phrase $it" }, context.map { it.text })
        assertTrue(store.recentPassages(other, 12000).isEmpty())
    }

    @Test fun repeatedSealCannotRequeueCorrectedTextOrChangeItsSourceAudio() {
        val id = note()
        val original = ByteArray(32_000) { (it % 127).toByte() }
        store.audioFile(id).writeBytes(original)
        val chunkId = store.beginChunk(id, 0, false)
        store.finishChunk(chunkId, 1000)
        val chunk = store.nextChunk(id)!!
        store.complete(chunk, listOf(Passage(noteId = id, chunkId = chunkId, start = 100, end = 900, text = "Original", uncertain = true)))
        store.edit(store.passages(id).single(), "Corrected")
        store.finishChunk(chunkId, 2000)
        store.complete(chunk, listOf(Passage(noteId = id, chunkId = chunkId, start = 0, end = 1000, text = "Repeated result", uncertain = true)))
        assertNull(store.nextChunk(id))
        val passage = store.passages(id).single()
        assertEquals("Corrected", passage.text)
        assertTrue(passage.reviewed)
        assertEquals(100L, passage.start)
        assertEquals(900L, passage.end)
        assertEquals(1000L, store.chunks(id).single().end)
        assertArrayEquals(original, store.audioFile(id).readBytes())
    }

    @Test fun invalidSourceMappingRejectsTheWholePassageBatch() {
        val id = note()
        val other = note()
        val chunkId = store.beginChunk(id, 0, false)
        val otherChunk = store.beginChunk(other, 0, false)
        store.finishChunk(chunkId, 1000)
        val chunk = store.nextChunk(id)!!
        val valid = Passage(noteId = id, chunkId = chunkId, start = 0, end = 500, text = "Valid", uncertain = false)
        listOf(valid.copy(noteId = other), valid.copy(chunkId = otherChunk), valid.copy(start = -1), valid.copy(end = 1001)).forEach { invalid ->
            try { store.complete(chunk, listOf(valid, invalid)); fail("Invalid source mapping was accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(store.passages(id).isEmpty())
            assertTrue(store.passages(other).isEmpty())
            assertEquals(chunkId, store.nextChunk(id)?.id)
        }
        store.complete(chunk, listOf(valid))
        val saved = store.passages(id).single()
        try { store.edit(saved.copy(noteId = other), "Wrong note"); fail("A mismatched edit was accepted") }
        catch (_: IllegalStateException) { }
        assertEquals("Valid", store.passages(id).single().text)
    }

    @Test fun longInterruptedTailRecoversAsBoundedOverlappingChunksWithoutChangingAudio() {
        val id = note()
        val original = ByteArray(65_001 * 32 + 1) { (it % 127).toByte() }
        store.audioFile(id).writeBytes(original)
        store.beginChunk(id, 1000, false)
        store.state(id, "ERROR", "Synthetic decoder failure")
        store.recover(id)
        val chunks = store.chunks(id)
        assertEquals(listOf(1000L, 30_500L, 60_000L), chunks.map { it.start })
        assertEquals(listOf(31_000L, 60_500L, 65_001L), chunks.map { it.end })
        assertEquals(listOf(false, true, true), chunks.map { it.overlap })
        assertTrue(chunks.all { it.state == "PENDING" && store.readChunk(it).size <= 960_000 })
        assertEquals("ERROR", store.note(id)?.state)
        assertEquals(65_001L, store.note(id)?.duration)
        store.recover(id)
        assertEquals(chunks, store.chunks(id))
        assertArrayEquals(original, store.audioFile(id).readBytes())
    }

    @Test fun interruptedRecoveryRollsBackAllQueueAndNoteChanges() {
        val id = note()
        store.audioFile(id).writeBytes(ByteArray(64_000))
        store.beginChunk(id, 0, false)
        val second = store.beginChunk(id, 1000, false)
        val trigger = "test_recovery_" + id.replace("-", "")
        store.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE UPDATE OF state ON chunks
            WHEN NEW.id='$second' BEGIN SELECT RAISE(ABORT,'Synthetic interrupted transaction'); END""")
        try {
            try { store.recover(id); fail("Recovery should have failed") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals("RECORDING", store.note(id)?.state)
            assertEquals(0L, store.note(id)?.duration)
            assertTrue(store.chunks(id).all { it.state == "WRITING" && it.end == it.start })
        } finally { store.writableDatabase.execSQL("DROP TRIGGER $trigger") }
    }

    @Test fun failedFileDeletionKeepsADurableIntentAndIsRetriedOnRecovery() {
        val id = note()
        // A nonempty directory deterministically fails File.delete without chmod/root assumptions.
        val path = store.audioFile(id).apply { mkdirs() }
        val obstruction = File(path, "synthetic").apply { writeText("fixture") }
        try {
            try { store.delete(id); fail("Failed audio deletion was silently accepted") }
            catch (_: IOException) { }
            assertNull(store.note(id))
            assertTrue(path.exists())
            assertEquals(1L, pendingDeletion(id))
            obstruction.delete()
            NoteStore(ApplicationProvider.getApplicationContext<Context>()).use { reopened -> reopened.recover(id) }
            assertFalse(path.exists())
            assertEquals(0L, pendingDeletion(id))
        } finally { obstruction.delete(); path.delete() }
    }

    @Test fun failedDatabaseDeletionKeepsTheNoteAndItsOriginalAudio() {
        val id = note()
        val original = ByteArray(32_000) { (it % 127).toByte() }
        store.audioFile(id).writeBytes(original)
        val trigger = "test_delete_" + id.replace("-", "")
        store.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE DELETE ON notes
            WHEN OLD.id='$id' BEGIN SELECT RAISE(ABORT,'Synthetic delete failure'); END""")
        try {
            try { store.delete(id); fail("Database deletion should have failed") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertNotNull(store.note(id))
            assertArrayEquals(original, store.audioFile(id).readBytes())
            assertEquals(0L, pendingDeletion(id))
        } finally { store.writableDatabase.execSQL("DROP TRIGGER $trigger") }
    }

    @Test fun audioRemovalRetainsCorrectionsAndDurationAndRejectsUnprocessedSources() {
        val id = note()
        store.audioFile(id).writeBytes(ByteArray(32_000))
        val chunk = store.beginChunk(id, 0, false)
        store.finishChunk(chunk, 1000)
        try { store.removeAudio(id); fail("Pending source audio was removed") }
        catch (_: IllegalStateException) { }
        assertEquals(32_000L, store.audioBytes(id))
        store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk, start = 50, end = 950, text = "Original", uncertain = true)))
        store.edit(store.passages(id).single(), "Correction")
        store.duration(id, 1000)
        store.state(id, "INTERRUPTED")
        store.removeAudio(id)
        store.recover(id)
        val passage = store.passages(id).single()
        assertEquals("Correction", passage.text)
        assertTrue(passage.reviewed)
        assertEquals(50L, passage.start)
        assertEquals(950L, passage.end)
        assertEquals(1000L, store.note(id)?.duration)
        assertEquals(0L, store.audioBytes(id))
    }

    @Test fun unpublishedTailBlocksAudioRemovalUntilRecovered() {
        val id = note()
        val original = ByteArray(64_000) { (it % 101).toByte() }
        store.audioFile(id).writeBytes(original)
        val first = store.beginChunk(id, 0, false)
        store.finishChunk(first, 1000)
        store.complete(store.nextChunk(id)!!, emptyList())
        store.state(id, "INTERRUPTED")
        assertEquals(0, store.pendingCount(id))

        try { store.removeAudio(id); fail("The unpublished audio tail was removed") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(original, store.audioFile(id).readBytes())
        assertEquals(0L, pendingDeletion(id))

        store.recover(id)
        val tail = checkNotNull(store.nextChunk(id))
        assertEquals(1000L to 2000L, tail.start to tail.end)
        store.complete(tail, emptyList())
        store.removeAudio(id)
        assertEquals(0L, store.audioBytes(id))
    }

    @Test fun previousCompletedChunkLookupIsBoundedAndNeverCrossesNotes() {
        val id = note()
        val other = note()
        fun completed(note: String, start: Long, end: Long) {
            val chunk = store.beginChunk(note, start, false)
            store.finishChunk(chunk, end)
            store.complete(store.nextChunk(note)!!, emptyList())
        }
        completed(id, 0, 1000)
        completed(id, 900, 1800)
        completed(other, 1700, 2200)
        val pending = store.beginChunk(id, 1750, true)
        store.finishChunk(pending, 2500)
        assertEquals(1800L, store.previousChunkEnd(store.nextChunk(id)!!))
        assertNull(store.previousChunkEnd(store.chunks(id).first()))
    }

    @Test fun renamedTitlesPersistWholeEmojiAtTheUtf16Limit() {
        val id = note()
        store.rename(id, "  " + "a".repeat(119) + "🙂  ")
        assertEquals("a".repeat(119), store.note(id)?.title)
        store.rename(id, "a".repeat(118) + "🙂tail")
        assertEquals("a".repeat(118) + "🙂", store.note(id)?.title)
        store.rename(id, "a".repeat(118) + "🙂")
        assertEquals("a".repeat(118) + "🙂", store.note(id)?.title)
    }

    @Test fun unicodeSearchFindsLaterCorrectedPassagesAndTreatsSqlSymbolsLiterally() {
        val id = note()
        val chunk = store.beginChunk(id, 0, false)
        store.finishChunk(chunk, 2000)
        store.complete(store.nextChunk(id)!!, listOf(
            Passage(noteId = id, chunkId = chunk, start = 0, end = 1000, text = "First passage", uncertain = false),
            Passage(noteId = id, chunkId = chunk, start = 1000, end = 2000, text = "МЫСЛЬ ÜBER AÇÃO 100% _ 'quoted'", uncertain = true)))
        // Automatic titles can legitimately retain the original words after a correction.
        store.rename(id, "Neutral title")
        listOf("мысль", "über", "ação", "100%", "_", "'quoted'").forEach { query ->
            assertTrue("Missing Unicode/literal match: $query", store.notes(query).any { it.id == id })
        }
        store.edit(store.passages(id).last(), "Исправленная фраза")
        assertTrue(store.notes("ИСПРАВЛЕННАЯ").any { it.id == id })
        assertFalse(store.notes("МЫСЛЬ").any { it.id == id })
        store.rename(id, "Résumé")
        assertTrue(store.notes("RÉSUMÉ").any { it.id == id })
    }

    @Test fun versionOneMigrationAddsDeletionRecoveryWithoutChangingStoredText() {
        android.database.sqlite.SQLiteDatabase.create(null).use { legacy ->
            legacy.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE passages(id INTEGER PRIMARY KEY,text TEXT NOT NULL,reviewed INTEGER NOT NULL)")
            legacy.execSQL("INSERT INTO notes VALUES('synthetic-note','Saved title')")
            legacy.execSQL("INSERT INTO passages VALUES(1,'Saved correction',1)")
            store.onUpgrade(legacy, 1, 2)
            legacy.rawQuery("SELECT title FROM notes", null).use { assertTrue(it.moveToFirst()); assertEquals("Saved title", it.getString(0)) }
            legacy.rawQuery("SELECT text,reviewed FROM passages", null).use {
                assertTrue(it.moveToFirst()); assertEquals("Saved correction", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            legacy.execSQL("INSERT INTO pending_audio_deletions VALUES('deleted-note')")
            legacy.rawQuery("SELECT note_id FROM pending_audio_deletions", null).use {
                assertTrue(it.moveToFirst()); assertEquals("deleted-note", it.getString(0))
            }
        }
    }

    @Test fun targetedRecoveryDoesNotInterruptAnotherCapture() {
        val id = note()
        val other = note()
        store.audioFile(id).writeBytes(ByteArray(32_000))
        store.audioFile(other).writeBytes(ByteArray(32_000))
        store.beginChunk(id, 0, false)
        store.beginChunk(other, 0, false)
        store.recover(id)
        assertEquals("INTERRUPTED", store.note(id)?.state)
        assertEquals("PENDING", store.chunks(id).single().state)
        assertEquals("RECORDING", store.note(other)?.state)
        assertEquals("WRITING", store.chunks(other).single().state)
        assertEquals(0L, store.note(other)?.duration)
    }

    @Test fun recoveryFindsPersistedAudioWhenQueuedBeginNeverReachedTheDatabase() {
        val id = note()
        val original = ByteArray(65_000 * 32) { (it % 127).toByte() }
        store.audioFile(id).writeBytes(original)
        val chunk = store.beginChunk(id, 0, false)
        store.finishChunk(chunk, 1000)
        store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk,
            start = 0, end = 1000, text = "Original", uncertain = true)))
        store.edit(store.passages(id).single(), "Correction")
        // The PCM tail is durable, but its queued Begin was lost with the process.
        store.recover(id)
        val chunks = store.chunks(id)
        assertEquals(listOf(0L, 1000L, 30_500L, 60_000L), chunks.map { it.start })
        assertEquals(listOf(1000L, 31_000L, 60_500L, 65_000L), chunks.map { it.end })
        assertEquals(listOf(false, false, true, true), chunks.map { it.overlap })
        assertEquals("DONE", chunks.first().state)
        assertTrue(chunks.drop(1).all { it.state == "PENDING" && store.readChunk(it).size <= 960_000 })
        assertEquals("Correction", store.passages(id).single().text)
        assertTrue(store.passages(id).single().reviewed)
        store.recover(id)
        assertEquals(chunks, store.chunks(id))
        assertArrayEquals(original, store.audioFile(id).readBytes())
    }

    @Test fun tailRecoveryKeepsCompletedSessionsAndEarlierSilenceGapsUnchanged() {
        val id = note()
        store.audioFile(id).writeBytes(ByteArray(5000 * 32))
        listOf(0L to 1000L, 3000L to 4000L).forEach { (start, end) ->
            val chunk = store.beginChunk(id, start, false)
            store.finishChunk(chunk, end)
            store.complete(store.nextChunk(id)!!, emptyList())
        }
        store.state(id, "READY")
        store.recover(id)
        assertEquals(2, store.chunks(id).size)
        assertEquals("READY", store.note(id)?.state)
        store.state(id, "RECORDING")
        store.recover(id)
        val recovered = store.chunks(id)
        assertEquals(listOf(0L, 3000L, 4000L), recovered.map { it.start })
        assertEquals(5000L, recovered.last().end)
        assertFalse(recovered.last().overlap)
    }

    @Test fun oversizedEditsAreRejectedAndLegacyTextCanBeReadAndSearched() {
        val id = note(); val chunk = store.beginChunk(id, 0, false)
        store.finishChunk(chunk, 1000)
        store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk,
            start = 0, end = 1000, text = "Saved original", uncertain = false)))
        val original = store.passages(id).single()
        try { store.edit(original, "🙂".repeat(40_000)); fail("Oversized edit accepted") }
        catch (_: app.focal.data.PassageSizeException) { }
        assertEquals("Saved original", store.passages(id).single().text)
        // Simulate an older install that already saved a passage larger than CursorWindow.
        val legacy = "a".repeat(2_100_000) + "мАрКеР🙂" + "b".repeat(100)
        store.writableDatabase.execSQL("UPDATE passages SET text=? WHERE id=?", arrayOf<Any>(legacy, original.id))
        assertEquals(legacy, store.passages(id).single().text)
        assertTrue(store.notes().any { it.id == id })
        assertTrue(store.notes("МаРкЕр🙂").any { it.id == id })
        store.edit(original, "Shortened safely")
        assertEquals("Shortened safely", store.passages(id).single().text)
    }

    @Test fun legacyExcludedPassageStaysOutOfCopyAndSearchUntilRestored() {
        val id = note()
        store.audioFile(id).writeBytes(ByteArray(2000 * 32))
        listOf(Triple(0L, 1000L, "Retired background chatter"), Triple(1000L, 2000L, "Keep these words")).forEach { (start, end, text) ->
            val chunk = store.beginChunk(id, start, false)
            store.finishChunk(chunk, end)
            store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk, start = start, end = end,
                text = text, uncertain = false)))
        }
        store.rename(id, "Legacy note")
        store.state(id, "READY")
        // The retired background-sound check stored its decisions this way.
        store.writableDatabase.execSQL("UPDATE passages SET excluded=1 WHERE id=?", arrayOf<Any>(store.passages(id).first().id))
        assertEquals("Keep these words", app.focal.data.transferableTranscript(store.passages(id)))
        assertTrue(store.notes("chatter").none { it.id == id })
        val excluded = store.passages(id).first()
        assertTrue(excluded.excluded)
        assertTrue(store.restorePassage(excluded))
        assertFalse(store.restorePassage(excluded))
        assertFalse(store.passages(id).first().excluded)
        assertEquals("Retired background chatter\n\nKeep these words", app.focal.data.transferableTranscript(store.passages(id)))
        assertTrue(store.notes("chatter").any { it.id == id })
    }

    private fun pendingDeletion(id: String): Long = store.readableDatabase.rawQuery(
        "SELECT count(*) FROM pending_audio_deletions WHERE note_id=?", arrayOf(id)
    ).use { it.moveToFirst(); it.getLong(0) }
}
