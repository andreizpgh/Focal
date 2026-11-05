package app.focal

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.system.Os
import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Passage
import app.focal.recording.SessionOperations
import app.focal.speech.PreviewPhase
import app.focal.speech.UltraModelFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class SavedAudioPreviewIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun copiesAlternativeFromSavedAudioAfterRecreationWithoutChangingTheRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await(); app.initialized.await() }
        val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java] }
        val runId = UUID.randomUUID().toString()
        val title = "Alternative preview ${runId.take(8)}"
        val receipts = File(app.filesDir, "parakeet-probe/receipts")
        check(receipts.mkdirs() || receipts.isDirectory) { "Cannot create the private receipt directory." }
        val receipt = JSONObject().put("schema", 1).put("run_id", runId).put("status", "FAIL")
        val bundledCache = if (BuildConfig.FOCAL_ULTRA_BUNDLED) BundledCache(app.noBackupFilesDir, runId) else null
        var noteId: String? = null
        var phase = "fixture"

        try {
            if (bundledCache != null) bundledCache.isolate()
            else check(File(app.filesDir, "parakeet-probe/model").isDirectory) { "Stage the verified Parakeet model files first." }
            val wav = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
            check(wav.size == 352_044 && sha256(wav) == JFK_SHA256) { "The bundled public WAV fixture changed." }
            // The published hash binds the public WAV's standard PCM16 mono 16 kHz header.
            val pcm = wav.copyOfRange(44, wav.size)
            val frames = pcm.size / 2
            val duration = pcm.size / 32L
            val store = app.store
            val id = store.create("ru").also { noteId = it }
            val audio = store.audioFile(id).apply { writeBytes(pcm) }
            val chunk = store.beginChunk(id, 0, false)
            store.finishChunk(chunk, duration)
            store.complete(checkNotNull(store.nextChunk(id)), listOf(
                Passage(noteId = id, chunkId = chunk, start = 0, end = duration / 2,
                    text = "Initial synthetic transcript.", uncertain = true),
                Passage(noteId = id, chunkId = chunk, start = duration / 2, end = duration,
                    text = EXCLUDED_TEXT, uncertain = true),
            ))
            store.edit(store.passages(id).first(), MANUAL_EDIT)
            store.rename(id, title)
            store.duration(id, duration)
            store.state(id, "READY")
            assertTrue(store.excludePassage(store.passages(id).last()))

            val savedNote = checkNotNull(store.note(id))
            val savedChunks = store.chunks(id)
            val savedPassages = store.passages(id)
            val savedAudioHash = sha256(audio.readBytes())
            assertEquals("ru", savedNote.language)
            val manual = savedPassages.single { it.text == MANUAL_EDIT }
            assertTrue(manual.reviewed && manual.uncertain && !manual.excluded)
            val excluded = savedPassages.single { it.text == EXCLUDED_TEXT }
            assertTrue(excluded.excluded && excluded.originalTranscriptText == EXCLUDED_TEXT)
            receipt.put("fixture_wav_sha256", JFK_SHA256).put("frames", frames)

            phase = "open_recording"
            compose.waitUntil(10_000) {
                model.library.value.notes.any { it.id == id && it.title == title && it.state == "READY" }
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(MANUAL_EDIT).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty()
            }
            val action = previewAction()

            phase = "start_preview"
            val started = SystemClock.elapsedRealtime()
            action.performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(AUTOMATIC_LANGUAGE).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(AUTOMATIC_LANGUAGE).assertIsDisplayed()
            val beforeRecreation = checkNotNull(model.alternativePreview.state.value)
            assertEquals(id, beforeRecreation.noteId)
            receipt.put("phase_before_recreation", beforeRecreation.phase.name)
            assertTrue("Recreate while the real preview is still in progress.",
                beforeRecreation.phase == PreviewPhase.Loading || beforeRecreation.phase == PreviewPhase.Running)

            phase = "recreate_and_wait"
            compose.activityRule.scenario.recreate()
            val restored = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java] }
            assertSame(model, restored)
            compose.waitUntil((120_000 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1)) {
                model.alternativePreview.state.value?.phase in setOf(PreviewPhase.Ready, PreviewPhase.Failed)
            }
            val ready = checkNotNull(model.alternativePreview.state.value)
            assertEquals("The real saved-audio preview must complete.", PreviewPhase.Ready, ready.phase)
            assertEquals(id, ready.noteId)
            assertEquals(frames, ready.completedFrames)
            assertEquals(frames, ready.totalFrames)
            val extractedFiles = bundledCache?.fingerprint()
            if (extractedFiles != null) receipt.put("bundled_model_extracted", true)
            compose.onNodeWithText(AUTOMATIC_LANGUAGE).assertIsDisplayed()
            val candidate = ready.segments.map { it.text }.filter(String::isNotBlank).joinToString("\n\n")
            assertTrue("The alternative text must be present in the preview.",
                compose.onAllNodesWithText(candidate).fetchSemanticsNodes().isNotEmpty())
            receipt.put("menu_to_preview_ready_ms", SystemClock.elapsedRealtime() - started)
            val words = Regex("[A-Za-z]+").findAll(candidate.lowercase(Locale.ROOT)).map { it.value }.toList()
            receipt.put("word_count", words.size)
            assertTrue("The public fixture must yield substantive English.",
                words.size >= 8 && "ask" in words && "country" in words)
            capture("alternative-preview-ready-$runId")

            phase = "copy_and_close"
            val alternativeCopy = hasAnyAncestor(hasTestTag("alternative-copy")) and hasClickAction()
            compose.onNode(alternativeCopy and hasText("Copy text"), useUnmergedTree = true)
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNode(alternativeCopy and hasText("Copied"), useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle {
                val clipboard = app.getSystemService(ClipboardManager::class.java)
                val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                assertTrue("Clipboard must equal the complete alternative.", copied == candidate)
            }
            closePreview(model)

            if (bundledCache != null) {
                phase = "reuse_bundled_model"
                val warmAction = previewAction()
                val warmStarted = SystemClock.elapsedRealtime()
                warmAction.performClick()
                compose.waitUntil(120_000) {
                    model.alternativePreview.state.value?.phase in setOf(PreviewPhase.Ready, PreviewPhase.Failed)
                }
                val warm = checkNotNull(model.alternativePreview.state.value)
                assertEquals(PreviewPhase.Ready, warm.phase)
                assertEquals(frames, warm.completedFrames)
                assertEquals(extractedFiles, bundledCache.fingerprint())
                receipt.put("bundled_model_reused", true)
                    .put("cached_menu_to_preview_ready_ms", SystemClock.elapsedRealtime() - warmStarted)
                closePreview(model)
            }

            phase = "verify_original"
            assertEquals(savedNote, store.note(id))
            assertEquals(savedChunks, store.chunks(id))
            assertEquals(savedPassages, store.passages(id))
            assertEquals(savedAudioHash, sha256(audio.readBytes()))
            assertEquals("ru", store.note(id)?.language)
            receipt.put("status", "PASS").put("original_unchanged", true)
            phase = "complete"
        } finally {
            try {
                try {
                    noteId?.let { id ->
                        compose.activityRule.scenario.onActivity { model.alternativePreview.stop() }
                        runBlocking { withTimeoutOrNull(15_000) { model.alternativePreview.state.first { it == null } } }
                        // A failed timeout must not delete the source while native work still owns it.
                        val claim = SessionOperations.tryDestructive(id)
                        if (claim != null) try { app.store.delete(id) } finally { claim.release() }
                        receipt.put("fixture_deleted", claim != null)
                    }
                } finally {
                    if (bundledCache != null) {
                        val restored = bundledCache.restore()
                        receipt.put("bundled_cache_restored", restored)
                        if (phase == "complete") check(restored) { "The bundled cache is still in use." }
                    }
                }
            } catch (failure: Exception) {
                receipt.put("status", "FAIL")
                throw failure
            } finally {
                receipt.put("phase", phase).put("process_lifetime_peak_rss_kib", peakRssKiB() ?: JSONObject.NULL)
                save(AtomicFile(File(receipts, "preview-$runId.json")), receipt)
            }
        }
    }

    private fun previewAction(): SemanticsNodeInteraction {
        compose.onNodeWithContentDescription("Recording options").assertIsDisplayed().performClick()
        val action = hasText("Transcribe with Ultra") and hasClickAction()
        compose.waitUntil(10_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(action).assertIsDisplayed().assertIsEnabled()
    }

    private fun closePreview(model: FocalViewModel) {
        compose.onNodeWithContentDescription("Close alternative transcript").performClick()
        compose.waitUntil(10_000) {
            model.alternativePreview.state.value == null &&
                compose.onAllNodesWithText(AUTOMATIC_LANGUAGE).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(MANUAL_EDIT).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(MANUAL_EDIT).assertIsDisplayed()
    }

    /** For dedicated instrumentation only. Any prior model directories are moved, never copied. */
    private class BundledCache(parent: File, private val runId: String) {
        private val active = File(parent, UltraModelFiles.directoryName)
        private val partial = File(parent, "${active.name}.partial")
        private val previous = mutableMapOf<File, File>()
        private var isolated = false

        fun isolate() {
            val claim = checkNotNull(SessionOperations.reserveSession(capture = false, noteId = null))
            try {
                for (directory in listOf(active, partial)) {
                    if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) continue
                    check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "Invalid Ultra cache directory." }
                    val backup = File(directory.parentFile, "${directory.name}.test-backup-$runId")
                    check(!Files.exists(backup.toPath(), NOFOLLOW_LINKS))
                    Files.move(directory.toPath(), backup.toPath(), ATOMIC_MOVE)
                    previous[directory] = backup
                }
                isolated = true
            } finally {
                claim.release()
            }
        }

        fun fingerprint(): Map<String, Triple<Long, Long, Long>> {
            check(!Files.exists(partial.toPath(), NOFOLLOW_LINKS)) { "Ultra extraction left a partial directory." }
            val entries = checkNotNull(active.listFiles()) { "Bundled Ultra did not install on first use." }
            assertEquals(MODEL_FILES, entries.associate { it.name to it.length() })
            return entries.associate { file ->
                check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                val stat = Os.stat(file.path)
                file.name to Triple(stat.st_dev, stat.st_ino, stat.st_mtime)
            }
        }

        fun restore(): Boolean {
            if (!isolated && previous.isEmpty()) return true
            val claim = SessionOperations.reserveSession(capture = false, noteId = null) ?: return false
            try {
                if (isolated) {
                    for (directory in listOf(active, partial)) {
                        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) continue
                        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
                        val entries = checkNotNull(directory.listFiles())
                        check(entries.all { it.name in MODEL_FILES && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) {
                            "Unexpected entry in the test's Ultra model cache."
                        }
                        entries.forEach { Files.delete(it.toPath()) }
                        Files.delete(directory.toPath())
                    }
                }
                for ((directory, backup) in previous) {
                    check(!Files.exists(directory.toPath(), NOFOLLOW_LINKS))
                    Files.move(backup.toPath(), directory.toPath(), ATOMIC_MOVE)
                }
                return true
            } finally {
                claim.release()
            }
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun peakRssKiB(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }?.substringAfter(':')?.trim()
                ?.substringBefore(' ')?.toLongOrNull()
        }
    }.getOrNull()

    private fun save(file: AtomicFile, receipt: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(receipt.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val AUTOMATIC_LANGUAGE = "Ultra · Automatic language"
        const val MANUAL_EDIT = "Keep these manually reviewed words."
        const val EXCLUDED_TEXT = "Preserve this excluded synthetic passage."
        const val JFK_SHA256 = "d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f"
        val MODEL_FILES = mapOf("encoder.int8.onnx" to 652_282_526L, "decoder.int8.onnx" to 11_845_274L,
            "joiner.int8.onnx" to 6_355_277L, "tokens.txt" to 93_939L)
    }
}
