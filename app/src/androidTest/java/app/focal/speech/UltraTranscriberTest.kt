package app.focal.speech

import androidx.test.core.app.ApplicationProvider
import app.focal.FocalApplication
import app.focal.data.Chunk
import app.focal.data.NoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UltraTranscriberTest {
    @Test fun commitsExactSpansAndKeepsManualTextAcrossAnOverlap() {
        val pcm = pcm(1_500, ::sample)
        withAudio(pcm) { store, id ->
            val first = pending(store, id, 0, 1_000)
            val next = pending(store, id, 500, 1_500, overlap = true)
            val owner = Thread.currentThread()
            var opens = 0
            var calls = 0
            var closes = 0
            UltraTranscriber { threads, checkCancelled ->
                assertSame(owner, Thread.currentThread())
                assertEquals(2, threads)
                checkCancelled()
                opens++
                fakeDecoder(decodeBlock = { samples ->
                    assertSame(owner, Thread.currentThread())
                    assertEquals(16_000, samples.size)
                    val start = if (calls == 0) 0 else 8_000
                    assertTrue(samples.indices.all { samples[it] == sample(start + it) / 32768f })
                    if (calls++ == 0) "Original words repeated" else "repeated then new words"
                }, onClose = { assertSame(owner, Thread.currentThread()); closes++ })
            }.use { engine ->
                runBlocking { engine.transcribe(store, first, "ru", 4) }
                val passage = store.passages(id).single()
                assertEquals(0L, passage.start)
                assertEquals(1_000L, passage.end)
                assertFalse(passage.uncertain)
                assertEquals("Original words repeated", passage.originalTranscriptText)
                store.edit(passage, "Manually edited words repeated")
                runBlocking { engine.transcribe(store, next, "ru", 2) }
            }
            val passages = store.passages(id)
            assertEquals(listOf("Manually edited words repeated", "repeated then new words"), passages.map { it.text })
            assertEquals("Original words repeated", passages[0].originalTranscriptText)
            assertTrue(passages[0].reviewed)
            assertEquals(500L, passages[1].start)
            assertEquals(1_500L, passages[1].end)
            assertTrue("A possible overlap repeat remains visible for review.", passages[1].uncertain)
            assertEquals(passages[1].text, passages[1].originalTranscriptText)
            assertEquals(listOf("DONE", "DONE"), store.chunks(id).map { it.state })
            assertArrayEquals(pcm, store.audioFile(id).readBytes())
            assertEquals(1, opens)
            assertEquals(2, calls)
            assertEquals(1, closes)
        }
    }

    @Test fun constantPcmSkipsTheModelAndBlankVaryingPcmKeepsAMissingRangeCue() {
        val pcm = pcm(2_000) { if (it < 16_000) (-151).toShort() else sample(it) }
        withAudio(pcm) { store, id ->
            val constant = pending(store, id, 0, 1_000)
            val varying = pending(store, id, 1_000, 2_000)
            var opens = 0
            UltraTranscriber { _, _ ->
                opens++
                fakeDecoder(decodeBlock = { "  " })
            }.use { engine ->
                runBlocking { engine.transcribe(store, constant, "ru", 2) }
                assertEquals(0, opens)
                assertTrue(store.passages(id).isEmpty())
                runBlocking { engine.transcribe(store, varying, "ru", 2) }
            }
            val cue = store.passages(id).single()
            assertEquals(1, opens)
            assertEquals(varying.id, cue.chunkId)
            assertEquals(1_000L, cue.start)
            assertEquals(2_000L, cue.end)
            assertEquals("", cue.text)
            assertTrue(cue.uncertain)
            assertEquals(0, store.pendingCount(id))
        }
    }

    @Test fun cancellationDuringDecodeKeepsChunkPendingAndClosesOnTheOwnerAfterReturn() {
        val pcm = pcm(1_000, ::sample)
        withAudio(pcm) { store, id ->
            val chunk = pending(store, id, 0, 1_000)
            val worker = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val adapter = AtomicReference<UltraTranscriber>()
            try {
                val future = worker.submit {
                    val owner = Thread.currentThread()
                    UltraTranscriber { _, _ ->
                        assertSame(owner, Thread.currentThread())
                        fakeDecoder(decodeBlock = {
                            assertSame(owner, Thread.currentThread())
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            returned.set(true)
                            "Canceled words"
                        }, onClose = {
                            assertSame(owner, Thread.currentThread())
                            assertTrue(returned.get())
                            closed.set(true)
                        })
                    }.use { engine ->
                        adapter.set(engine)
                        runBlocking { engine.transcribe(store, chunk, "ru", 2) }
                    }
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                adapter.get().cancel()
                assertFalse(closed.get())
                assertEquals(chunk, store.nextChunk(id))
                release.countDown()
                val failure = assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is CancellationException)
                assertTrue(closed.get())
                assertEquals(chunk, store.nextChunk(id))
                assertTrue(store.passages(id).isEmpty())
                assertArrayEquals(pcm, store.audioFile(id).readBytes())
            } finally {
                release.countDown()
                worker.shutdownNow()
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun fakeDecoder(decodeBlock: (FloatArray) -> String, onClose: () -> Unit = {}) =
        object : SavedAudioPreview.Decoder {
            override fun decode(samples: FloatArray): String = decodeBlock(samples)
            override fun close() = onClose()
        }

    private fun withAudio(pcm: ByteArray, block: (NoteStore, String) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await() }
        val id = app.store.create("ru")
        try {
            app.store.audioFile(id).writeBytes(pcm)
            block(app.store, id)
        } finally {
            app.store.delete(id)
        }
    }

    private fun pending(store: NoteStore, id: String, start: Long, end: Long, overlap: Boolean = false): Chunk {
        val chunk = store.beginChunk(id, start, overlap)
        store.finishChunk(chunk, end)
        return store.chunks(id).single { it.id == chunk }
    }

    private fun pcm(milliseconds: Int, sample: (Int) -> Short): ByteArray =
        ByteBuffer.allocate(milliseconds * 32).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(milliseconds * 16) { putShort(sample(it)) }
        }.array()

    private fun sample(index: Int): Short = (index % 31 - 15).toShort()
}
