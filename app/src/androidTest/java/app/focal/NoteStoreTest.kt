package app.focal

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.speech.NativeWhisper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NoteStoreTest {
    private lateinit var store: NoteStore
    private val created = mutableListOf<String>()
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        kotlinx.coroutines.runBlocking { app.initialized.await() }
        store = app.store
    }
    @After fun cleanup() { created.forEach { store.delete(it) } }
    private fun note() = store.create("ru").also { created += it }

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
    @Test fun nativeEngineRejectsAnInvalidModelWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "invalid-model.bin").apply { writeBytes(ByteArray(32)) }
        try {
            try { NativeWhisper.open(file.absolutePath); fail("Invalid model was accepted") }
            catch (_: IllegalStateException) { }
        } finally { file.delete() }
    }
}
