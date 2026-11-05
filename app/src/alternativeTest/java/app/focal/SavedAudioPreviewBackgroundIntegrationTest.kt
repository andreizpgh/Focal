package app.focal

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Passage
import app.focal.recording.SessionOperations
import app.focal.speech.PreviewPhase
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in real graph test. The public fixture is repeated to leave time for a real HOME event. */
class SavedAudioPreviewBackgroundIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pressingHomeStopsTheAlternativeWithoutChangingTheSavedRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await(); app.initialized.await() }
        if (!BuildConfig.FOCAL_ULTRA_BUNDLED) {
            check(File(app.filesDir, "parakeet-probe/model").isDirectory) { "Stage the verified Parakeet model files first." }
        }
        val wav = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
        check(wav.size == 352_044 && sha256(wav) == JFK_SHA256) { "The bundled public WAV fixture changed." }
        val pcm = wav.copyOfRange(44, wav.size)
        val duration = pcm.size * REPEATS / 32L
        val title = "Alternative background ${UUID.randomUUID().toString().take(8)}"
        val store = app.store
        val id = store.create("ru")
        val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java] }
        val preview = model.alternativePreview
        val phases = CopyOnWriteArrayList<PreviewPhase>()
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val audio = store.audioFile(id)
            audio.outputStream().buffered().use { output -> repeat(REPEATS) { output.write(pcm) } }
            var start = 0L
            while (start < duration) {
                val end = minOf(start + 22_000, duration)
                val chunk = store.beginChunk(id, start, false)
                store.finishChunk(chunk, end)
                store.complete(checkNotNull(store.nextChunk(id)), if (start == 0L) listOf(
                    Passage(noteId = id, chunkId = chunk, start = start, end = end,
                        text = "Original synthetic text.", uncertain = true)
                ) else emptyList())
                start = end
            }
            store.edit(store.passages(id).single(), "Keep the manually reviewed words.")
            store.rename(id, title)
            store.duration(id, duration)
            store.state(id, "READY")
            val originalNote = checkNotNull(store.note(id))
            val originalChunks = store.chunks(id)
            val originalPassages = store.passages(id)
            val originalAudio = sha256(audio)

            observer.launch(start = CoroutineStart.UNDISPATCHED) {
                preview.state.collect { state -> state?.phase?.let { phases.add(it) } }
            }
            compose.waitUntil(10_000) {
                model.library.value.notes.any { it.id == id && it.title == title && it.state == "READY" }
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Recording options").performClick()
            val alternative = hasText("Transcribe with Ultra") and hasClickAction()
            compose.waitUntil(10_000) { compose.onAllNodes(alternative and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(alternative).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Preparing alternative transcript").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(PreviewPhase.Running, preview.state.value?.phase)
            assertTrue(SessionOperations.matchesSession(capture = false, noteId = id))

            pressHome()
            runBlocking { withTimeout(120_000) { preview.state.first { it == null } } }
            instrumentation.waitForIdleSync()
            assertEquals(Lifecycle.State.CREATED, compose.activityRule.scenario.state)
            assertTrue("Home must stop an active preview.", PreviewPhase.Stopping in phases)
            assertFalse("The stopped preview must not publish a result.", PreviewPhase.Ready in phases)
            assertFalse(PreviewPhase.Failed in phases)
            assertFalse(SessionOperations.matchesSession(capture = false, noteId = id))
            checkNotNull(SessionOperations.reserveSession(capture = true, noteId = null)).release()
            checkNotNull(SessionOperations.tryDestructive(id)).release()
            assertEquals(originalNote, store.note(id))
            assertEquals(originalChunks, store.chunks(id))
            assertEquals(originalPassages, store.passages(id))
            assertEquals(originalAudio, sha256(audio))
            assertEquals("ru", store.note(id)?.language)
        } finally {
            observer.cancel()
            instrumentation.runOnMainSync { preview.stop() }
            runBlocking { withTimeoutOrNull(15_000) { preview.state.first { it == null } } }
            // Never remove the source while the recognizer still owns its preview claim.
            SessionOperations.tryDestructive(id)?.let { claim ->
                try { store.delete(id) } finally { claim.release() }
            }
        }
    }

    private fun pressHome() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true))
        assertTrue(automation.injectInputEvent(
            KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val REPEATS = 12
        const val JFK_SHA256 = "d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f"
    }
}
