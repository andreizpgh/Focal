package app.focal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.core.EndKind
import app.focal.data.NoteStore
import app.focal.data.StoredWindow
import app.focal.data.WindowState
import app.focal.recording.WindowFeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sin

/** Segmentation of saved PCM into persisted windows, with the relative-energy detector (no model). */
@RunWith(AndroidJUnit4::class)
class WindowFeedTest {
    private lateinit var root: File
    private lateinit var store: NoteStore

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "window-feed-").toFile()
        store = NoteStore(IsolatedContext(app, root))
    }

    @After fun cleanup() {
        if (::store.isInitialized) store.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    /** Tone where [speech] is true, digital silence elsewhere. */
    private fun pcm(ms: Long, speech: (Long) -> Boolean): ByteArray {
        val buffer = ByteBuffer.allocate((ms * 32).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until ms * 16) {
            val on = speech(i / 16)
            buffer.putShort(if (on) (sin(i * 2.0 * Math.PI * 220 / 16_000) * 8_000).toInt().toShort() else 0)
        }
        return buffer.array()
    }

    private fun recording(audio: ByteArray): String = store.create().also { id ->
        FileOutputStream(store.audioFile(id)).use { it.write(audio); it.fd.sync() }
    }

    private fun drain(feed: WindowFeed, durableMs: Long, finished: Boolean, legacy: Boolean = false) {
        var guard = 0
        while (feed.advance(durableMs, finished, legacy)) check(++guard < 1_000) { "The feed did not converge." }
    }

    private fun assertTiles(windows: List<StoredWindow>, endMs: Long) {
        assertEquals(0L, windows.first().ownStartMs)
        assertEquals(endMs, windows.last().ownEndMs)
        for ((previous, next) in windows.zipWithNext()) assertEquals(previous.ownEndMs, next.ownStartMs)
        assertEquals(windows.indices.toList(), windows.map { it.index })
        assertTrue(windows.all { it.ownEndMs - it.ownStartMs <= 20_000 })
    }

    @Test fun aPauseClosesTheOpenWindowAndFinishCoversTheWholeRecording() {
        // Speech, a short silence, then the user's Pause at 8 s and more speech after Resume.
        val audio = pcm(15_000) { it < 7_000 || it >= 8_000 }
        val id = recording(audio)
        val feed = WindowFeed(store, id) { null }
        drain(feed, 4_000, finished = false)
        assertTrue("Nothing is cut before the minimum window length", store.windows(id).isEmpty())
        store.addBreak(id, 8_000 * 16)
        drain(feed, 8_000, finished = false)
        val paused = store.windows(id)
        assertEquals(8_000L, paused.last().ownEndMs)
        assertEquals(EndKind.BREAK, paused.last().endKind)
        assertFalse(feed.complete)
        drain(feed, 15_000, finished = true)
        assertTrue(feed.complete)
        val windows = store.windows(id)
        assertTiles(windows, 15_000)
        assertEquals(EndKind.FINAL, windows.last().endKind)
        assertTrue("The window after a break gets no context from before it", windows.last().audioStartMs == 8_000L)
        assertTrue(windows.filter { it.state == WindowState.SILENT }.all { it.peak == 0 })
        assertEquals(windows.filter { it.state == WindowState.PENDING }, store.pendingWindows(id))
    }

    @Test fun segmentationResumesFromItsCheckpointAfterProcessDeath() {
        val audio = pcm(47_000) { (it / 1_000) % 9 != 8L && it !in 30_000L until 31_000L }
        val continuous = recording(audio)
        store.addBreak(continuous, 30_500 * 16)
        drain(WindowFeed(store, continuous) { null }, 47_000, finished = true)

        val resumed = recording(audio)
        drain(WindowFeed(store, resumed) { null }, 22_000, finished = false)
        val before = store.windows(resumed)
        assertTrue(before.isNotEmpty())
        // Capture went on to a Pause at 30.5 s and the process died; a new process builds a new feed
        // from the persisted checkpoint and replays the recorded break.
        store.addBreak(resumed, 30_500 * 16)
        drain(WindowFeed(store, resumed) { null }, 47_000, finished = true)
        val windows = store.windows(resumed)
        assertEquals(before, windows.take(before.size))
        assertTiles(windows, 47_000)
        assertTrue(windows.any { it.ownEndMs == 30_500L && it.endKind == EndKind.BREAK })
        assertTiles(store.windows(continuous), 47_000)
    }

    @Test fun aPauseAtTheCheckpointIsReplayedAfterProcessDeath() {
        // The Pause at 8 027 ms is not on the 10 ms slot grid: the slot it splits is loud before it and
        // silent after it, so the window after the Pause must not see the loud part.
        val audio = pcm(9_027) { it < 8_027 }
        val pause = 8_027L * 16
        val continuous = recording(audio)
        store.addBreak(continuous, pause)
        drain(WindowFeed(store, continuous) { null }, 9_027, finished = true)

        val resumed = recording(audio)
        store.addBreak(resumed, pause)
        drain(WindowFeed(store, resumed) { null }, 8_027, finished = false)
        assertEquals(8_027L, store.lastWindow(resumed)!!.ownEndMs)
        // A new process resumes after the window that ends exactly at the Pause.
        drain(WindowFeed(store, resumed) { null }, 9_027, finished = true)
        val expected = store.windows(continuous)
        assertEquals(WindowState.SILENT, expected.last().state)
        assertEquals(expected.map { it.plan() to it.state }, store.windows(resumed).map { it.plan() to it.state })
    }

    @Test fun migratedNotesAreSegmentedOnlyInsideTheirGaps() {
        val audio = pcm(30_000) { it % 4_000 < 3_000L }
        File(root, "files/audio").mkdirs()
        File(root, "files/audio/legacy.pcm").writeBytes(audio)
        store.close()
        SQLiteDatabase.deleteDatabase(File(root, "focal.db"))
        SQLiteDatabase.openOrCreateDatabase(File(root, "focal.db"), null).use { db ->
            db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,
                language TEXT NOT NULL,state TEXT NOT NULL,duration INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',
                automatic_title_chunk TEXT,speech_engine TEXT NOT NULL DEFAULT 'whisper')""")
            db.execSQL("""CREATE TABLE chunks(id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                start INTEGER NOT NULL,end INTEGER NOT NULL,overlap INTEGER NOT NULL,state TEXT NOT NULL,next_start INTEGER)""")
            db.execSQL("""CREATE TABLE passages(id INTEGER PRIMARY KEY AUTOINCREMENT,note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                chunk_id TEXT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL,
                text TEXT NOT NULL,uncertain INTEGER NOT NULL,reviewed INTEGER NOT NULL DEFAULT 0,
                original_transcript_text TEXT,excluded INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE TABLE pending_audio_deletions(note_id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO notes VALUES('legacy','Title',1,'en','INTERRUPTED',8000,'','a','whisper')")
            db.execSQL("INSERT INTO chunks VALUES('a','legacy',0,8000,0,'DONE',NULL)")
            db.execSQL("INSERT INTO chunks VALUES('b','legacy',12000,15000,0,'DONE',NULL)")
            db.execSQL("INSERT INTO passages VALUES(1,'legacy','a',0,8000,'Kept.',0,0,'Kept.',0)")
            db.version = 4
        }
        store = NoteStore(IsolatedContext(ApplicationProvider.getApplicationContext(), root))
        val feed = WindowFeed(store, "legacy") { null }
        drain(feed, 0, finished = true, legacy = true)
        assertTrue(feed.complete)
        assertTrue(store.gaps("legacy").isEmpty())
        val fresh = store.windows("legacy").filter { !it.legacy }
        val covered = fresh.map { it.ownStartMs to it.ownEndMs }
        assertEquals(8_000L, covered.first().first)
        assertTrue(covered.any { it.second == 12_000L })
        assertTrue(covered.any { it.first == 15_000L })
        assertEquals(30_000L, covered.last().second)
        assertTrue("No window reaches into transcribed audio", fresh.none { it.ownStartMs < 15_000 && it.ownEndMs > 12_000 })
        assertEquals(fresh.size, fresh.map { it.index }.toSet().size)
    }
}
