package app.focal

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.NoteState
import app.focal.data.NoteStore
import app.focal.data.RawAsr
import app.focal.data.StoredWindow
import app.focal.data.ULTRA_ENGINE
import app.focal.recording.RecordingDecoder
import app.focal.recording.RecordingInput
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.recording.SpeechAvailability
import app.focal.speech.EngineStage
import app.focal.speech.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The guard around the speech work that can kill the process (EngineGuard), driven through the service. A process
 * death inside a decode is simulated as the next process finds it: the guard's file as it was on disk while the
 * decoder ran, read by the guard started again as a new process ([app.focal.speech.EngineGuard.restartForTests]).
 * Two such deaths stop automatic transcription; capture keeps working; the person's Try again starts it again, for one
 * attempt until a decode completes.
 */
@RunWith(AndroidJUnit4::class)
class EngineGuardServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private val store get() = app.store
    private val guard get() = app.engineGuard
    private val guardFile get() = File(app.noBackupFilesDir, "speech-guard")
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private val created = mutableListOf<String>()

    /** How the synthetic decoder behaves: die (block inside the decode until cancelled) or commit a word. */
    @Volatile private var dying = true
    private val opened = AtomicInteger()
    private val decodes = AtomicInteger()
    @Volatile private var inside: CountDownLatch = CountDownLatch(1)
    @Volatile private var markedInside: EngineStage? = null
    @Volatile private var onDisk: ByteArray? = null

    @Before fun setup() {
        runBlocking { app.storageReady.await() }
        await("Previous recording service stopped") { RecordingState.live.value.noteId == null && !serviceRunning() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.autoResumeTranscription = false
        guard.clearForTests()
        RecordingService.inputFactoryForTests = null
        RecordingService.decoderFactoryForTests = { opened.incrementAndGet(); decoder() }
        activity = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun cleanup() {
        try {
            app.stopService(Intent(app, RecordingService::class.java))
            await("Recording service stopped", 60_000) {
                RecordingState.live.value.noteId == null && !serviceRunning() &&
                    created.none { SessionOperations.matchesSession(true, it) || SessionOperations.matchesSession(false, it) }
            }
            created.forEach { store.delete(it) }
        } finally {
            // The notes made ready here leave no "Note ready" behind for the tests after this one.
            app.getSystemService(NotificationManager::class.java).cancelAll()
            guard.clearForTests()
            RecordingService.inputFactoryForTests = null
            RecordingService.decoderFactoryForTests = null
            app.autoResumeTranscription = true
            activity.close()
        }
    }

    @Test fun twoDeathsInsideADecodeStopAutomaticTranscriptionButNotCapture() {
        val waiting = waitingNote(SAVED_MS)

        // The first death: the decode of a note the foreground start resumes.
        die { send(RecordingService.DRAIN) }
        assertEquals(mapOf(EngineStage.DECODE to 1), guard.deaths())
        assertTrue("One death leaves one more attempt", guard.allows())
        // The second: the next start resumes it again, and it dies again.
        die { send(RecordingService.DRAIN) }
        assertEquals(mapOf(EngineStage.DECODE to 2), guard.deaths())
        assertFalse("Two deaths in a row stop the work", guard.allows())

        // The next foreground start does not resume transcription by itself.
        val before = opened.get()
        app.autoResumeTranscription = true
        activity.onActivity { app.resumeWaitingTranscription(it) }
        SystemClock.sleep(QUIET_MS)
        assertFalse("No service started", serviceRunning())
        if (app.models.status.value is ModelStatus.Ready) assertFalse(SessionOperations.matchesSession(false, waiting))
        // Even an explicit drain only puts the note back to waiting: no recognizer, no decode, no window planned.
        val windows = store.windows(waiting).size
        send(RecordingService.DRAIN)
        await("The drain ended") { !serviceRunning() && !SessionOperations.matchesSession(false, waiting) }
        assertEquals(before, opened.get())
        assertEquals(NoteState.INTERRUPTED, store.note(waiting)?.state)
        assertEquals(windows, store.windows(waiting).size)

        // Capture keeps working: every delivered sample is saved, and the note waits for Try again.
        val input = SawtoothInput(frames(CAPTURE_MS))
        RecordingService.inputFactoryForTests = { input }
        send(RecordingService.START)
        await("New recording created") { RecordingState.live.value.noteId != null }
        val recording = checkNotNull(RecordingState.live.value.noteId).also { created += it }
        await("Every sample delivered") { input.delivered == frames(CAPTURE_MS) }
        await("Text will catch up") { RecordingState.live.value.speech == SpeechAvailability.UNAVAILABLE }
        send(RecordingService.STOP)
        await("The recording waits", 30_000) {
            input.closed && !serviceRunning() && store.note(recording)?.state == NoteState.INTERRUPTED
        }
        assertArrayEquals(sawtooth(frames(CAPTURE_MS)), store.audioFile(recording).readBytes())
        assertEquals(CAPTURE_MS, store.note(recording)?.duration)
        assertEquals(before, opened.get())
        assertEquals(0, decodes.get())
        assertFalse("Still stopped", guard.allows())

        // Try again gives one attempt: a decode that dies again stops the work at once, so the next start neither
        // resumes it nor dies a second time.
        die { send(RecordingService.RECOVER, waiting, retryFailed = true) }
        assertEquals(mapOf(EngineStage.DECODE to 1), guard.deaths())
        assertFalse("One death after Try again stops the work again", guard.allows())

        // The person's Try again forgets the deaths and transcribes the note, then every other note that waited
        // while the work was stopped (the recording made meanwhile), with no foreground start to send DRAIN.
        dying = false
        send(RecordingService.RECOVER, waiting, retryFailed = true)
        await("Ready after Try again", 60_000) {
            store.note(waiting)?.state == NoteState.READY && !SessionOperations.matchesSession(false, waiting)
        }
        assertTrue(guard.allows())
        assertEquals(emptyMap<EngineStage, Int>(), guard.deaths())
        assertTrue(store.paragraphs(waiting).isNotEmpty())
        await("The other waiting note ready after Try again", 60_000) {
            store.note(recording)?.state == NoteState.READY && !SessionOperations.matchesSession(false, recording)
        }
        assertTrue(store.paragraphs(recording).isNotEmpty())
    }

    /**
     * Runs [start] until the decoder is inside its decode, then "kills the process": the guard's file is kept as it was
     * on disk at that moment, the service is stopped, and the guard starts again as the next process, from that file.
     */
    private fun die(start: () -> Unit) {
        inside = CountDownLatch(1)
        onDisk = null
        start()
        assertTrue("The decoder was reached", inside.await(30, TimeUnit.SECONDS))
        assertEquals("The decode is marked on disk before it runs", EngineStage.DECODE, markedInside)
        val dead = checkNotNull(onDisk)
        app.stopService(Intent(app, RecordingService::class.java))
        await("The service released the note", 30_000) {
            !serviceRunning() && RecordingState.live.value.noteId == null && created.none { SessionOperations.matchesSession(false, it) }
        }
        guardFile.writeBytes(dead)
        guard.restartForTests()
    }

    private fun decoder() = object : RecordingDecoder {
        private val cancelled = CountDownLatch(1)

        override suspend fun transcribe(store: NoteStore, window: StoredWindow, threads: Int) {
            if (dying) {
                markedInside = guard.markedOnDisk()
                onDisk = guardFile.readBytes()
                inside.countDown()
                // The process "dies" here: nothing after this line runs before the service is stopped.
                cancelled.await(60, TimeUnit.SECONDS)
                throw CancellationException("The process died inside the decode.")
            }
            decodes.incrementAndGet()
            val text = "Window${window.ownStartMs}."
            val start = (window.ownStartMs - window.audioStartMs) / 1000.0
            store.commitDecoded(window.id, ULTRA_ENGINE, RawAsr(text, Synthetic.tokens(Triple(text, start, 0.1))))
        }

        override fun cancel() = cancelled.countDown()
        override fun close() = cancelled.countDown()
    }

    /** A recording that waits for transcription after a process death: [ms] of audible saved audio, no windows. */
    private fun waitingNote(ms: Long): String {
        val id = store.create().also { created += it }
        FileOutputStream(store.audioFile(id)).use { it.write(sawtooth(frames(ms))); it.fd.sync() }
        store.duration(id, ms)
        store.state(id, NoteState.INTERRUPTED)
        return id
    }

    private fun send(action: String, id: String? = null, retryFailed: Boolean = false) {
        activity.onActivity { RecordingService.command(it, action, id, retryFailed) }
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

    /** Delivers [limit] frames of the sawtooth, then waits like a microphone in silence until stopped. */
    private class SawtoothInput(private val limit: Int) : RecordingInput {
        private val lock = Object()
        private var running = false
        @Volatile var delivered = 0
            private set
        @Volatile var closed = false
            private set
        override val initialized = true
        override val silenced = false

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

    private companion object {
        const val SAVED_MS = 12_000L
        const val CAPTURE_MS = 3_000L
        const val QUIET_MS = 2_000L

        fun frames(ms: Long) = (ms * 16).toInt()
        /** A sawtooth above the silence gate (about -42 dBFS), so its windows are decoded. */
        fun sample(index: Int) = ((index % 63 - 31) * 8).toShort()
        fun sawtooth(frames: Int): ByteArray {
            val bytes = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { bytes.putShort(sample(it)) }
            return bytes.array()
        }
    }
}
