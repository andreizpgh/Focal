package app.focal

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.recording.RecordingDecoder
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.speech.NativeFaultHooks
import app.focal.speech.NativeWhisper
import app.focal.speech.Transcriber
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NativeFaultRecoveryTest {
    private val app get() = NativeFaultFixtures.app
    private var noteId: String? = null
    private val token = AtomicLong()

    @Before fun setup() {
        NativeFaultFixtures.ready(NativeFaultFixtures.PATCHED)
        await { idle() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun cleanup() {
        token.get().takeIf { it != 0L }?.let(NativeFaultHooks::release)
        app.stopService(Intent(app, RecordingService::class.java))
        await { idle() }
        RecordingService.decoderFactoryForTests = null
        RecordingService.inputFactoryForTests = null
        clearFault()
        noteId?.let { app.store.delete(it) }
    }

    @Test fun nativeAllocationFailurePreservesAudioAndEditsAndNormalRecoverySucceeds() {
        val speech = NativeFaultFixtures.pcm()
        val original = speech.copyOfRange(0, 32_000) + speech
        val duration = original.size / 32L
        val expectedHash = NativeFaultFixtures.hash(original)
        val id = app.store.create("en").also { noteId = it }
        app.store.audioFile(id).writeBytes(original)
        app.store.duration(id, duration)
        val first = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(first, 1000)
        app.store.complete(app.store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = first,
            start = 0, end = 1000, text = "Original fixture passage", uncertain = true)))
        val correction = "Keep this reviewed correction."
        app.store.edit(app.store.passages(id).single(), correction)
        val pending = app.store.beginChunk(id, 1000, false)
        app.store.finishChunk(pending, duration)
        app.store.state(id, "INTERRUPTED", "Native recovery fixture")
        val savedPassages = app.store.passages(id)
        assertEquals(correction, savedPassages.single().text)
        assertTrue(savedPassages.single().reviewed)
        val savedChunks = app.store.chunks(id)
        val microphoneOpens = AtomicInteger()
        val closes = AtomicInteger()
        RecordingService.inputFactoryForTests = {
            microphoneOpens.incrementAndGet()
            error("Recovery must not open the microphone")
        }
        RecordingService.decoderFactoryForTests = {
            val engine = Transcriber(app.models.model.absolutePath)
            object : RecordingDecoder {
                override suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) {
                    token.set(NativeFaultHooks.arm(NativeFaultHooks.FAIL_ALLOCATION))
                    try { engine.transcribe(store, chunk, language, threads) }
                    finally { NativeFaultHooks.disarm() }
                }
                override fun cancel() = engine.cancel()
                override fun close() { engine.close(); closes.incrementAndGet() }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
            await { app.store.note(id)?.error?.contains("Transcription stopped") == true && idle() }
            assertEquals("INTERRUPTED", app.store.note(id)!!.state)
            assertEquals(1, closes.get())
            assertEquals(0, microphoneOpens.get())
            assertEquals(savedChunks, app.store.chunks(id))
            assertEquals(savedPassages, app.store.passages(id))
            assertEquals(1, app.store.pendingCount(id))
            assertEquals(duration, app.store.note(id)!!.duration)
            assertEquals(original.size.toLong(), app.store.audioBytes(id))
            assertArrayEquals(expectedHash, NativeFaultFixtures.hash(app.store.audioFile(id).readBytes()))
            val observed = NativeFaultHooks.snapshot(token.get())
            assertTarget(observed)
            assertEquals(1L, observed.refusals)
            assertTrue(observed.hasResult && observed.finished)
            assertEquals(-7L, observed.resultCode)
            assertEquals(1L, observed.destroyed)
            clearFault()

            // Keep the same database/audio and use the ordinary service factory/new engine.
            RecordingService.decoderFactoryForTests = null
            activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
            await { app.store.note(id)?.state == "READY" && idle() }
            assertEquals(0, microphoneOpens.get())
            assertEquals(0, app.store.pendingCount(id))
            val chunks = app.store.chunks(id)
            assertEquals(2, chunks.size)
            assertEquals(savedChunks.first(), chunks.first())
            assertEquals(savedChunks.last().copy(state = "DONE"), chunks.last())
            assertEquals(duration, app.store.note(id)!!.duration)
            assertEquals("", app.store.note(id)!!.error)
            val passages = app.store.passages(id)
            assertEquals(savedPassages, passages.filter { it.chunkId == first })
            val recovered = passages.filter { it.chunkId == pending }
            assertTrue(recovered.isNotEmpty())
            assertTrue(recovered.all { it.start >= 1000 && it.end <= duration && it.end > it.start && it.text.isNotBlank() })
            val text = recovered.joinToString(" ") { it.text }.lowercase()
            assertTrue("Recovery must recognize the real fixture", "ask not" in text && "country" in text)
            assertEquals(original.size.toLong(), app.store.audioBytes(id))
            assertArrayEquals(expectedHash, NativeFaultFixtures.hash(app.store.audioFile(id).readBytes()))
        }
    }

    @Test fun inFlightCancellationAndCloseKeepOwnerUntilDecodeReturns() {
        val samples = NativeFaultFixtures.samples(NativeFaultFixtures.pcm())
        val handle = NativeWhisper.open(app.models.model.absolutePath)
        val executor = Executors.newSingleThreadExecutor()
        val armed = CountDownLatch(1)
        try {
            val decode = executor.submit<ByteArray> {
                token.set(NativeFaultHooks.arm(NativeFaultHooks.PAUSE_ALLOCATION))
                armed.countDown()
                try { NativeWhisper.decode(handle, samples, "en", 1) }
                finally { NativeFaultHooks.disarm() }
            }
            assertTrue("Decode worker must arm", armed.await(5, TimeUnit.SECONDS))
            val waitingSince = SystemClock.elapsedRealtime()
            val entered = NativeFaultHooks.awaitEntered(token.get(), 120_000)
            assertTrue("Real encoder must reach the native barrier: ${NativeFaultHooks.snapshot(token.get())}", entered)
            Log.i("FocalNativeFault", "BARRIER_REACHED wait_ms=${SystemClock.elapsedRealtime() - waitingSince}")
            val blocked = NativeFaultHooks.snapshot(token.get())
            assertTarget(blocked)
            assertEquals(handle, blocked.handle)
            assertFalse(blocked.finished)
            assertEquals(0L, blocked.destroyed)
            NativeWhisper.cancel(handle)
            NativeWhisper.close(handle)
            assertThrows(IllegalStateException::class.java) { NativeWhisper.decode(handle, samples, "en", 1) }
            assertEquals("In-flight call still owns the engine", 0L, NativeFaultHooks.snapshot(token.get()).destroyed)
            NativeFaultHooks.release(token.get())
            val failure = assertThrows(ExecutionException::class.java) { decode.get(45, TimeUnit.SECONDS) }
            assertTrue("Cancelled decode must not return partial output", failure.cause is CancellationException)
            val finished = NativeFaultHooks.snapshot(token.get())
            assertTarget(finished)
            assertEquals(0L, finished.refusals)
            assertTrue(finished.hasResult && finished.finished)
            assertEquals(1L, finished.destroyed)
            NativeWhisper.cancel(handle)
            NativeWhisper.close(handle)
            clearFault()
            val fresh = NativeWhisper.open(app.models.model.absolutePath)
            try { NativeFaultFixtures.assertSpeech(NativeWhisper.decode(fresh, samples, "en", 1)) }
            finally { NativeWhisper.close(fresh) }
        } finally {
            token.get().takeIf { it != 0L }?.let(NativeFaultHooks::release)
            NativeWhisper.cancel(handle)
            NativeWhisper.close(handle)
            executor.shutdownNow()
            assertTrue("Decode worker must finish cleanup", executor.awaitTermination(45, TimeUnit.SECONDS))
        }
    }

    private fun assertTarget(observed: NativeFaultHooks.Snapshot) {
        assertTrue(observed.handle > 0)
        assertEquals(1L, observed.encoderCompletions)
        assertEquals(1L, observed.oldDecoders)
        assertEquals(3L, observed.newDecoders)
        assertEquals(1L, observed.allocationEntries)
        assertTrue(observed.entered)
        assertFalse(observed.timedOut || observed.invalidTarget)
    }

    private fun clearFault() {
        val current = token.get()
        if (current != 0L) {
            NativeFaultHooks.clear(current)
            token.set(0)
        }
    }

    @Suppress("DEPRECATION")
    private fun idle(): Boolean = RecordingState.live.value.noteId == null &&
        !SessionOperations.matchesSession(capture = true, noteId = null) &&
        (noteId?.let { !SessionOperations.matchesSession(capture = false, noteId = it) } ?: true) &&
        app.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .none { it.service.className == RecordingService::class.java.name } &&
        app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for native recovery cleanup", condition())
    }
}
