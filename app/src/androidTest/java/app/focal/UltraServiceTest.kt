package app.focal

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.AUTOMATIC_LANGUAGE
import app.focal.core.EndKind
import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.StoredWindow
import app.focal.data.WindowState
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
    private lateinit var activity: ActivityScenario<ComponentActivity>
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
        // Staged waiting notes are transcribed by explicit commands only.
        app.autoResumeTranscription = false
        activity = ActivityScenario.launch(ComponentActivity::class.java)
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
            app.autoResumeTranscription = true
            activity.close()
        }
    }

    @Test fun defaultUltraPersistsLongCaptureWhileFirstDecodeIsBlocked() {
        val firstMs = 36_000L
        val totalMs = 185_001L
        val input = BoundedInput(frames(firstMs), ::audibleSample)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoded = CopyOnWriteArrayList<Long>()
        RecordingService.inputFactoryForTests = { input }
        RecordingService.decoderFactoryForTests = {
            completingDecoder { window ->
                decoded += window.id
                if (window.ownStartMs == 0L) {
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
            assertTrue(store.windows(id).all { it.state == WindowState.PENDING })
            assertTrue(store.paragraphs(id).isEmpty())

            input.allow(frames(totalMs))
            send(RecordingService.RESUME)
            await("Over three minutes written while the decoder is still blocked") {
                input.delivered == frames(totalMs) && store.audioBytes(id) > 180_000L * BYTES_PER_MS
            }
            assertFalse(input.closed)
            assertFalse(RecordingState.live.value.processing)
            assertEquals(1, decoded.size)
            assertTrue(store.windows(id).all { it.state == WindowState.PENDING })
            assertTrue(store.paragraphs(id).isEmpty())

            send(RecordingService.STOP)
            await("Microphone closed and complete original synced before decode returns") {
                input.closed && store.note(id)?.duration == totalMs && RecordingState.live.value.processing
            }
            assertEquals(totalMs * BYTES_PER_MS, store.audioBytes(id))
            assertTrue(store.paragraphs(id).isEmpty())
            release.countDown()
            awaitReady(id)
            assertUltraTimeline(id, totalMs)
            assertEquals(store.windows(id).filter { it.state != WindowState.SILENT }.map { it.id }, decoded.toList())
            assertTrue("Full original PCM must survive pause, resume and finish.",
                store.audioFile(id).readBytes().contentEquals(syntheticPcm(totalMs)))
        } finally {
            release.countDown()
        }
    }

    @Test fun recoveryDecodesThePendingWindowAndSegmentsTheTailWithoutOpeningInput() {
        val duration = 65_001L
        val original = syntheticPcm(duration)
        val id = store.create().also { fixtureId = it }
        FileOutputStream(store.audioFile(id)).use { it.write(original); it.fd.sync() }
        store.duration(id, 59_500)
        val first = store.addWindow(id, Synthetic.plan(0, 0, 20_000, EndKind.HARD))
        complete(store, checkNotNull(store.beginDecode(first.id)))
        val committed = store.paragraphs(id).single()
        val pending = store.addWindow(id, Synthetic.plan(1, 20_000, 40_000, EndKind.HARD, EndKind.HARD))
        store.state(id, "INTERRUPTED")
        assertTrue(store.hasUntranscribedAudio(checkNotNull(store.note(id))))
        val inputOpens = AtomicInteger()
        val decoded = CopyOnWriteArrayList<StoredWindow>()
        RecordingService.inputFactoryForTests = {
            inputOpens.incrementAndGet()
            error("Recovery must use the saved original.")
        }
        RecordingService.decoderFactoryForTests = { completingDecoder { decoded += it } }

        send(RecordingService.RECOVER, id)
        awaitReady(id)
        assertUltraTimeline(id, duration)
        assertEquals(0, inputOpens.get())
        assertEquals(pending.id, decoded.first().id)
        assertEquals(store.windows(id).drop(1).filter { it.state != WindowState.SILENT }.map { it.id }, decoded.map { it.id })
        assertEquals(committed.text, store.paragraphs(id).first().text.take(committed.text.length))
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
        val passages = store.paragraphs(id)
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
        // Windows tile the whole recording without gaps, each at most the maximum window length.
        val windows = store.windows(id)
        assertEquals(0L, windows.first().ownStartMs)
        assertEquals(duration, windows.last().ownEndMs)
        assertEquals(EndKind.FINAL, windows.last().endKind)
        assertTrue(windows.all { it.state in listOf(WindowState.DONE, WindowState.SILENT) && it.ownEndMs - it.ownStartMs in 1L..20_000L })
        for ((previous, next) in windows.zipWithNext()) assertEquals(previous.ownEndMs, next.ownStartMs)
        assertEquals(0, store.pendingCount(id))
        assertEquals(0L, store.progress(id).remainingMs)
        val paragraphs = store.paragraphs(id)
        assertTrue(paragraphs.isNotEmpty())
        assertTrue(paragraphs.all { !it.open && it.sourceText?.isNotBlank() == true })
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

    private fun completingDecoder(beforeComplete: (StoredWindow) -> Unit) = object : RecordingDecoder {
        override suspend fun transcribe(store: NoteStore, window: StoredWindow, threads: Int) {
            beforeComplete(window)
            currentCoroutineContext().ensureActive()
            complete(store, window)
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    /** Commits one short synthetic word at the start of the window's own audio. */
    private fun complete(store: NoteStore, window: StoredWindow) {
        val samples = store.readAudio(window.noteId, window.audioStartMs, window.audioEndMs).size
        check(samples.toLong() == (window.audioEndMs - window.audioStartMs) * 16)
        val text = "Window${window.ownStartMs}."
        val start = (window.ownStartMs - window.audioStartMs) / 1000.0
        store.commitDecoded(window.id, ULTRA_ENGINE, RawAsr(text, Synthetic.tokens(Triple(text, start, 0.1))))
    }

    private fun frames(ms: Long) = (ms * 16).toInt()
    /** A sawtooth above the silence gate (about -42 dBFS), so every window is decoded. */
    private fun audibleSample(index: Int) = ((index % 63 - 31) * 8).toShort()
    private fun syntheticPcm(ms: Long): ByteArray {
        val bytes = ByteBuffer.allocate(frames(ms) * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames(ms)) { bytes.putShort(audibleSample(it)) }
        return bytes.array()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BYTES_PER_MS = 32L
        const val JFK_SHA256 = "d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f"
    }
}
