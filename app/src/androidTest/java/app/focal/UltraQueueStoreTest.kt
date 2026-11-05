package app.focal

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class UltraQueueStoreTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "ultra-queue-").toFile()
        context = object : ContextWrapper(app) {
            override fun getDatabasePath(name: String) = File(root, name)
            override fun getFilesDir() = File(root, "files")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                openOrCreateDatabase(name, mode, factory, null)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                                              errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
                val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
                    if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0
                return SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, flags, errorHandler)
            }
        }
    }

    @After fun cleanup() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun schemaThreePreservesWhisperNoteAndOriginalAudioAndText() {
        val audio = ByteArray(32_000) { (it % 97).toByte() }
        val original = File(context.filesDir, "audio/legacy.pcm")
        original.parentFile!!.mkdirs()
        original.writeBytes(audio)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("focal.db"), null).use { db ->
            db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
                language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',
                automatic_title_chunk TEXT)""")
            db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                chunk_id TEXT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL,
                text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0,
                original_transcript_text TEXT,excluded INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE INDEX chunks_queue ON chunks(note_id,state,start)")
            db.execSQL("CREATE INDEX passages_note ON passages(note_id,start,id)")
            db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO notes VALUES('legacy','Manual title',123,'ru','INTERRUPTED',1000,'Interrupted',NULL)")
            db.execSQL("INSERT INTO chunks VALUES('legacy-done','legacy',0,500,0,'DONE')")
            db.execSQL("INSERT INTO chunks VALUES('legacy-pending','legacy',500,1000,0,'PENDING')")
            db.execSQL("INSERT INTO passages VALUES(17,'legacy','legacy-done',0,500,'Manual edit',1,1,'Original ASR',0)")
            db.version = 3
        }

        NoteStore(context).use { store ->
            assertEquals(4, store.readableDatabase.version)
            val note = checkNotNull(store.note("legacy"))
            assertEquals("whisper", note.speechEngine)
            assertEquals("ru", note.language)
            assertEquals("Manual title", note.title)
            assertEquals("INTERRUPTED", note.state)
            assertEquals(1000L, note.duration)
            assertEquals("legacy-pending", store.nextChunk(note.id)?.id)
            assertTrue(store.chunks(note.id).all { it.nextStart == null })
            assertEquals(Passage(17, "legacy", "legacy-done", 0, 500, "Manual edit",
                uncertain = true, reviewed = true, originalTranscriptText = "Original ASR"),
                store.passages(note.id).single())
            assertArrayEquals(audio, store.audioFile(note.id).readBytes())
        }
    }

    @Test fun ultraCursorRejectsUnpublishedOrRequeuedRangesAndSurvivesReopen() {
        val savedMs = 65_001L
        val audio = ByteArray((savedMs * 32).toInt()) { (it % 113).toByte() }
        lateinit var pending: Chunk
        lateinit var edited: Passage
        val id = NoteStore(context).use { store ->
            val note = store.create("auto", "ultra-v1")
            FileOutputStream(store.audioFile(note)).use { it.write(audio); it.fd.sync() }
            store.duration(note, 29_999)
            assertThrows(IllegalStateException::class.java) { store.enqueueUltraWindow(note, 0, 30_000, 29_500) }
            assertTrue(store.chunks(note).isEmpty())

            store.duration(note, 59_500)
            val first = store.enqueueUltraWindow(note, 0, 30_000, 29_500)
            assertThrows(IllegalStateException::class.java) { store.enqueueUltraWindow(note, 29_500, 59_500, 59_000) }
            assertEquals(listOf(first), store.chunks(note))
            store.complete(first, listOf(Passage(noteId = note, chunkId = first.id, start = first.start, end = first.end,
                text = "Original synthetic words", uncertain = false)))
            store.edit(store.passages(note).single(), "Preserve the manual edit")
            edited = store.passages(note).single()
            assertThrows(IllegalStateException::class.java) { store.enqueueUltraWindow(note, 0, 30_000, 29_500) }
            assertThrows(IllegalStateException::class.java) { store.enqueueUltraWindow(note, 29_500, 29_999, 29_999) }
            pending = store.enqueueUltraWindow(note, 29_500, 59_500, 59_000)
            assertTrue(pending.overlap)
            store.state(note, "INTERRUPTED")
            note
        }

        NoteStore(context).use { store ->
            val note = checkNotNull(store.note(id))
            assertEquals("ultra-v1", note.speechEngine)
            assertEquals("auto", note.language)
            assertEquals(59_500L, note.duration)
            assertEquals(pending, store.nextChunk(id))
            assertTrue(store.hasUnpublishedTail(note))
            store.recover(id)
            assertEquals(savedMs, checkNotNull(store.note(id)).duration)
            assertEquals(pending, store.nextChunk(id))
            assertEquals(2, store.chunks(id).size)
            store.complete(pending, emptyList())
            val tail = store.enqueueUltraWindow(id, checkNotNull(pending.nextStart), savedMs, savedMs)
            assertEquals(tail, store.nextChunk(id))
            assertTrue(tail.overlap)
            assertEquals(edited, store.passages(id).single())
            assertEquals("Original synthetic words", edited.originalTranscriptText)
            assertTrue(edited.reviewed)
            assertArrayEquals(audio, store.audioFile(id).readBytes())
            store.complete(tail, emptyList())
            assertNull(store.nextChunk(id))
        }
    }
}
