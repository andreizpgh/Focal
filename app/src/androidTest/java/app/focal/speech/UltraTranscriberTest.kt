package app.focal.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.focal.IsolatedContext
import app.focal.Synthetic
import app.focal.core.EndKind
import app.focal.data.NoteStore
import app.focal.data.StoredWindow
import app.focal.data.ULTRA_ENGINE
import app.focal.data.WindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UltraTranscriberTest {
    @Test fun decodesEachWindowsAudioRangeAndCommitsItsTokens() {
        val pcm = pcm(14_000, ::sample)
        withAudio(pcm) { store, id ->
            val first = pending(store, id, Synthetic.plan(0, 0, 6_000, EndKind.PAUSE))
            val owner = Thread.currentThread()
            var opens = 0
            var calls = 0
            var closes = 0
            UltraTranscriber { threads, checkCancelled ->
                assertSame(owner, Thread.currentThread())
                assertEquals(4, threads)
                checkCancelled()
                opens++
                fakeDecoder(decodeBlock = { samples ->
                    assertSame(owner, Thread.currentThread())
                    // A pause-cut window is decoded with its right context; the next one with its lead-in.
                    val start = if (calls == 0) 0 else 5_750 * 16
                    assertEquals(if (calls == 0) 7_000 * 16 else 8_250 * 16, samples.size)
                    assertTrue(samples.indices.all { samples[it] == sample(start + it) / 32768f })
                    if (calls++ == 0) result(" Saved" to 0.5, " words." to 0.9, " Context" to 6.5)
                    else result(" More" to 0.6, " text." to 1.0)
                }, onClose = { assertSame(owner, Thread.currentThread()); closes++ })
            }.use { engine ->
                runBlocking { engine.transcribe(store, first, 4) }
                assertEquals(listOf("Saved words."), store.paragraphs(id).map { it.text })
                val next = pending(store, id, Synthetic.plan(1, 6_000, 14_000, EndKind.FINAL, EndKind.PAUSE))
                runBlocking { engine.transcribe(store, next, 4) }
            }
            val windows = store.windows(id)
            assertTrue(windows.all { it.state == WindowState.DONE && it.engine == ULTRA_ENGINE })
            assertEquals("Saved words. Context", windows[0].rawText)
            assertEquals(3, store.windowTokens(windows[0].id)!!.size)
            // The word in the right context belongs to the next window and is not kept twice.
            assertEquals("Saved words. More text.", store.paragraphs(id).joinToString(" ") { it.text })
            assertArrayEquals(pcm, store.audioFile(id).readBytes())
            assertEquals(1, opens)
            assertEquals(2, calls)
            assertEquals(1, closes)
        }
    }

    @Test fun cancellationDuringDecodeKeepsTheWindowPendingAndClosesOnTheOwnerAfterReturn() {
        val pcm = pcm(1_000, ::sample)
        withAudio(pcm) { store, id ->
            val window = pending(store, id, Synthetic.plan(0, 0, 1_000, EndKind.FINAL))
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
                            result(" Canceled" to 0.1)
                        }, onClose = {
                            assertSame(owner, Thread.currentThread())
                            assertTrue(returned.get())
                            closed.set(true)
                        })
                    }.use { engine ->
                        adapter.set(engine)
                        runBlocking { engine.transcribe(store, window, 2) }
                    }
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                adapter.get().cancel()
                assertFalse(closed.get())
                release.countDown()
                val failure = assertThrows(ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is CancellationException)
                assertTrue(closed.get())
                assertEquals(listOf(window.id), store.pendingWindows(id).map { it.id })
                assertTrue(store.paragraphs(id).isEmpty())
                assertArrayEquals(pcm, store.audioFile(id).readBytes())
            } finally {
                release.countDown()
                worker.shutdownNow()
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun result(vararg tokens: Pair<String, Double>) = AsrResult(
        tokens.joinToString("") { it.first }.trim(), tokens.map { it.first },
        FloatArray(tokens.size) { tokens[it].second.toFloat() }, FloatArray(tokens.size) { 0.24f },
        FloatArray(tokens.size) { -0.01f },
    )

    private fun fakeDecoder(decodeBlock: (FloatArray) -> AsrResult, onClose: () -> Unit = {}) =
        object : SpeechDecoder {
            override fun decode(samples: FloatArray): AsrResult = decodeBlock(samples)
            override fun close() = onClose()
        }

    private fun withAudio(pcm: ByteArray, block: (NoteStore, String) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "transcriber-").toFile()
        try {
            NoteStore(IsolatedContext(app, root)).use { store ->
                val id = store.create()
                File(store.audioDir, "$id.pcm").writeBytes(pcm)
                store.duration(id, pcm.size / 32L)
                block(store, id)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pending(store: NoteStore, id: String, plan: app.focal.core.WindowPlan): StoredWindow =
        checkNotNull(store.beginDecode(store.addWindow(id, plan).id))

    private fun pcm(milliseconds: Int, sample: (Int) -> Short): ByteArray =
        ByteBuffer.allocate(milliseconds * 32).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(milliseconds * 16) { putShort(sample(it)) }
        }.array()

    private fun sample(index: Int): Short = (index % 31 - 15).toShort()
}
