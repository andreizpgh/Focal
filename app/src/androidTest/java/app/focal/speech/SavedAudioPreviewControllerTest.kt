package app.focal.speech

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.FocalApplication
import app.focal.data.Chunk
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SavedAudioPreviewControllerTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private val store get() = app.store
    private val notes = mutableListOf<String>()
    private lateinit var scope: CoroutineScope
    private lateinit var controller: SavedAudioPreviewController

    @Before fun setUp() {
        runBlocking { app.storageReady.await() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After fun cleanUp() {
        if (::controller.isInitialized) {
            onMain { scope.cancel(); controller.close() }
            await { it == null }
        } else if (::scope.isInitialized) scope.cancel()
        notes.forEach(store::delete)
    }

    @Test fun readyPreviewIsPublishedOnMainAndPreservesTheOriginal() {
        val id = readyNote()
        val original = snapshot(id)
        val closes = AtomicInteger()
        controller = SavedAudioPreviewController(store, scope) {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    assertFalse(Looper.myLooper() == Looper.getMainLooper())
                    return "Alternative synthetic text"
                }
                override fun close() { closes.incrementAndGet() }
            }
        }
        val publicationOnMain = CopyOnWriteArrayList<Boolean>()
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            observerScope.launch {
                controller.state.drop(1).collect { publicationOnMain += Looper.myLooper() == Looper.getMainLooper() }
            }
            assertTrue(onMain { controller.start(id) })
            val ready = checkNotNull(await { it?.phase == PreviewPhase.Ready })
            assertEquals(id, ready.noteId)
            assertEquals(original.note.title, ready.title)
            assertEquals(16_000, ready.completedFrames)
            assertEquals(16_000, ready.totalFrames)
            assertEquals("Alternative synthetic text", ready.segments.single().text)
            assertTrue(ready.segments.single().uncertain)
            assertEquals(1, closes.get())
            assertTrue(publicationOnMain.isNotEmpty() && publicationOnMain.all { it })
            assertReleased(id)
            assertOriginal(id, original)
            onMain { controller.stop() }
            assertNull(controller.state.value)
        } finally { observerScope.cancel() }
    }

    @Test fun stopWaitsForSynchronousDecodeBeforeDiscardingThePreview() {
        checkBlockedCancellation(clearViewModel = false)
    }

    @Test fun clearingTheOwnerScopeKeepsTheClaimUntilDecodeReturns() {
        checkBlockedCancellation(clearViewModel = true)
    }

    @Test fun failedDecodeReleasesTheClaimAndPreservesTheOriginal() {
        val id = readyNote()
        val original = snapshot(id)
        val closes = AtomicInteger()
        controller = SavedAudioPreviewController(store, scope) {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String = error("Synthetic decoder failure")
                override fun close() { closes.incrementAndGet() }
            }
        }
        assertTrue(onMain { controller.start(id) })
        val failed = checkNotNull(await { it?.phase == PreviewPhase.Failed })
        assertEquals("Synthetic decoder failure", failed.error)
        assertTrue(failed.segments.isEmpty())
        assertEquals(1, closes.get())
        assertReleased(id)
        assertOriginal(id, original)
        onMain { controller.stop() }
        assertNull(controller.state.value)
    }

    @Test fun busyOrUnfinishedSourceNeverOpensTheDecoder() {
        val id = readyNote()
        val opens = AtomicInteger()
        controller = SavedAudioPreviewController(store, scope) {
            opens.incrementAndGet()
            error("Unfinished source must not open the decoder.")
        }
        val capture = checkNotNull(SessionOperations.reserveSession(capture = true, noteId = null))
        try {
            assertFalse(onMain { controller.start(id) })
            assertNull(controller.state.value)
        } finally { capture.release() }
        store.finishChunk(store.beginChunk(id, 0, false), 1000)
        val original = snapshot(id)
        assertTrue(onMain { controller.start(id) })
        await { it?.phase == PreviewPhase.Failed }
        assertEquals(0, opens.get())
        assertReleased(id)
        assertOriginal(id, original)
    }

    @Test fun canceledScopeBeforeTheLaunchBodyReleasesTheReservation() {
        val id = readyNote()
        val original = snapshot(id)
        val opens = AtomicInteger()
        controller = SavedAudioPreviewController(store, scope) {
            opens.incrementAndGet()
            error("Canceled launch must not open the decoder.")
        }
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            observerScope.launch {
                controller.state.first { it?.phase == PreviewPhase.Loading }
                scope.cancel()
            }
            assertTrue(onMain { controller.start(id) })
            await { it == null }
            assertEquals(0, opens.get())
            assertReleased(id)
            assertOriginal(id, original)
        } finally { observerScope.cancel() }
    }

    private fun checkBlockedCancellation(clearViewModel: Boolean) {
        val id = readyNote(durationMs = 17_000)
        val original = snapshot(id)
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val returned = AtomicBoolean()
        val safeClose = AtomicBoolean()
        val decodeCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        controller = SavedAudioPreviewController(store, scope) {
            val owner = Thread.currentThread()
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    decodeCalls.incrementAndGet()
                    entered.countDown()
                    check(allowReturn.await(10, TimeUnit.SECONDS)) { "Test did not release the fake decoder." }
                    returned.set(true)
                    return "Canceled synthetic text"
                }
                override fun close() {
                    safeClose.set(returned.get() && owner === Thread.currentThread())
                    closeCalls.incrementAndGet()
                }
            }
        }
        try {
            assertTrue(onMain { controller.start(id) })
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            onMain {
                if (clearViewModel) {
                    scope.cancel()
                    controller.close()
                } else controller.stop()
            }
            assertEquals(PreviewPhase.Stopping, controller.state.value?.phase)
            assertTrue(controller.state.value?.segments.orEmpty().isEmpty())
            assertEquals(0, closeCalls.get())
            assertFalse(onMain { controller.start(id) })
            assertBlocked(id)
            allowReturn.countDown()
            await { it == null }
            assertEquals(1, decodeCalls.get())
            assertEquals(1, closeCalls.get())
            assertTrue(safeClose.get())
            assertReleased(id)
            assertOriginal(id, original)
        } finally { allowReturn.countDown() }
    }

    private fun assertBlocked(id: String) {
        val capture = SessionOperations.reserveSession(capture = true, noteId = null)
        capture?.release()
        assertNull(capture)
        val removal = SessionOperations.tryDestructive(id)
        removal?.release()
        assertNull(removal)
    }

    private fun assertReleased(id: String) {
        checkNotNull(SessionOperations.reserveSession(capture = true, noteId = null)).release()
        checkNotNull(SessionOperations.tryDestructive(id)).release()
    }

    private fun readyNote(durationMs: Long = 1000): String {
        val id = store.create("en").also(notes::add)
        val pcm = ByteArray((durationMs * 32).toInt())
        for (index in pcm.indices step 2) pcm[index] = (1 + index / 2 % 3).toByte()
        store.audioFile(id).writeBytes(pcm)
        val chunk = store.beginChunk(id, 0, false)
        store.finishChunk(chunk, durationMs)
        store.complete(checkNotNull(store.nextChunk(id)), listOf(
            Passage(noteId = id, chunkId = chunk, start = 0, end = durationMs,
                text = "Original synthetic text", uncertain = true)
        ))
        store.edit(store.passages(id).single(), "Keep the manually reviewed words.")
        store.rename(id, "Saved audio preview fixture")
        store.duration(id, durationMs)
        store.state(id, "READY")
        return id
    }

    private data class Original(
        val note: Note, val chunks: List<Chunk>, val passages: List<Passage>, val pcm: ByteArray,
    )

    private fun snapshot(id: String) = Original(
        checkNotNull(store.note(id)), store.chunks(id), store.passages(id), store.audioFile(id).readBytes()
    )

    private fun assertOriginal(id: String, expected: Original) {
        val actual = snapshot(id)
        assertEquals(expected.note, actual.note)
        assertEquals(expected.chunks, actual.chunks)
        assertEquals(expected.passages, actual.passages)
        assertArrayEquals(expected.pcm, actual.pcm)
    }

    private fun await(predicate: (PreviewState?) -> Boolean): PreviewState? =
        runBlocking { withTimeout(10_000) { controller.state.first(predicate) } }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
