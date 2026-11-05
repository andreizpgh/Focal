package app.focal

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.core.EndKind
import app.focal.data.WindowState
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.speech.UltraModel
import org.junit.Assert.*
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SpeechWorkflowTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()

    // Staged waiting notes are transcribed by explicit commands only.
    @Before fun noAutomaticResume() { app.autoResumeTranscription = false }
    @After fun restoreAutomaticResume() { app.autoResumeTranscription = true }

    /** A digitally silent tail is recorded as silent without decoding, so this recovery needs no model. */
    @Test fun recoveryOfASilentTailKeepsTextAndOriginalWithoutAddingParagraphs() {
        kotlinx.coroutines.runBlocking { app.storageReady.await() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            run {
                val id = app.store.create()
                try {
                    val original = ByteBuffer.allocate(3000 * 32).order(ByteOrder.LITTLE_ENDIAN).apply {
                        repeat(16000) { putShort(((it % 200) - 100).toShort()) }
                        repeat(32000) { putShort(0) }
                    }.array()
                    app.store.audioFile(id).writeBytes(original)
                    app.store.duration(id, 1000)
                    val expectedHash = MessageDigest.getInstance("SHA-256").digest(original)
                    Synthetic.commit(app.store, id, Synthetic.plan(0, 0, 1000, EndKind.BREAK), Synthetic.speech("Synthetic original."))
                    val savedParagraphs = app.store.paragraphs(id)
                    val savedWindow = app.store.windows(id).single()
                    // The silent tail has durable PCM but no window after the interruption.
                    app.store.state(id, "INTERRUPTED", "Synthetic interrupted capture")
                    scenario.onActivity { RecordingService.command(it, RecordingService.RECOVER, id) }
                    await { app.store.note(id)?.state == "READY" && RecordingState.live.value.noteId == null }
                    assertEquals(0, app.store.pendingCount(id))
                    assertEquals(3000L, app.store.note(id)!!.duration)
                    assertEquals("", app.store.note(id)!!.error)
                    val windows = app.store.windows(id)
                    assertEquals(savedWindow, windows.first())
                    assertEquals(1000L, windows[1].ownStartMs)
                    assertEquals(3000L, windows.last().ownEndMs)
                    assertTrue(windows.drop(1).all { it.state == WindowState.SILENT })
                    assertEquals(savedParagraphs.map { it.text }, app.store.paragraphs(id).map { it.text })
                    assertArrayEquals(expectedHash,
                        MessageDigest.getInstance("SHA-256").digest(app.store.audioFile(id).readBytes()))
                } finally {
                    if (RecordingState.live.value.noteId == id) {
                        app.stopService(Intent(app, RecordingService::class.java))
                        await { RecordingState.live.value.noteId == null }
                    }
                    app.store.delete(id)
                }
            }
        }
    }

    @Test fun captureContinuesWithoutAnActivityAndCanPauseAndFinish() {
        kotlinx.coroutines.runBlocking { app.storageReady.await() }
        assumeTrue("The bundled speech model is required for this check", UltraModel.isBundled(app))
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
