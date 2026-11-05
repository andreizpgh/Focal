package app.focal

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.LEGACY_WHISPER_ENGINE
import app.focal.data.NoteStore
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

/**
 * Installs over Alpha 9 and earlier must keep every saved note. Each test writes a database in an
 * older schema, then opens it through NoteStore exactly as the app does. Schema 3 without exclusions
 * is covered by UltraQueueStoreTest.
 */
@RunWith(AndroidJUnit4::class)
class LegacyDatabaseUpgradeTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "legacy-upgrade-").toFile()
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

    private fun savedAudio(): ByteArray {
        val audio = ByteArray(32_000) { (it % 89).toByte() }
        File(context.filesDir, "audio/legacy.pcm").apply { parentFile!!.mkdirs() }.writeBytes(audio)
        return audio
    }

    @Test fun versionsOneAndTwoUpgradeWithoutChangingTextEditsOrAudio() {
        for (version in 1..2) {
            val path = context.getDatabasePath("focal.db")
            val audio = savedAudio()
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
                val note = checkNotNull(store.note("legacy"))
                assertEquals("Saved title", note.title)
                assertEquals("ru", note.language)
                assertEquals("READY", note.state)
                assertEquals(1000L, note.duration)
                assertEquals(LEGACY_WHISPER_ENGINE, note.speechEngine)
                val (edited, unknown) = store.passages("legacy")
                assertEquals("Saved manual edit", edited.text)
                assertTrue(edited.reviewed)
                assertEquals("Saved guess", unknown.text)
                assertFalse(unknown.reviewed)
                // Older text may already hold manual edits: its original is unknown, never invented.
                assertNull(edited.originalTranscriptText)
                assertNull(unknown.originalTranscriptText)
                assertFalse(edited.excluded)
                assertFalse(unknown.excluded)
                assertEquals("Saved manual edit\n\nSaved guess", transferableTranscript(store.passages("legacy")))
                assertEquals("Saved manual edit", store.notes().single().preview)
                assertEquals(1, store.notes().single().reviewCount)
                assertTrue(store.notes("guess").any { it.id == "legacy" })
                store.readableDatabase.rawQuery("SELECT automatic_title_chunk FROM notes WHERE id='legacy'", null).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
                }
                assertTrue(store.chunks("legacy").single().let { it.state == "DONE" && it.nextStart == null })
                store.readableDatabase.rawQuery("SELECT note_id FROM pending_audio_deletions", null).use {
                    assertEquals(version == 2, it.moveToFirst())
                    if (version == 2) assertEquals("queued-delete", it.getString(0))
                }
                // Startup recovery leaves a finished legacy note alone and completes the queued deletion.
                store.recover()
                assertEquals("READY", store.note("legacy")?.state)
                assertEquals(listOf(edited, unknown), store.passages("legacy"))
                assertEquals(1, store.chunks("legacy").size)
                store.readableDatabase.rawQuery("SELECT count(*) FROM pending_audio_deletions", null).use {
                    it.moveToFirst(); assertEquals(0, it.getInt(0))
                }
                assertArrayEquals(audio, store.audioFile("legacy").readBytes())
            }
            assertTrue(SQLiteDatabase.deleteDatabase(path))
        }
    }

    @Test fun versionThreeBackgroundSoundExclusionsStayHiddenAfterUpgradeAndReopen() {
        val audio = savedAudio()
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
            db.execSQL("INSERT INTO notes VALUES('legacy','Saved title',1,'en','READY',1000,'',NULL)")
            db.execSQL("INSERT INTO chunks VALUES('chunk','legacy',0,1000,0,'DONE')")
            // The retired background-sound check stored its decision as excluded=1.
            db.execSQL("INSERT INTO passages VALUES(1,'legacy','chunk',0,500,'Retired background chatter',1,0,'Retired background chatter',1)")
            db.execSQL("INSERT INTO passages VALUES(2,'legacy','chunk',500,1000,'Keep these words',0,0,'Keep these words',0)")
            db.version = 3
        }
        NoteStore(context).use { store ->
            assertEquals(4, store.readableDatabase.version)
            val (hidden, kept) = store.passages("legacy")
            assertTrue(hidden.excluded)
            assertFalse(kept.excluded)
            assertEquals("Retired background chatter", hidden.originalTranscriptText)
            assertEquals("Keep these words", transferableTranscript(store.passages("legacy")))
            assertTrue(store.notes("chatter").isEmpty())
            assertEquals("Keep these words", store.notes().single().preview)
            assertEquals(0, store.notes().single().reviewCount)
            assertEquals(listOf("Keep these words"), store.recentPassages("legacy", 1000).map { it.text })
        }
        NoteStore(context).use { store ->
            val hidden = store.passages("legacy").first()
            assertTrue(hidden.excluded)
            assertTrue(store.notes("chatter").isEmpty())
            assertTrue(store.restorePassage(hidden))
            assertEquals("Retired background chatter\n\nKeep these words", transferableTranscript(store.passages("legacy")))
            assertTrue(store.notes("chatter").any { it.id == "legacy" })
            assertEquals("Saved title", store.note("legacy")?.title)
            assertArrayEquals(audio, store.audioFile("legacy").readBytes())
        }
    }
}
