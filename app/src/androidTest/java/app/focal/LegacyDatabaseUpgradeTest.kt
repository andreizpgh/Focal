package app.focal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.Synthetic.assertSearchIndexIntact
import app.focal.Synthetic.plan
import app.focal.Synthetic.speech
import app.focal.core.EndKind
import app.focal.core.WindowGate
import app.focal.data.EditResult
import app.focal.data.Gap
import app.focal.data.LEGACY_WHISPER_ENGINE
import app.focal.data.NoteState
import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.ULTRA_ENGINE
import app.focal.data.WindowState
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
 * Installs over Alpha 9 and earlier must keep every saved note. Each test writes a database exactly as an
 * older schema (1 to 4) created it, then opens it through NoteStore as the app does and checks schema 5.
 */
@RunWith(AndroidJUnit4::class)
class LegacyDatabaseUpgradeTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "legacy-upgrade-").toFile()
        context = IsolatedContext(app, root)
    }

    @After fun cleanup() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun savedAudio(id: String, ms: Long): ByteArray {
        val audio = ByteArray((ms * 32).toInt()) { (it % 89).toByte() }
        File(context.filesDir, "audio/$id.pcm").apply { parentFile!!.mkdirs() }.writeBytes(audio)
        return audio
    }

    private fun legacyDatabase(version: Int, fill: SQLiteDatabase.() -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("focal.db"), null).use { db ->
            when (version) {
                1, 2 -> {
                    db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
                        language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL,error TEXT NOT NULL)""")
                    db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),
                        start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL)""")
                    db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id),
                        chunk_id TEXT NOT NULL REFERENCES chunks(id),start INTEGER NOT NULL,end INTEGER NOT NULL,
                        text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL)""")
                    if (version == 2) db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
                }
                3, 4 -> {
                    val engine = if (version == 4) ",speech_engine TEXT NOT NULL DEFAULT 'whisper'" else ""
                    val cursor = if (version == 4) ",next_start INTEGER" else ""
                    db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
                        language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',
                        automatic_title_chunk TEXT$engine)""")
                    db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL$cursor)""")
                    db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                        chunk_id TEXT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL,
                        text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0,
                        original_transcript_text TEXT,excluded INTEGER NOT NULL DEFAULT 0)""")
                    db.execSQL("CREATE INDEX chunks_queue ON chunks(note_id,state,start)")
                    db.execSQL("CREATE INDEX passages_note ON passages(note_id,start,id)")
                    db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
                }
                else -> error("No schema $version")
            }
            db.fill()
            db.version = version
        }
    }

    @Test fun versionsOneAndTwoKeepTextEditsTitlesAndAudio() {
        for (version in 1..2) {
            val audio = savedAudio("legacy", 1_000)
            savedAudio("unfinished", 1_500)
            legacyDatabase(version) {
                execSQL("INSERT INTO notes VALUES('legacy','Saved title',1,'ru','READY',1000,'')")
                execSQL("INSERT INTO chunks VALUES('chunk','legacy',0,1000,0,'DONE')")
                execSQL("INSERT INTO passages VALUES(1,'legacy','chunk',500,1000,'Saved guess',1,0)")
                execSQL("INSERT INTO passages VALUES(2,'legacy','chunk',0,500,'Saved manual edit',1,1)")
                execSQL("INSERT INTO notes VALUES('unfinished','Untitled note',2,'en','RECORDING',900,'')")
                execSQL("INSERT INTO chunks VALUES('done','unfinished',0,500,0,'DONE')")
                execSQL("INSERT INTO chunks VALUES('pending','unfinished',500,1000,0,'PENDING')")
                execSQL("INSERT INTO passages VALUES(3,'unfinished','done',0,500,'Early words.',0,0)")
                if (version == 2) execSQL("INSERT INTO pending_audio_deletions VALUES('queued-delete')")
            }
            NoteStore(context).use { store ->
                assertEquals(5, store.readableDatabase.version)
                val note = checkNotNull(store.note("legacy"))
                assertEquals("Saved title", note.title)
                assertTrue("A title of unknown provenance is never replaced", note.titleIsCustom)
                assertEquals("ru", note.language)
                assertEquals(NoteState.READY, note.state)
                assertEquals(1000L, note.duration)
                assertEquals(LEGACY_WHISPER_ENGINE, note.speechEngine)
                assertTrue(note.legacy)
                // Passages become paragraphs 1:1 in time order, with their ids and current text.
                val (edited, guess) = store.paragraphs("legacy")
                assertEquals(listOf(2L, 1L), listOf(edited.id, guess.id))
                assertEquals(listOf(0, 1), listOf(edited.ord, guess.ord))
                assertEquals("Saved manual edit", edited.text)
                assertEquals(0L to 500L, edited.startMs to edited.endMs)
                assertTrue(edited.edited)
                assertFalse(guess.edited)
                // Older text may already hold manual edits: its original is unknown, never invented.
                assertNull(edited.sourceText)
                assertNull(guess.sourceText)
                assertNull("No word times are invented", edited.wordsJson)
                assertFalse(edited.hidden || guess.hidden)
                assertEquals("Saved manual edit\n\nSaved guess", store.transcript("legacy"))
                assertEquals("Saved manual edit", store.library().first { it.id == "legacy" }.preview)
                assertEquals(listOf("legacy"), store.search("guess").map { it.note.id })
                assertEquals(listOf("legacy"), store.search("saved TITLE").map { it.note.id })
                assertTrue(store.gaps("legacy").isEmpty())
                assertEquals(listOf(WindowState.DONE), store.windows("legacy").map { it.state })
                assertNull(store.windows("legacy").single().endKind)

                // An unfinished note resumes exactly where coverage ends; its text gets an automatic title.
                val unfinished = checkNotNull(store.note("unfinished"))
                assertEquals(NoteState.INTERRUPTED, unfinished.state)
                assertEquals(1_500L, unfinished.duration)
                assertEquals("Early words", unfinished.title)
                assertFalse(unfinished.titleIsCustom)
                assertEquals(listOf(Gap("unfinished", 500, 1_500)), store.gaps("unfinished"))
                assertEquals(listOf("unfinished"), store.waitingNotes())

                store.writableDatabase.rawQuery("SELECT note_id FROM pending_audio_deletions", null).use {
                    assertEquals(version == 2, it.moveToFirst())
                }
                // Startup recovery leaves finished notes alone and completes the queued deletion.
                store.recover()
                assertEquals(NoteState.READY, store.note("legacy")?.state)
                assertEquals(listOf(edited, guess), store.paragraphs("legacy"))
                store.writableDatabase.rawQuery("SELECT count(*) FROM pending_audio_deletions", null).use {
                    it.moveToFirst(); assertEquals(0, it.getInt(0))
                }
                assertArrayEquals(audio, store.audioFile("legacy").readBytes())
                assertSearchIndexIntact(store)
            }
            assertTrue(SQLiteDatabase.deleteDatabase(context.getDatabasePath("focal.db")))
        }
    }

    @Test fun versionThreeExclusionsBecomeHiddenParagraphsAndOriginalsBecomeSources() {
        val audio = savedAudio("legacy", 1_000)
        legacyDatabase(3) {
            execSQL("INSERT INTO notes VALUES('legacy','Keep these words',1,'en','READY',1000,'','chunk')")
            execSQL("INSERT INTO chunks VALUES('chunk','legacy',0,1000,0,'DONE')")
            // The retired background-sound check stored its decision as excluded=1.
            execSQL("INSERT INTO passages VALUES(1,'legacy','chunk',0,500,'Retired background chatter',1,0,'Retired background chatter',1)")
            execSQL("INSERT INTO passages VALUES(2,'legacy','chunk',500,1000,'Keep these words, edited',0,1,'Keep these words',0)")
            execSQL("INSERT INTO notes VALUES('renamed','Untitled note',2,'en','READY',0,'',NULL)")
        }
        NoteStore(context).use { store ->
            assertEquals(5, store.readableDatabase.version)
            val (hidden, kept) = store.paragraphs("legacy")
            assertTrue(hidden.hidden)
            assertFalse(hidden.visible)
            assertFalse(kept.hidden)
            assertEquals("Retired background chatter", hidden.sourceText)
            assertEquals("Keep these words", kept.sourceText)
            assertTrue(kept.edited)
            assertTrue(kept.canRestore)
            assertEquals("Keep these words, edited", store.transcript("legacy"))
            assertTrue(store.search("chatter").isEmpty())
            assertEquals("Keep these words, edited", store.library().first { it.id == "legacy" }.preview)
            assertEquals("An automatic title stays automatic", false, store.note("legacy")!!.titleIsCustom)
            assertEquals("Keep these words", store.note("legacy")!!.title)
            assertEquals("", store.note("renamed")!!.title)
            assertFalse(store.note("renamed")!!.titleIsCustom)
        }
        NoteStore(context).use { store ->
            val hidden = store.paragraphs("legacy").first()
            assertTrue(hidden.hidden)
            assertTrue(store.search("chatter").isEmpty())
            assertTrue(store.unhideParagraph(hidden.id))
            assertEquals("Retired background chatter\n\nKeep these words, edited", store.transcript("legacy"))
            assertEquals(listOf("legacy"), store.search("chatter").map { it.note.id })
            val kept = store.paragraphs("legacy")[1]
            val restored = store.restoreOriginal(kept.id, kept.revision) as EditResult.Saved
            assertEquals("Keep these words", restored.paragraphs.single().text)
            assertArrayEquals(audio, store.audioFile("legacy").readBytes())
            assertSearchIndexIntact(store)
        }
    }

    @Test fun versionFourNotesResumeOnlyTheirUncoveredAudio() {
        savedAudio("ultra", 65_001)
        savedAudio("whisper-ready", 5_000)
        savedAudio("whisper-gaps", 5_000)
        legacyDatabase(4) {
            // Alpha 9 Ultra note: a done 30 s window with a hard-cut cursor and a pending one after it.
            execSQL("INSERT INTO notes VALUES('ultra','Original synthetic words',10,'auto','INTERRUPTED',59500,'Interrupted','w1','ultra-v1')")
            execSQL("INSERT INTO chunks VALUES('w1','ultra',0,30000,0,'DONE',29500)")
            execSQL("INSERT INTO chunks VALUES('w2','ultra',29500,59500,1,'PENDING',59000)")
            execSQL("INSERT INTO passages VALUES(7,'ultra','w1',0,30000,'Preserve the manual edit',0,1,'Original synthetic words',0)")
            // A finished Whisper note keeps its silence-gate gaps untouched.
            execSQL("INSERT INTO notes VALUES('whisper-ready','Gate title',11,'en','READY',5000,'','a','whisper')")
            execSQL("INSERT INTO chunks VALUES('a','whisper-ready',0,1000,0,'DONE',NULL)")
            execSQL("INSERT INTO chunks VALUES('b','whisper-ready',3000,4000,0,'DONE',NULL)")
            execSQL("INSERT INTO passages VALUES(8,'whisper-ready','a',0,1000,'First.',0,0,'First.',0)")
            execSQL("INSERT INTO passages VALUES(9,'whisper-ready','b',3000,4000,'Second.',0,0,'Second.',0)")
            // An unfinished Whisper note: earlier gaps are transcribed too, never skipped.
            execSQL("INSERT INTO notes VALUES('whisper-gaps','Gaps',12,'en','ERROR',4000,'Failed','c','whisper')")
            execSQL("INSERT INTO chunks VALUES('c','whisper-gaps',0,1000,0,'DONE',NULL)")
            execSQL("INSERT INTO chunks VALUES('d','whisper-gaps',2000,3000,0,'DONE',NULL)")
            execSQL("INSERT INTO chunks VALUES('e','whisper-gaps',3000,3500,0,'WRITING',NULL)")
            execSQL("INSERT INTO passages VALUES(10,'whisper-gaps','c',0,1000,'Before the gap.',0,0,'Before the gap.',0)")
            execSQL("INSERT INTO passages VALUES(11,'whisper-gaps','d',2000,3000,'After the gap.',0,0,'After the gap.',0)")
            // Audio already removed (or never saved): nothing is left to transcribe.
            execSQL("INSERT INTO notes VALUES('removed','Text only',13,'en','INTERRUPTED',2000,'','f','whisper')")
            execSQL("INSERT INTO chunks VALUES('f','removed',0,1000,0,'DONE',NULL)")
            execSQL("INSERT INTO chunks VALUES('g','removed',1000,2000,0,'PENDING',NULL)")
        }
        NoteStore(context).use { store ->
            val ultra = checkNotNull(store.note("ultra"))
            assertEquals(ULTRA_ENGINE, ultra.speechEngine)
            assertEquals(NoteState.INTERRUPTED, ultra.state)
            assertEquals(65_001L, ultra.duration)
            assertEquals(listOf(Gap("ultra", 30_000, 65_001)), store.gaps("ultra"))
            assertEquals(1, store.windows("ultra").size)
            val edited = store.paragraphs("ultra").single()
            assertEquals("Preserve the manual edit", edited.text)
            assertEquals("Original synthetic words", edited.sourceText)
            assertTrue(edited.edited)
            assertEquals(35_001L, store.progress("ultra").remainingMs)

            assertEquals(NoteState.READY, store.note("whisper-ready")!!.state)
            assertTrue(store.gaps("whisper-ready").isEmpty())

            assertEquals(NoteState.INTERRUPTED, store.note("whisper-gaps")!!.state)
            assertEquals(listOf(Gap("whisper-gaps", 1_000, 2_000), Gap("whisper-gaps", 3_000, 5_000)), store.gaps("whisper-gaps"))

            assertEquals(NoteState.READY, store.note("removed")!!.state)
            assertTrue(store.gaps("removed").isEmpty())
            assertEquals(setOf("ultra", "whisper-gaps"), store.waitingNotes().toSet())

            // Ultra transcribes the first gap of the Whisper note; its text lands between the old paragraphs.
            val index = store.nextWindowIndex("whisper-gaps")
            val window = store.addWindow("whisper-gaps", plan(index, 1_000, 2_000, EndKind.BREAK, EndKind.BREAK))
            assertEquals(listOf(Gap("whisper-gaps", 3_000, 5_000)), store.gaps("whisper-gaps"))
            store.beginDecode(window.id)
            assertTrue(store.commitDecoded(window.id, ULTRA_ENGINE, RawAsr("Recovered middle.", speech("Recovered middle.", startSec = 0.1))))
            assertEquals(listOf("Before the gap.", "Recovered middle.", "After the gap."), store.paragraphs("whisper-gaps").map { it.text })
            assertEquals(listOf(true, false, true), store.paragraphs("whisper-gaps").map { it.wordsJson == null })
            assertFalse("A gap remains", store.markReady("whisper-gaps"))
            store.addWindow("whisper-gaps", plan(index + 1, 3_000, 5_000, EndKind.BREAK, EndKind.BREAK), WindowGate.Skip.SILENT)
            assertTrue(store.markReady("whisper-gaps"))
            assertEquals(listOf(null, null, ULTRA_ENGINE, null), store.windows("whisper-gaps").map { if (it.legacy) null else it.engine })
            assertEquals(listOf(LEGACY_WHISPER_ENGINE, LEGACY_WHISPER_ENGINE), store.windows("whisper-gaps").filter { it.legacy }.map { it.engine })
            assertSearchIndexIntact(store)
        }
    }

    @Test fun queuedAudioDeletionsMarkNotesTextOnly() {
        savedAudio("queued", 1_000)
        legacyDatabase(4) {
            execSQL("INSERT INTO notes VALUES('queued','Text only',1,'en','READY',1000,'','c','whisper')")
            execSQL("INSERT INTO chunks VALUES('c','queued',0,1000,0,'DONE',NULL)")
            execSQL("INSERT INTO passages VALUES(1,'queued','c',0,1000,'Words stay.',0,0,'Words stay.',0)")
            execSQL("INSERT INTO pending_audio_deletions VALUES('queued')")
        }
        NoteStore(context).use { store ->
            assertFalse(store.note("queued")!!.audioPresent)
            store.recover()
            assertFalse(store.audioFile("queued").exists())
            assertEquals("Words stay.", store.transcript("queued"))
        }
    }
}
