package app.focal.speech

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.FocalApplication
import app.focal.data.Chunk
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.data.transferableTranscript
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NoiseReviewControllerTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private val store get() = app.store
    private lateinit var scope: CoroutineScope
    private lateinit var controller: NoiseReviewController
    private val created = mutableListOf<String>()

    @Before fun setup() {
        runBlocking { app.storageReady.await() }
        scope = ownerScope()
    }

    @After fun cleanup() {
        onMain {
            if (::controller.isInitialized) controller.close()
            if (::scope.isInitialized) scope.cancel()
        }
        if (::controller.isInitialized) await(controller) { it == null }
        created.forEach(store::delete)
    }

    @Test fun readySuggestionsDoNotChangeCopyAndProgressPublishesOnMain() {
        val id = readyNote()
        val before = snapshot(id)
        val enteredSecond = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val closed = AtomicInteger()
        val publishedOnMain = CopyOnWriteArrayList<Boolean>()
        controller = NoiseReviewController(store, scope) {
            val owner = Thread.currentThread()
            var index = 0
            object : SpeechPresenceDetector {
                override fun hasSpeech(samples: FloatArray, checkCancelled: () -> Unit): Boolean {
                    check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
                    checkCancelled()
                    val expected = ByteBuffer.wrap(before.pcm, index * BYTES_PER_SECOND, BYTES_PER_SECOND)
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    check(samples.size == expected.remaining())
                    check(samples.all { it == expected.get() / 32768f }) { "Detector received different PCM." }
                    if (++index == 1) return false
                    enteredSecond.countDown()
                    check(releaseSecond.await(10, TimeUnit.SECONDS))
                    return true
                }

                override fun close() {
                    check(Thread.currentThread() === owner)
                    closed.incrementAndGet()
                }
            }
        }
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            observer.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.state.collect { if (it != null) publishedOnMain += Looper.myLooper() == Looper.getMainLooper() }
            }
            assertTrue(onMain { controller.start(id) })
            assertTrue(enteredSecond.await(10, TimeUnit.SECONDS))
            val progress = checkNotNull(await(controller) { it != null && it.phase == NoiseReviewPhase.Checking && it.checked == 1 })
            assertEquals(3, progress.total)
            assertTrue(progress.proposals.isEmpty())
            releaseSecond.countDown()
            val ready = checkNotNull(await(controller) { it?.phase == NoiseReviewPhase.Ready })
            assertEquals(3, ready.checked)
            assertEquals(before.passages.take(1), ready.proposals)
            assertEquals(1, closed.get())
            assertTrue(publishedOnMain.isNotEmpty() && publishedOnMain.all { it })
            assertUnchanged(id, before)
            assertEquals(transferableTranscript(before.passages), transferableTranscript(store.passages(id)))
            assertReleased(id)

            assertTrue(store.excludePassage(ready.proposals.single()))
            onMain { controller.resolved(ready.proposals.single().id) }
            assertTrue(controller.state.value?.proposals.orEmpty().isEmpty())
            assertEquals(transferableTranscript(before.passages.drop(1)), transferableTranscript(store.passages(id)))
            assertEquals(before.passages.first().copy(excluded = true), store.passages(id).first())
        } finally {
            releaseSecond.countDown()
            observer.cancel()
        }
    }

    @Test fun laterDetectorFailureDiscardsEarlierProposalsAndReleasesOwnership() {
        val id = readyNote()
        val before = snapshot(id)
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        controller = NoiseReviewController(store, scope) {
            object : SpeechPresenceDetector {
                override fun hasSpeech(samples: FloatArray, checkCancelled: () -> Unit): Boolean {
                    if (calls.incrementAndGet() == 1) return false
                    error("Synthetic detector failure")
                }
                override fun close() { closed.incrementAndGet() }
            }
        }
        assertTrue(onMain { controller.start(id) })
        val failed = checkNotNull(await(controller) { it?.phase == NoiseReviewPhase.Failed })
        assertEquals("Synthetic detector failure", failed.error)
        assertTrue(failed.proposals.isEmpty())
        assertEquals(2, calls.get())
        assertEquals(1, closed.get())
        assertReleased(id)
        assertUnchanged(id, before)
    }

    @Test fun stopRetainsOwnershipThroughDetectorReturnAndClose() = verifyCancellation(cancelOwner = false)

    @Test fun clearedOwnerReleasesAfterCloseAndAllowsARecreatedOwner() = verifyCancellation(cancelOwner = true)

    private fun verifyCancellation(cancelOwner: Boolean) {
        val id = readyNote()
        val before = snapshot(id)
        val enteredDetect = CountDownLatch(1)
        val allowDetect = CountDownLatch(1)
        val enteredClose = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        controller = NoiseReviewController(store, scope) {
            val owner = Thread.currentThread()
            object : SpeechPresenceDetector {
                override fun hasSpeech(samples: FloatArray, checkCancelled: () -> Unit): Boolean {
                    check(Thread.currentThread() === owner)
                    if (calls.incrementAndGet() == 1) return false
                    enteredDetect.countDown()
                    check(allowDetect.await(10, TimeUnit.SECONDS))
                    return false // A synchronous implementation may return after cancellation.
                }
                override fun close() {
                    check(Thread.currentThread() === owner)
                    enteredClose.countDown()
                    check(allowClose.await(10, TimeUnit.SECONDS))
                    closed.incrementAndGet()
                }
            }
        }
        val recreatedScope = ownerScope()
        val recreated = NoiseReviewController(store, recreatedScope) {
            object : SpeechPresenceDetector {
                override fun hasSpeech(samples: FloatArray, checkCancelled: () -> Unit) = true
                override fun close() = Unit
            }
        }
        try {
            assertTrue(onMain { controller.start(id) })
            assertTrue(enteredDetect.await(10, TimeUnit.SECONDS))
            onMain {
                if (cancelOwner) { scope.cancel(); controller.close() } else controller.stop()
            }
            assertEquals(NoiseReviewPhase.Stopping, controller.state.value?.phase)
            assertTrue(controller.state.value?.proposals.orEmpty().isEmpty())
            assertBlocked(id)
            assertFalse(onMain { recreated.start(id) })
            allowDetect.countDown()
            assertTrue(enteredClose.await(10, TimeUnit.SECONDS))
            assertEquals(0, closed.get())
            assertBlocked(id)
            assertFalse(onMain { recreated.start(id) })
            allowClose.countDown()
            await(controller) { it == null }
            assertEquals(2, calls.get())
            assertEquals(1, closed.get())
            assertReleased(id)
            assertUnchanged(id, before)
            if (cancelOwner) {
                assertTrue(onMain { recreated.start(id) })
                val ready = checkNotNull(await(recreated) { it?.phase == NoiseReviewPhase.Ready })
                assertTrue(ready.proposals.isEmpty())
                assertUnchanged(id, before)
            }
        } finally {
            allowDetect.countDown()
            allowClose.countDown()
            onMain { recreated.close(); recreatedScope.cancel() }
            await(recreated) { it == null }
        }
    }

    private fun readyNote(): String {
        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val checksum = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
        check(wav.size == 352_044 && checksum == JFK_SHA256)
        val id = store.create("en").also(created::add)
        store.audioFile(id).writeBytes(wav.copyOfRange(44, 44 + 3 * BYTES_PER_SECOND))
        listOf("Synthetic background candidate", "Retain this speech", "First draft").forEachIndexed { index, text ->
            val start = index * 1000L
            val end = start + 1000
            val chunk = store.beginChunk(id, start, false)
            store.finishChunk(chunk, end)
            store.complete(checkNotNull(store.nextChunk(id)), listOf(
                Passage(noteId = id, chunkId = chunk, start = start, end = end, text = text, uncertain = true)
            ))
        }
        store.edit(store.passages(id).last(), "Keep the manual correction")
        store.rename(id, "Noise review fixture")
        store.duration(id, 3000)
        store.state(id, "READY")
        return id
    }

    private data class Original(val note: Note, val chunks: List<Chunk>, val passages: List<Passage>, val pcm: ByteArray)

    private fun snapshot(id: String) = Original(checkNotNull(store.note(id)), store.chunks(id),
        store.passages(id), store.audioFile(id).readBytes())

    private fun assertUnchanged(id: String, original: Original) {
        assertEquals(original.note, store.note(id))
        assertEquals(original.chunks, store.chunks(id))
        assertEquals(original.passages, store.passages(id))
        assertArrayEquals(original.pcm, store.audioFile(id).readBytes())
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

    private fun await(target: NoiseReviewController, predicate: (NoiseReviewState?) -> Boolean): NoiseReviewState? =
        runBlocking { withTimeout(10_000) { target.state.first(predicate) } }

    private fun ownerScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val BYTES_PER_SECOND = 32_000
        const val JFK_SHA256 = "d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f"
    }
}
