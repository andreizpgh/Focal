package app.focal

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Note
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.recording.RecordingDecoder
import app.focal.recording.RecordingInput
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.speech.SpeechPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle/durability checks with deterministic PCM; these make no recognition-quality claim. */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private lateinit var activity: ActivityScenario<MainActivity>
    private var originalIds: Set<String>? = null
    private val inputs = CopyOnWriteArrayList<SyntheticInput>()

    @Before fun setup() {
        runBlocking { app.storageReady.await() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        originalIds = app.store.notes().map { it.id }.toSet()
        await { RecordingState.live.value.noteId == null && !serviceRunning() }
        // Explicit decoder failure tests pending-queue behavior; it never supplies fake text.
        RecordingService.metadataCapacityForTests = null
        RecordingService.decoderFactoryForTests = { error("Synthetic decoder unavailable for capture-only test") }
        RecordingService.inputFactoryForTests = { SyntheticInput().also { inputs += it } }
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() {
        app.stopService(Intent(app, RecordingService::class.java))
        await { RecordingState.live.value.noteId == null && !serviceRunning() }
        awaitNoNotifications()
        RecordingService.inputFactoryForTests = null
        RecordingService.decoderFactoryForTests = null
        RecordingService.metadataCapacityForTests = null
        if (::activity.isInitialized) activity.close()
        originalIds?.let { baseline -> app.store.notes().filter { it.id !in baseline }.forEach { app.store.delete(it.id) } }
    }

    @Test fun decoderFailureKeepsCaptureAliveAndPauseIsSyncedAndIdempotent() {
        val id = start()
        await { RecordingState.live.value.duration >= 240 }
        send(RecordingService.START)
        send(RecordingService.START)
        assertEquals(id, RecordingState.live.value.noteId)
        send(RecordingService.PAUSE)
        await { RecordingState.live.value.paused && app.store.note(id)?.state == "PAUSED" }
        val file = app.store.audioFile(id)
        val pausedBytes = file.length()
        val source = inputs.single()
        assertTrue(pausedBytes > 0)
        assertEquals("Pause must sync every delivered partial read", source.samples.get() * 2, pausedBytes)
        assertEquals(pausedBytes / 32, app.store.note(id)!!.duration)
        await { app.store.chunks(id).lastOrNull()?.end == pausedBytes / 32 }
        send(RecordingService.PAUSE)
        send(RecordingService.PAUSE)
        SystemClock.sleep(180)
        assertTrue(RecordingState.live.value.paused)
        assertEquals(pausedBytes, file.length())
        val pausedDuration = RecordingState.live.value.duration
        send(RecordingService.RESUME)
        send(RecordingService.RESUME)
        await { !RecordingState.live.value.paused && RecordingState.live.value.duration > pausedDuration + 120 }
        send(RecordingService.STOP)
        send(RecordingService.STOP)
        finish(id)
        assertEquals(1, inputs.size)
        assertTrue(source.closed)
        assertOriginalMatchesDeliveredSamples(id, source)
        assertEquals("INTERRUPTED", app.store.note(id)!!.state)
        assertTrue(app.store.note(id)!!.error.contains("Synthetic decoder"))
        assertTrue("Final voiced audio must remain queued", app.store.pendingCount(id) > 0)
        assertEquals("The partial final frame must be queued", app.store.note(id)!!.duration, app.store.chunks(id).maxOf { it.end })
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
        assertTrue("Capture-only checks must not invent a transcript", app.store.passages(id).isEmpty())
    }

    @Test fun sqliteWriterCannotBlockCaptureOrMicrophoneShutdown() {
        val id = start()
        await { RecordingState.live.value.duration >= 200 }
        val file = app.store.audioFile(id)
        HeldWriter(app.store.writableDatabase, id).use {
            val before = file.length()
            val beforeDuration = RecordingState.live.value.duration
            await { file.length() >= before + 32_000 && RecordingState.live.value.duration >= beforeDuration + 1200 }
            send(RecordingService.STOP)
            // Metadata is blocked, but the device/file must already close and sync.
            await { inputs.single().closed && RecordingState.live.value.processing }
            assertOriginalBytesMatchDeliveredSamples(id, inputs.single())
            assertTrue(RecordingState.live.value.processing)
        }
        finish(id)
        assertOriginalMatchesDeliveredSamples(id, inputs.single())
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
        assertEquals(app.store.note(id)!!.duration, app.store.chunks(id).maxOf { it.end })
    }

    @Test fun boundedMetadataOverflowStopsVisiblyAndRecoversPersistedAudio() {
        // Same bounded production channel path, with a small debug-only limit so
        // a software-rendered emulator need not execute dozens of UI round trips.
        RecordingService.metadataCapacityForTests = 4
        val id = start()
        await { RecordingState.live.value.duration >= 240 }
        HeldWriter(app.store.writableDatabase, id).use {
            repeat(6) {
                if (!inputs.single().closed) {
                    send(RecordingService.PAUSE)
                    await { RecordingState.live.value.paused || inputs.single().closed }
                    if (!inputs.single().closed) {
                        val beforeResume = RecordingState.live.value.duration
                        send(RecordingService.RESUME)
                        await { inputs.single().closed || (!RecordingState.live.value.paused && RecordingState.live.value.duration >= beforeResume + 80) }
                    }
                }
            }
            await { inputs.single().closed && RecordingState.live.value.message.contains("metadata") }
            assertTrue(RecordingState.live.value.message, RecordingState.live.value.message.contains("metadata"))
            assertOriginalBytesMatchDeliveredSamples(id, inputs.single())
        }
        finish(id)
        val note = app.store.note(id)!!
        assertEquals("INTERRUPTED", note.state)
        assertTrue(note.error, note.error.contains("metadata"))
        assertOriginalMatchesDeliveredSamples(id, inputs.single())
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
        assertEquals(note.duration, app.store.chunks(id).maxOf { it.end })
        assertNoDuplicateSourceRanges(id)
    }

    @Test fun immediateStopAndRepeatedStartCannotLeaveAnOrphanRecording() {
        activity.onActivity {
            RecordingService.command(it, RecordingService.START)
            RecordingService.command(it, RecordingService.START)
            RecordingService.command(it, RecordingService.STOP)
            RecordingService.command(it, RecordingService.STOP)
        }
        // Let Android dispatch all four service commands before observing completion.
        SystemClock.sleep(150)
        await { RecordingState.live.value.noteId == null && !serviceRunning() }
        awaitNoNotifications()
        val notes = createdNotes()
        assertTrue("Repeated START created multiple notes", notes.size <= 1)
        assertTrue(notes.none { it.state in listOf("RECORDING", "PAUSED", "PROCESSING") })
        assertTrue(inputs.size <= 1)
        assertTrue(inputs.all { it.closed })
    }

    @Test fun rapidPauseResumeAndServiceDestructionPreserveAllDeliveredPcm() {
        val id = start()
        await { RecordingState.live.value.duration >= 200 }
        repeat(12) {
            send(RecordingService.PAUSE)
            send(RecordingService.RESUME)
        }
        await { !RecordingState.live.value.paused && RecordingState.live.value.duration >= 400 }
        assertTrue(app.stopService(Intent(app, RecordingService::class.java)))
        finish(id)
        assertOriginalMatchesDeliveredSamples(id, inputs.single())
        assertEquals("INTERRUPTED", app.store.note(id)!!.state)
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
        assertTrue(inputs.single().closed)
    }

    @Test fun microphoneSilencingStopsSafelyWithRecoverablePendingAudio() {
        val id = start()
        await { RecordingState.live.value.duration >= 240 }
        inputs.single().silenced = true
        finish(id)
        val note = app.store.note(id)!!
        assertEquals("INTERRUPTED", note.state)
        assertTrue(note.error, note.error.contains("muted"))
        assertTrue(app.store.audioBytes(id) > 0)
        assertEquals(app.store.audioBytes(id) / 32, note.duration)
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
        assertTrue(app.store.pendingCount(id) > 0)
    }

    @Test fun positiveReadIsKeptWhenSilencingArrivesDuringThatRead() {
        val id = start()
        await { RecordingState.live.value.duration >= 240 }
        val source = inputs.single()
        source.silenceOnNextRead = true
        finish(id)
        assertTrue(source.silenced)
        assertOriginalMatchesDeliveredSamples(id, source)
        val note = app.store.note(id)!!
        assertEquals("INTERRUPTED", note.state)
        assertTrue(note.error, note.error.contains("muted"))
        assertEquals(note.duration, app.store.chunks(id).maxOf { it.end })
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
    }

    @Test fun disconnectedMicrophoneDoesNotLoseTheFinalQueuedRange() {
        val id = start()
        await { RecordingState.live.value.duration >= 240 }
        inputs.single().disconnected = true
        finish(id)
        val note = app.store.note(id)!!
        assertEquals("INTERRUPTED", note.state)
        assertTrue(note.error, note.error.contains("disconnected"))
        assertOriginalMatchesDeliveredSamples(id, inputs.single())
        assertFalse(app.store.chunks(id).any { it.state == "WRITING" })
        assertTrue(app.store.pendingCount(id) > 0)
    }

    @Test fun staleControlsAndInvalidRecoveryNeverOpenAMicrophone() {
        send(RecordingService.PAUSE)
        send(RecordingService.RESUME)
        send(RecordingService.STOP)
        activity.onActivity {
            try {
                RecordingService.command(it, RecordingService.RECOVER)
                fail("Invalid recovery must be rejected before starting a foreground service")
            } catch (_: IllegalArgumentException) { }
            // Also exercise a malformed raw intent bypassing the validated command API.
            ContextCompat.startForegroundService(it, Intent(it, RecordingService::class.java).setAction(RecordingService.RECOVER))
        }
        SystemClock.sleep(150)
        await { !serviceRunning() }
        // Survive Android's foreground-start deadline rather than handing a delayed
        // RemoteServiceException to the next test after this one appears to pass.
        SystemClock.sleep(5500)
        assertFalse(serviceRunning())
        awaitNoNotifications()
        assertTrue(inputs.isEmpty())
        assertTrue(createdNotes().isEmpty())
        assertNull(RecordingState.live.value.noteId)
    }

    @Test fun staleIdleControlsCannotStopANewerQueuedCaptureStart() {
        activity.onActivity {
            RecordingService.command(it, RecordingService.PAUSE)
            RecordingService.command(it, RecordingService.RESUME)
            RecordingService.command(it, RecordingService.STOP)
            RecordingService.command(it, RecordingService.START)
        }
        await { RecordingState.live.value.noteId != null && RecordingState.live.value.duration >= 160 }
        val id = RecordingState.live.value.noteId!!
        assertEquals(1, inputs.size)
        assertTrue(serviceRunning())
        send(RecordingService.STOP)
        finish(id)
        assertOriginalMatchesDeliveredSamples(id, inputs.single())
    }

    @Test fun recoveryDoesNotReopenMicrophoneOrAlterOriginalAudioWhenDecoderIsUnavailable() {
        val id = app.store.create("en")
        val pcm = ByteArray(1200 * 32) { index -> (index % 97).toByte() }
        app.store.audioFile(id).writeBytes(pcm)
        app.store.duration(id, 1200)
        val chunk = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(chunk, 1200)
        app.store.state(id, "INTERRUPTED", "Synthetic interrupted capture")
        activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
        await { app.store.note(id)?.error?.contains("Synthetic decoder") == true }
        // Service removal/notification cancellation precede the joined onDestroy
        // cleanup that releases live ownership. Wait for that same finish contract.
        finish(id)
        assertNull(RecordingState.live.value.noteId)
        assertTrue(inputs.isEmpty())
        assertArrayEquals(pcm, app.store.audioFile(id).readBytes())
        assertEquals(1, app.store.pendingCount(id))
        assertEquals("INTERRUPTED", app.store.note(id)!!.state)
        assertTrue(app.store.note(id)!!.error.contains("Synthetic decoder"))
    }

    @Test fun stoppingRecoveryKeepsInFlightResultPending() {
        val id = app.store.create("en")
        val pcm = ByteArray(1200 * 32) { (it % 97).toByte() }
        app.store.audioFile(id).writeBytes(pcm)
        app.store.duration(id, 1200)
        val completed = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(completed, 600)
        app.store.complete(app.store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = completed,
            start = 0, end = 600, text = "Original", uncertain = true)))
        app.store.edit(app.store.passages(id).single(), "Keep this correction")
        val savedPassage = app.store.passages(id).single()
        app.store.finishChunk(app.store.beginChunk(id, 600, false), 1200)
        app.store.state(id, "INTERRUPTED")
        val savedChunks = app.store.chunks(id)
        val decoding = CountDownLatch(1)
        val releaseResult = CountDownLatch(1)
        val decodeReturned = CountDownLatch(1)
        val returnedDuringCancel = AtomicBoolean()
        val closes = AtomicInteger()
        RecordingService.decoderFactoryForTests = {
            object : RecordingDecoder {
                override suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) {
                    decoding.countDown()
                    try {
                        check(releaseResult.await(10, TimeUnit.SECONDS)) { "Test decode was not released." }
                        currentCoroutineContext().ensureActive()
                        store.complete(chunk, listOf(Passage(noteId = id, chunkId = chunk.id,
                            start = chunk.start, end = chunk.end, text = "Synthetic result", uncertain = false)))
                    } finally { decodeReturned.countDown() }
                }
                override fun cancel() {
                    // A decode can return an already-produced result while the stop request is in flight.
                    releaseResult.countDown()
                    returnedDuringCancel.set(decodeReturned.await(3, TimeUnit.SECONDS))
                }
                override fun close() { closes.incrementAndGet() }
            }
        }
        try {
            activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
            assertTrue("Recovery must start decoding", decoding.await(5, TimeUnit.SECONDS))
            assertTrue(app.stopService(Intent(app, RecordingService::class.java)))
            finish(id)
            await { !SessionOperations.matchesSession(capture = false, noteId = id) }
            assertTrue("Decode must return during the cancellation request", returnedDuringCancel.get())
            assertEquals("The in-flight chunk must remain pending", savedChunks, app.store.chunks(id))
            assertEquals(listOf(savedPassage), app.store.passages(id))
            assertEquals("INTERRUPTED", app.store.note(id)!!.state)
            assertArrayEquals(pcm, app.store.audioFile(id).readBytes())
            assertEquals(1, closes.get())
        } finally { releaseResult.countDown() }
    }

    @Test fun startingCaptureExcludesPlaybackBeforeServicePublishesItsNote() {
        val playbackId = app.store.create("en")
        app.store.audioFile(playbackId).writeBytes(ByteArray(32_000))
        app.store.state(playbackId, "READY")
        val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val otherPlayer = SpeechPlayer(app, app.store, playbackScope)
        var playbackError: String? = null
        try {
            activity.onActivity {
                RecordingService.command(it, RecordingService.START)
                // Service callbacks cannot run until this main-thread controller returns.
                assertNull(RecordingState.live.value.noteId)
                otherPlayer.playNote(app.store.note(playbackId)!!) { message -> playbackError = message }
                assertFalse(otherPlayer.playback.value.isPlaying)
                assertNotNull("Playback must be rejected visibly during capture startup", playbackError)
            }
            await { RecordingState.live.value.noteId != null && inputs.isNotEmpty() }
            val id = RecordingState.live.value.noteId!!
            send(RecordingService.STOP)
            finish(id)
        } finally {
            otherPlayer.stop(); playbackScope.cancel()
        }
    }

    @Test fun sameProcessRecoverySealsWritingRangesBeforeDecodingAndCanRetryAFailedSeal() {
        val id = app.store.create("en")
        val original = ByteArray(1500 * 32) { (it % 97).toByte() }
        app.store.audioFile(id).writeBytes(original)
        app.store.beginChunk(id, 0, false)
        app.store.state(id, "INTERRUPTED", "Synthetic failed finalization")
        val decoded = CopyOnWriteArrayList<Chunk>()
        completingDecoder(decoded)
        val trigger = "test_recover_" + id.replace("-", "")
        app.store.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE UPDATE OF state ON chunks
            WHEN NEW.note_id='$id' BEGIN SELECT RAISE(ABORT,'Synthetic unavailable metadata'); END""")
        try {
            activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
            await { app.store.note(id)?.state == "ERROR" }
            finish(id)
            assertTrue(decoded.isEmpty())
            assertEquals("WRITING", app.store.chunks(id).single().state)
        } finally { app.store.writableDatabase.execSQL("DROP TRIGGER $trigger") }

        activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
        await { app.store.note(id)?.state == "READY" }
        finish(id)
        assertEquals(listOf(0L to 1500L), decoded.map { it.start to it.end })
        assertEquals("DONE", app.store.chunks(id).single().state)
        assertEquals(1500L, app.store.note(id)!!.duration)
        assertTrue(inputs.isEmpty())
        assertArrayEquals(original, app.store.audioFile(id).readBytes())
    }

    @Test fun sameProcessRecoveryFindsUnpublishedTailAndPreservesCompletedEdits() {
        val id = app.store.create("en")
        val original = ByteArray(2000 * 32) { (it % 101).toByte() }
        app.store.audioFile(id).writeBytes(original)
        val first = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(first, 1000)
        app.store.complete(app.store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = first,
            start = 0, end = 1000, text = "Original", uncertain = true)))
        app.store.edit(app.store.passages(id).single(), "Keep this correction")
        val saved = app.store.passages(id).single()
        app.store.state(id, "INTERRUPTED", "Synthetic lost Begin metadata")
        val decoded = CopyOnWriteArrayList<Chunk>()
        completingDecoder(decoded)

        activity.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
        await { app.store.note(id)?.state == "READY" }
        finish(id)
        assertEquals(listOf(1000L to 2000L), decoded.map { it.start to it.end })
        assertTrue(app.store.chunks(id).all { it.state == "DONE" })
        assertEquals(saved, app.store.passages(id).single())
        assertTrue(inputs.isEmpty())
        assertArrayEquals(original, app.store.audioFile(id).readBytes())
    }

    private fun completingDecoder(decoded: MutableList<Chunk>) {
        RecordingService.decoderFactoryForTests = {
            object : RecordingDecoder {
                override suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) {
                    decoded += chunk
                    store.complete(chunk, emptyList())
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }

    private fun start(): String {
        activity.onActivity { RecordingService.command(it, RecordingService.START) }
        await { RecordingState.live.value.noteId != null && inputs.isNotEmpty() }
        return RecordingState.live.value.noteId!!
    }
    private fun send(action: String) { activity.onActivity { RecordingService.command(it, action) } }
    private fun finish(id: String) {
        await {
            RecordingState.live.value.noteId == null && !serviceRunning() && app.store.note(id)?.state !in listOf("RECORDING", "PAUSED", "PROCESSING")
        }
        awaitNoNotifications()
    }
    private fun awaitNoNotifications() {
        val manager = app.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (manager.activeNotifications.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Recording notification survived service teardown: ${manager.activeNotifications.map { it.id to it.notification.flags }}",
            manager.activeNotifications.isEmpty())
    }
    private fun createdNotes(): List<Note> = originalIds?.let { baseline -> app.store.notes().filter { it.id !in baseline } }.orEmpty()
    private fun assertOriginalMatchesDeliveredSamples(id: String, source: SyntheticInput) {
        assertOriginalBytesMatchDeliveredSamples(id, source)
        assertEquals(app.store.audioBytes(id) / 32, app.store.note(id)!!.duration)
    }
    private fun assertOriginalBytesMatchDeliveredSamples(id: String, source: SyntheticInput) {
        val pcm = app.store.audioFile(id).readBytes()
        assertEquals(source.samples.get() * 2, pcm.size.toLong())
        val values = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var index = 0L
        while (values.hasRemaining()) assertEquals("PCM differs at sample $index", value(index++).toInt(), values.get().toInt())
    }
    private fun assertNoDuplicateSourceRanges(id: String) {
        val ordered = app.store.chunks(id).sortedWith(compareBy({ it.start }, { it.end }))
        ordered.zipWithNext().forEach { (previous, next) ->
            val overlap = previous.end - next.start
            assertTrue("Unexpected duplicate audio ranges: $previous / $next", overlap <= 0 || (next.overlap && overlap <= 500 && next.end > previous.end))
        }
    }
    @Suppress("DEPRECATION")
    private fun serviceRunning() = app.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == RecordingService::class.java.name }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out: ${RecordingState.live.value}; notes=${createdNotes()}", condition())
    }
    /** A real SQLite writer held on its owning thread, matching inference-commit contention. */
    private class HeldWriter(database: SQLiteDatabase, id: String) : AutoCloseable {
        private val ready = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread {
            try {
                database.beginTransactionNonExclusive()
                try {
                    database.execSQL("UPDATE notes SET title=title WHERE id=?", arrayOf(id))
                    ready.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Test writer was not released." }
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
            } catch (error: Throwable) { failure.set(error); ready.countDown() }
        }.apply { isDaemon = true; name = "focal-test-held-writer"; start() }
        init {
            try {
                check(ready.await(5, TimeUnit.SECONDS)) { "Could not acquire the test SQLite writer." }
                failure.get()?.let { throw AssertionError("Test SQLite writer failed", it) }
            } catch (error: Throwable) { release.countDown(); throw error }
        }
        override fun close() {
            release.countDown(); thread.join(5000)
            assertFalse("Test writer did not finish", thread.isAlive)
            failure.get()?.let { throw AssertionError("Test SQLite writer failed", it) }
        }
    }
    private class SyntheticInput : RecordingInput {
        val samples = AtomicLong()
        @Volatile private var running = false
        @Volatile override var silenced = false
        @Volatile var disconnected = false
        @Volatile var silenceOnNextRead = false
        @Volatile var closed = false
        override val initialized = true
        override fun start() { check(!closed); running = true }
        override fun stop() { running = false }
        override fun read(samples: ShortArray, offset: Int, count: Int): Int {
            SystemClock.sleep(4)
            if (!running) return AudioRecord.ERROR_INVALID_OPERATION
            if (disconnected) return AudioRecord.ERROR_DEAD_OBJECT
            // Deliberately partial 73-sample reads exercise frame boundaries around pause/finish.
            val delivered = minOf(73, count)
            val start = this.samples.getAndAdd(delivered.toLong())
            repeat(delivered) { samples[offset + it] = value(start + it) }
            if (silenceOnNextRead) { silenceOnNextRead = false; silenced = true }
            return delivered
        }
        override fun close() { running = false; closed = true }
    }
    private companion object { fun value(index: Long): Short = ((index % 4000) - 2000).toShort() }
}
