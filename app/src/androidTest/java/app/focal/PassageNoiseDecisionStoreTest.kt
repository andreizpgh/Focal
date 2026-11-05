package app.focal

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.data.transferableTranscript
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PassageNoiseDecisionStoreTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "passage-noise-").toFile()
        context = object : ContextWrapper(app) {
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun getFilesDir(): File = File(root, "files")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                openOrCreateDatabase(name, mode, factory, null)

            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                                              errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
                val flags = SQLiteDatabase.CREATE_IF_NECESSARY or
                    if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0
                return SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, flags, errorHandler)
            }
        }
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun exclusionFiltersTransferSearchPreviewAndContextAndRestoresAfterReopen() {
        val audio = ByteArray(32_000) { (it % 113).toByte() }
        val noise = "Invented noise phrase"
        val useful = "Keep these words"
        val id = NoteStore(context).use { store ->
            val noteId = readyNote(store, audio, noise, useful)
            val chunk = store.completedChunks(noteId).single()
            val passage = store.noiseCandidatePassages(noteId, chunk.id).first()
            assertEquals(noise, passage.originalTranscriptText)
            assertTrue(store.notes(noise).any { it.id == noteId })
            assertTrue(store.excludePassage(passage))
            assertFalse(store.excludePassage(passage))
            val saved = store.passages(noteId)
            assertEquals(passage.copy(excluded = true), saved.first())
            assertEquals(useful, transferableTranscript(saved))
            assertEquals(useful, store.notes().single().preview)
            assertEquals(useful, store.note(noteId)?.title)
            assertEquals(1, store.notes().single().reviewCount)
            assertTrue(store.notes(noise).isEmpty())
            assertEquals(listOf(useful), store.recentPassages(noteId, 1000).map { it.text })
            store.complete(chunk, listOf(passage.copy(text = "Retry must not replace the result")))
            assertEquals(saved, store.passages(noteId))
            store.rename(noteId, "My independent title")
            assertArrayEquals(audio, store.audioFile(noteId).readBytes())
            noteId
        }
        NoteStore(context).use { store ->
            val excluded = store.passages(id).first()
            assertTrue(excluded.excluded)
            assertTrue(store.restorePassage(excluded))
            assertFalse(store.restorePassage(excluded))
            assertEquals(excluded.copy(excluded = false, reviewed = true), store.passages(id).first())
            assertEquals("My independent title", store.note(id)?.title)
            assertEquals("$noise\n\n$useful", transferableTranscript(store.passages(id)))
            assertTrue(store.notes(noise).any { it.id == id })
            assertArrayEquals(audio, store.audioFile(id).readBytes())
        }
    }

    @Test fun decisionsCannotHideAnEditOrTouchUnfinishedNotes() {
        NoteStore(context).use { store ->
            val id = readyNote(store, ByteArray(48_000), "First guess", "Second guess", "Third guess")
            val (first, second, third) = store.passages(id)
            assertFalse(store.excludePassage(first.copy(text = "Stale contents")))
            store.edit(first, "Manual correction")
            assertFalse(store.excludePassage(first))
            assertFalse(store.keepPassage(first))
            assertEquals(first.copy(text = "Manual correction", reviewed = true), store.passages(id).first())

            val pending = store.beginChunk(id, 1000, false)
            assertFalse(store.keepPassage(second))
            assertFalse(store.excludePassage(third))
            store.finishChunk(pending, 1500)
            store.complete(checkNotNull(store.nextChunk(id)), emptyList())
            store.duration(id, 1500)
            store.state(id, "INTERRUPTED")
            assertFalse(store.keepPassage(second))
            store.state(id, "READY")

            assertTrue(store.keepPassage(second))
            assertFalse(store.excludePassage(second))
            assertTrue(store.excludePassage(third))
            store.edit(store.passages(id).last(), "Manual replacement")
            assertFalse(store.restorePassage(third))
            assertEquals(third.copy(text = "Manual replacement", reviewed = true), store.passages(id).last())
            assertTrue(store.noiseCandidatePassages(id, third.chunkId).isEmpty())
        }
    }

    @Test fun upgradesBothLegacyVersionsWithoutInventingOriginalText() {
        for (version in 1..2) {
            val path = context.getDatabasePath("focal.db")
            val audio = byteArrayOf(7, 0, 8, 0)
            val audioFile = File(context.filesDir, "audio/legacy.pcm")
            audioFile.parentFile?.mkdirs()
            audioFile.writeBytes(audio)
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
                    language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL,error TEXT NOT NULL)""")
                db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),
                    start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL)""")
                db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),
                    chunk_id TEXT NOT NULL REFERENCES chunks(id),start INTEGER NOT NULL,end INTEGER NOT NULL,
                    text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL)""")
                db.execSQL("INSERT INTO notes VALUES('legacy','Saved title',1,'ru','READY',1000,'')")
                db.execSQL("INSERT INTO chunks VALUES('chunk','legacy',0,1000,0,'DONE')")
                db.execSQL("INSERT INTO passages VALUES(1,'legacy','chunk',0,500,'Saved manual edit',1,1)")
                db.execSQL("INSERT INTO passages VALUES(2,'legacy','chunk',500,1000,'Saved guess',1,0)")
                if (version == 2) {
                    db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
                    db.execSQL("INSERT INTO pending_audio_deletions VALUES('queued-delete')")
                }
                db.version = version
            }
            NoteStore(context).use { store ->
                assertEquals(4, store.readableDatabase.version)
                assertEquals("Saved title", store.note("legacy")?.title)
                val (reviewed, unknown) = store.passages("legacy")
                assertEquals("Saved manual edit", reviewed.text)
                assertTrue(reviewed.reviewed)
                assertNull(reviewed.originalTranscriptText)
                assertFalse(store.excludePassage(reviewed))
                assertNull(unknown.originalTranscriptText)
                assertFalse(unknown.excluded)
                assertTrue(store.excludePassage(unknown))
                assertEquals("Saved manual edit", transferableTranscript(store.passages("legacy")))
                assertArrayEquals(audio, store.audioFile("legacy").readBytes())
                store.readableDatabase.rawQuery("SELECT note_id FROM pending_audio_deletions", null).use {
                    assertEquals(version == 2, it.moveToFirst())
                    if (version == 2) assertEquals("queued-delete", it.getString(0))
                }
            }
            assertTrue(SQLiteDatabase.deleteDatabase(path))
        }
    }

    private fun readyNote(store: NoteStore, audio: ByteArray, vararg texts: String): String {
        val id = store.create("en")
        store.audioFile(id).writeBytes(audio)
        val chunk = store.beginChunk(id, 0, false)
        store.finishChunk(chunk, 1000)
        store.complete(checkNotNull(store.nextChunk(id)), texts.mapIndexed { index, text ->
            Passage(noteId = id, chunkId = chunk, start = index * 250L, end = (index + 1) * 250L,
                text = text, uncertain = true)
        })
        store.duration(id, 1000)
        store.state(id, "READY")
        return id
    }
}
