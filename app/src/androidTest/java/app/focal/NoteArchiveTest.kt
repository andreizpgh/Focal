package app.focal

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.NoteArchive
import app.focal.data.NoteStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Offline export (Markdown ZIP) and backup/restore (JSON + optional PCM audio) round-trip tests. */
@RunWith(AndroidJUnit4::class)
class NoteArchiveTest {
    private lateinit var app: FocalApplication
    private lateinit var store: NoteStore
    private val created = mutableListOf<String>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.storageReady.await() }
        store = app.store
    }

    @After fun cleanup() {
        created.forEach { runCatching { store.delete(it) } }
    }

    @Test fun markdownExportWritesReadableNotesWithUniqueNames() {
        val now = 1_763_820_000_000L
        val a = store.insertNoteForTests(listOf("First paragraph.", "Second paragraph."), durationMs = 42_000, title = "Design review", created = now)
            .also { created += it }
        val b = store.insertNoteForTests(listOf("Another note with same title."), durationMs = 15_000, title = "Design review", created = now)
            .also { created += it }

        val out = ByteArrayOutputStream()
        val count = NoteArchive.exportMarkdownZip(store, out)
        assertTrue(count >= 2)

        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        val reviewFiles = entries.filterKeys { it.contains("design-review") && it.endsWith(".md") }
        assertEquals("Colliding note titles get unique filenames", 2, reviewFiles.size)
        val combined = reviewFiles.values.joinToString("\n")
        assertTrue(combined.contains("# Design review"))
        assertTrue(combined.contains("First paragraph.\n\nSecond paragraph."))
        assertTrue(combined.contains("Another note with same title."))
        assertNotNull(store.note(a))
        assertNotNull(store.note(b))
    }

    @Test fun backupAndRestorePreservesTextMetadataAudioAndSkipsDuplicates() {
        val id = store.insertNoteForTests(
            listOf("Quarterly architecture notes.", "Offline voice capture works locally."),
            durationMs = 6_000,
            title = "Quarterly notes",
            timedWords = true,
        ).also { created += it }
        val pcm = ByteArray(1024) { (it * 31 and 0xff).toByte() }
        store.audioFile(id).writeBytes(pcm)

        // Export with audio included.
        val withAudioOut = ByteArrayOutputStream()
        assertTrue(NoteArchive.exportBackupZip(store, withAudioOut, includeAudio = true) >= 1)
        val withAudioBytes = withAudioOut.toByteArray()

        // Re-importing while the note still exists must be a non-destructive no-op for that ID.
        val noop = NoteArchive.importBackupZip(store, ByteArrayInputStream(withAudioBytes), app.cacheDir)
        assertEquals(0, noop.imported)
        assertTrue(noop.skipped >= 1)

        // Delete the note permanently, then restore it from the archive.
        store.delete(id)
        assertFalse(store.noteExists(id))
        assertFalse(store.audioFile(id).exists())

        val restored = NoteArchive.importBackupZip(store, ByteArrayInputStream(withAudioBytes), app.cacheDir)
        assertEquals(1, restored.imported)
        val note = store.note(id)
        assertNotNull(note)
        assertEquals("Quarterly notes", note!!.title)
        assertTrue(note.titleIsCustom)
        assertTrue(note.audioPresent)
        assertArrayEquals(pcm, store.audioFile(id).readBytes())
        assertEquals("Quarterly architecture notes.\n\nOffline voice capture works locally.", store.transcript(id))
        assertTrue("Restored notes are indexed for FTS search", store.search("Quarterly").any { it.note.id == id })

        // Export text-only (includeAudio = false), delete, and restore as text-only.
        val textOnlyOut = ByteArrayOutputStream()
        NoteArchive.exportBackupZip(store, textOnlyOut, includeAudio = false)
        store.delete(id)
        val restoredTextOnly = NoteArchive.importBackupZip(store, ByteArrayInputStream(textOnlyOut.toByteArray()), app.cacheDir)
        assertEquals(1, restoredTextOnly.imported)
        val textNote = store.note(id)!!
        assertFalse(textNote.audioPresent)
        assertFalse(store.audioFile(id).exists())
        assertEquals("Quarterly architecture notes.\n\nOffline voice capture works locally.", store.transcript(id))
    }

    @Test fun invalidBackupIsRejectedCleanly() {
        val badZip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("nope".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        try {
            NoteArchive.importBackupZip(store, ByteArrayInputStream(badZip), app.cacheDir)
            fail("Expected IOException for backup missing manifest")
        } catch (_: IOException) {
            // expected
        }
    }
}
