package app.focal

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Passage
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.speech.NativeWhisper
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechWorkflowTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private fun ready() {
        kotlinx.coroutines.runBlocking { app.initialized.await() }
        assumeTrue("A real bundled model is required for this check", app.models.ready())
    }
    @Test fun realModelRecognizesSpeechThroughAndroidJni() {
        ready()
        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        // Public-domain JFK fixture: PCM16, mono, 16 kHz, standard 44-byte WAV header.
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
        val handle = NativeWhisper.open(app.models.model.absolutePath)
        try {
            val segments = JSONArray(NativeWhisper.decode(handle, samples, "en", 2).decodeToString())
            val text = (0 until segments.length()).joinToString(" ") { segments.getJSONObject(it).getString("text") }.lowercase()
            assertTrue(text, "ask not" in text && "country" in text)
        } finally { NativeWhisper.close(handle) }
    }
    @Test fun captureContinuesWithoutAnActivityAndCanPauseAndFinish() {
        ready()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        var id: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { RecordingService.command(it, RecordingService.START) }
                await { RecordingState.live.value.noteId != null }
                id = RecordingState.live.value.noteId!!
                await { app.store.audioBytes(id!!) >= 32000 }
                scenario.onActivity { it.finishAndRemoveTask() }
                val before = app.store.audioBytes(id!!)
                await { app.store.audioBytes(id!!) >= before + 32000 }
                RecordingService.command(app, RecordingService.PAUSE)
                await { app.store.note(id!!)?.state == "PAUSED" }
                SystemClock.sleep(500)
                val pausedSize = app.store.audioBytes(id!!)
                SystemClock.sleep(1200)
                assertEquals(pausedSize, app.store.audioBytes(id!!))
                RecordingService.command(app, RecordingService.RESUME)
                await { app.store.audioBytes(id!!) > pausedSize }
                RecordingService.command(app, RecordingService.STOP)
                await { RecordingState.live.value.noteId == null }
                assertEquals("READY", app.store.note(id!!)?.state)
                assertTrue(app.store.audioBytes(id!!) > 32000)
            }
        } finally {
            if (RecordingState.live.value.noteId != null) {
                RecordingService.command(app, RecordingService.STOP)
                await { RecordingState.live.value.noteId == null }
            }
            id?.let { app.store.delete(it) }
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 120_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue("Timed out waiting for recording state", condition())
    }
}
