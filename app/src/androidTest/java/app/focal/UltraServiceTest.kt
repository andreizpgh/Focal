package app.focal

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.AUTOMATIC_LANGUAGE
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.data.ULTRA_ENGINE
import app.focal.recording.RecordingDecoder
import app.focal.recording.RecordingInput
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.speech.UltraModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UltraServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private val store get() = app.store
    private lateinit var activity: ActivityScenario<MainActivity>
    private var fixtureId: String? = null

    @Before fun setup() {
        runBlocking { app.storageReady.await() }
        await("Previous recording service stopped") { RecordingState.live.value.noteId == null && !serviceRunning() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        RecordingService.decoderFactoryForTests = null
        RecordingService.inputFactoryForTests = null
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() {
        if (!::activity.isInitialized) return
        try {
            app.stopService(Intent(app, RecordingService::class.java))
            await("Recording service released its worker", 120_000) {
                RecordingState.live.value.noteId == null && !serviceRunning() &&
                    !SessionOperations.matchesSession(true, fixtureId) &&
                    !SessionOperations.matchesSession(false, fixtureId)
            }
            fixtureId?.let { store.delete(it) }
        } finally {
            RecordingService.inputFactoryForTests = null
            RecordingService.decoderFactoryForTests = null
            activity.close()
        }
    }

    @Test fun defaultUltraPersistsLongCaptureWhileFirstDecodeIsBlocked() {
        val firstMs = 36_000L
        val totalMs = 185_001L
        val input = BoundedInput(frames(firstMs), ::quietSample)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoded = CopyOnWriteArrayList<String>()
        RecordingService.inputFactoryForTests = { input }
        RecordingService.decoderFactoryForTests = {
            completingDecoder { chunk ->
                decoded += chunk.id
                if (chunk.start == 0L) {
                    entered.countDown()
                    check(release.await(45, TimeUnit.SECONDS)) { "Test did not release the first window." }
                }
            }
        }
        try {
            val id = start()
            await("First PCM batch delivered") { input.delivered == frames(firstMs) }
            send(RecordingService.PAUSE)
            await("First batch synced while paused") {
                store.note(id)?.let { it.state == "PAUSED" && it.duration == firstMs } == true
            }
            assertTrue("The first window must reach the decoder.", entered.await(20, TimeUnit.SECONDS))
            assertEquals("PENDING", store.chunks(id).single().state)
            assertTrue(store.passages(id).isEmpty())

            input.allow(frames(totalMs))
            send(RecordingService.RESUME)
            await("Over three minutes written while the decoder is still blocked") {
                input.delivered == frames(totalMs) && store.audioBytes(id) > 180_000L * BYTES_PER_MS
            }
            assertFalse(input.closed)
            assertFalse(RecordingState.live.value.processing)
            assertEquals(1, decoded.size)
            assertEquals("PENDING", store.chunks(id).single().state)
            assertTrue(store.passages(id).isEmpty())

            send(RecordingService.STOP)
            await("Microphone closed and complete original synced before decode returns") {
                input.closed && store.note(id)?.duration == totalMs && RecordingState.live.value.processing
            }
            assertEquals(totalMs * BYTES_PER_MS, store.audioBytes(id))
            assertTrue(store.passages(id).isEmpty())
            release.countDown()
            awaitReady(id)
            assertUltraTimeline(id, totalMs)
            assertEquals(store.chunks(id).map { it.id }, decoded.toList())
            assertTrue("Full quiet original PCM must survive pause, resume and finish.",
                store.audioFile(id).readBytes().contentEquals(syntheticPcm(totalMs)))
        } finally {
            release.countDown()
        }
    }

    @Test fun recoveryUsesPendingCursorAndUnpublishedTailWithoutOpeningInput() {
        val duration = 65_001L
        val original = syntheticPcm(duration)
        val id = store.create().also { fixtureId = it }
        FileOutputStream(store.audioFile(id)).use { it.write(original); it.fd.sync() }
        store.duration(id, 59_500)
        val first = store.enqueueUltraWindow(id, 0, 30_000, 29_500)
        complete(store, first)
        store.edit(store.passages(id).single(), "My synthetic manual edit")
        val edited = store.passages(id).single()
        val pending = store.enqueueUltraWindow(id, 29_500, 59_500, 59_000)
        store.state(id, "INTERRUPTED")
        assertTrue(store.hasUnpublishedTail(checkNotNull(store.note(id))))
        val inputOpens = AtomicInteger()
        val decoded = CopyOnWriteArrayList<Chunk>()
        RecordingService.inputFactoryForTests = {
            inputOpens.incrementAndGet()
            error("Recovery must use the saved original.")
        }
        RecordingService.decoderFactoryForTests = { completingDecoder { decoded += it } }

        send(RecordingService.RECOVER, id)
        awaitReady(id)
        assertUltraTimeline(id, duration)
        assertEquals(0, inputOpens.get())
        assertEquals(listOf(pending.id, store.chunks(id).last().id), decoded.map { it.id })
        assertEquals(59_000L, decoded.last().start)
        assertEquals(edited, store.passages(id).first())
        assertTrue(edited.reviewed)
        assertEquals(windowText(first), edited.originalTranscriptText)
        assertArrayEquals(original, store.audioFile(id).readBytes())
    }

    @Test fun defaultUltraAutomaticallyTranscribesPublicSpeechIntoTheNote() {
        assumeTrue("The bundled speech model is required for this check", UltraModel.isBundled(app))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val wav = assets.open("jfk.wav").use { it.readBytes() }
        assertEquals(352_044, wav.size)
        assertEquals(JFK_SHA256, sha256(wav))
        val pcm = wav.copyOfRange(44, wav.size)
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val input = BoundedInput(samples.limit(), samples::get)
        RecordingService.inputFactoryForTests = { input }

        val id = start()
        await("Public fixture delivered to capture") { input.delivered == samples.limit() }
        send(RecordingService.STOP)
        awaitReady(id, 120_000)
        assertUltraTimeline(id, 11_000)
        assertTrue("Saved audio must match the public fixture PCM.",
            store.audioFile(id).let { it.length() == pcm.size.toLong() && sha256(it.readBytes()) == sha256(pcm) })
        val passages = store.passages(id)
        val words = Regex("[a-z]+").findAll(passages.joinToString(" ") { it.text }.lowercase())
            .map { it.value }.toList()
        assertTrue("The default engine must produce substantive public-fixture speech.", words.size >= 8)
        assertTrue("The public phrase must retain its negative and key words.",
            words.windowed(2).any { it == listOf("ask", "not") } && "country" in words)
    }

    private fun start(): String {
        send(RecordingService.START)
        await("New recording created") { RecordingState.live.value.noteId != null }
        return checkNotNull(RecordingState.live.value.noteId).also { fixtureId = it }
    }

    private fun send(action: String, id: String? = null) {
        activity.onActivity { RecordingService.command(it, action, id) }
    }

    private fun awaitReady(id: String, timeoutMs: Long = 25_000) {
        await("Note became READY", timeoutMs) {
            val claimed = SessionOperations.matchesSession(true, id) || SessionOperations.matchesSession(false, id)
            val state = store.note(id)?.state
            check(state !in listOf("INTERRUPTED", "ERROR") || claimed || RecordingState.live.value.noteId != null) {
                "Transcription did not finish. Final state: $state."
            }
            state == "READY" && !claimed && RecordingState.live.value.noteId == null && !serviceRunning()
        }
    }

    private fun assertUltraTimeline(id: String, duration: Long) {
        val note = checkNotNull(store.note(id))
        assertEquals("READY", note.state)
        assertEquals(ULTRA_ENGINE, note.speechEngine)
        assertEquals(AUTOMATIC_LANGUAGE, note.language)
        assertEquals(duration, note.duration)
        val chunks = store.chunks(id)
        assertEquals(0L, chunks.first().start)
        assertFalse(chunks.first().overlap)
        assertEquals(duration, chunks.last().end)
        assertEquals(duration, checkNotNull(chunks.last().nextStart))
        assertTrue(chunks.all { it.state == "DONE" && it.end - it.start in 1L..30_000L })
        for ((previous, next) in chunks.zipWithNext()) {
            assertEquals(checkNotNull(previous.nextStart), next.start)
            assertTrue(next.end > previous.end)
            assertTrue(previous.end - next.start in 0L..500L)
            assertEquals(next.start < previous.end, next.overlap)
        }
        assertEquals(0, store.pendingCount(id))
        val passages = store.passages(id)
        assertEquals(chunks.map { it.id }, passages.map { it.chunkId }.distinct())
        assertTrue(passages.all { it.originalTranscriptText?.isNotBlank() == true })
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning() = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == RecordingService::class.java.name }

    private fun await(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { description }
            SystemClock.sleep(20)
        }
    }

    private class BoundedInput(initialLimit: Int, private val sample: (Int) -> Short) : RecordingInput {
        private val lock = Object()
        private var running = false
        private var limit = initialLimit
        @Volatile var delivered = 0
            private set
        @Volatile var closed = false
            private set
        override val initialized = true
        override val silenced = false

        fun allow(frames: Int) = synchronized(lock) {
            check(frames >= limit)
            limit = frames
            lock.notifyAll()
        }
        override fun start() = synchronized(lock) { check(!closed); running = true }
        override fun stop() = synchronized(lock) { running = false; lock.notifyAll() }
        override fun close() = synchronized(lock) { closed = true; running = false; lock.notifyAll() }
        override fun read(samples: ShortArray, offset: Int, count: Int): Int = synchronized(lock) {
            while (running && delivered == limit) lock.wait()
            if (!running) return@synchronized AudioRecord.ERROR_INVALID_OPERATION
            val length = minOf(count, limit - delivered)
            repeat(length) { index -> samples[offset + index] = sample(delivered + index) }
            delivered += length
            length
        }
    }

    private fun completingDecoder(beforeComplete: (Chunk) -> Unit) = object : RecordingDecoder {
        override suspend fun transcribe(store: NoteStore, chunk: Chunk, threads: Int) {
            beforeComplete(chunk)
            currentCoroutineContext().ensureActive()
            complete(store, chunk)
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private fun complete(store: NoteStore, chunk: Chunk) {
        check(store.readChunk(chunk).size.toLong() == (chunk.end - chunk.start) * BYTES_PER_MS)
        store.complete(chunk, listOf(Passage(noteId = chunk.noteId, chunkId = chunk.id,
            start = chunk.start, end = chunk.end, text = windowText(chunk), uncertain = false)))
    }

    private fun windowText(chunk: Chunk) = "Synthetic Ultra window ${chunk.start}"
    private fun frames(ms: Long) = (ms * 16).toInt()
    private fun quietSample(index: Int) = (index % 31 - 15).toShort()
    private fun syntheticPcm(ms: Long): ByteArray {
        val bytes = ByteBuffer.allocate(frames(ms) * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames(ms)) { bytes.putShort(quietSample(it)) }
        return bytes.array()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BYTES_PER_MS = 32L
        const val JFK_SHA256 = "d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f"
    }
}
