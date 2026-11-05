package app.focal

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.LEGACY_WHISPER_ENGINE
import app.focal.data.Passage
import app.focal.data.PassageSizeException
import app.focal.data.transferableTranscript
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class FocalViewModelTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: FocalApplication
    private val owners = mutableListOf<ViewModelStore>()
    private val created = mutableListOf<String>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.storageReady.await() }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.forEach { it.clear() } }
        created.forEach { app.store.delete(it) }
    }

    private fun model(read: (() -> Unit)? = null, delete: ((String) -> Unit)? = null): FocalViewModel {
        val owner = ViewModelStore().also { owners += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FocalViewModel(app, read, delete) as T
        }
        return ViewModelProvider(owner, factory)[FocalViewModel::class.java]
    }

    private fun await(model: FocalViewModel, condition: (LibraryState) -> Boolean): LibraryState =
        runBlocking { withTimeout(10_000) { model.library.first(condition) } }

    @Test fun libraryReadsOffMainAndPublishesSuccessAndFailureOnMain() = runBlocking {
        val readStarted = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val readOnMain = AtomicBoolean(false)
        val failRead = AtomicBoolean(false)
        val model = model(read = {
            if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
            readStarted.countDown()
            check(finishRead.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the synthetic read." }
            if (failRead.get()) throw IOException("Synthetic read failure")
        })
        try {
            assertTrue(readStarted.await(10, TimeUnit.SECONDS))
            assertFalse("Library reads must stay off Main", readOnMain.get())
            // Subscribe before the read completes. An unconfined collector resumes at publication.
            val success = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                model.library.first { it.loaded && it.error == null }
                Looper.myLooper() == Looper.getMainLooper()
            }
            finishRead.countDown()
            val successOnMain = withTimeout(10_000) { success.await() }

            val failure = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                model.library.first { it.error != null }
                Looper.myLooper() == Looper.getMainLooper()
            }
            failRead.set(true)
            model.retryLibrary()
            val failureOnMain = withTimeout(10_000) { failure.await() }

            assertFalse("Retry reads must stay off Main", readOnMain.get())
            assertTrue("Library success must be published on Main", successOnMain)
            assertTrue("Library failure must be published on Main", failureOnMain)
        } finally {
            finishRead.countDown()
        }
    }

    @Test fun failedReadsKeepKnownDataAndRetryAfterInitialAndLaterErrors() {
        val id = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        app.store.rename(id, "Recoverable library")
        val failing = AtomicBoolean(true)
        val model = model(read = { if (failing.get()) throw IOException("Synthetic read failure") })

        val initial = await(model) { it.error != null }
        assertFalse(initial.loaded)
        assertTrue(initial.notes.isEmpty())

        failing.set(false)
        model.retryLibrary()
        await(model) { it.loaded && it.error == null && it.notes.any { note -> note.id == id } }
        model.select(id)
        await(model) { it.selectedId == id && it.note?.id == id && it.error == null }

        failing.set(true)
        model.retryLibrary()
        val failedAgain = await(model) { it.error != null }
        assertTrue(failedAgain.loaded)
        assertEquals(id, failedAgain.note?.id)
        assertTrue(failedAgain.notes.any { it.id == id })
        failing.set(false)
        model.retryLibrary()
        await(model) { it.error == null && it.selectedId == id && it.note?.id == id }
    }

    @Test fun delayedDeletionDoesNotClearTheNewSelection() {
        val first = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        val second = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = model(delete = { id ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            app.store.delete(id)
        })
        try {
            model.select(first)
            await(model) { it.selectedId == first && it.note?.id == first }
            val deletion = model.delete(first)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            model.select(second)
            await(model) { it.selectedId == second && it.note?.id == second }
            release.countDown()
            runBlocking { withTimeout(10_000) { deletion.join() } }
            // Force a fresh read after deletion; an old cached StateFlow value
            // cannot hide an incorrectly cleared selection.
            app.store.rename(second, "Selection after deletion")
            val selected = await(model) { it.selectedId == second && it.note?.id == second && it.note?.title == "Selection after deletion" }
            assertEquals(second, selected.note?.id)
            assertNull(app.store.note(first))
        } finally { release.countDown() }
    }

    @Test fun delayedDeletionExcludesRecoveryAcrossControllersUntilDiskWorkFinishes() {
        val id = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        app.store.audioFile(id).writeBytes(ByteArray(32_000))
        val chunk = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(chunk, 1000)
        app.store.state(id, "INTERRUPTED")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deleting = model(delete = { target ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            app.store.delete(target)
        })
        val recovering = model()
        val deletion = deleting.delete(id)
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            runBlocking {
                val rejected = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { recovering.errors.first() } }
                recovering.retry(id)
                assertTrue(rejected.await().contains("busy"))
            }
            assertEquals("INTERRUPTED", app.store.note(id)!!.state)
            assertEquals(32_000L, app.store.audioBytes(id))
        } finally {
            release.countDown()
            runBlocking { withTimeout(10_000) { deletion.join() } }
        }
        assertNull(app.store.note(id))
        // Completion releases only this deletion's claim, even across controllers.
        val available = SessionOperations.reserveSession(capture = false, noteId = id)
        assertNotNull(available)
        available?.release()
    }

    @Test fun retryWhileRecoveryClaimIsStillHeldShowsBusyMessage() {
        val id = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        val pcm = ByteArray(32_000) { (it % 97).toByte() }
        app.store.audioFile(id).writeBytes(pcm)
        app.store.duration(id, 1000)
        app.store.finishChunk(app.store.beginChunk(id, 0, false), 1000)
        app.store.state(id, "INTERRUPTED", "Synthetic transcription error")
        val savedNote = app.store.note(id)
        val savedChunks = app.store.chunks(id)
        val recovering = model()
        val claim = checkNotNull(SessionOperations.reserveSession(capture = false, noteId = id))
        try {
            assertSame(claim, SessionOperations.acceptSession(claim.token, capture = false, noteId = id))
            assertNull(RecordingState.live.value.noteId)
            runBlocking {
                val feedback = async(start = CoroutineStart.UNDISPATCHED) { recovering.errors.first() }
                recovering.retry(id)
                // Let the subscribed collector process retry's synchronous emission.
                yield()
                assertTrue("Retry during teardown must report that recovery is busy", feedback.isCompleted)
                assertEquals("Finish the current recording or transcription first.", feedback.await())
            }
            assertEquals(savedNote, app.store.note(id))
            assertEquals(savedChunks, app.store.chunks(id))
            assertArrayEquals(pcm, app.store.audioFile(id).readBytes())
            assertTrue(SessionOperations.matchesSession(capture = false, noteId = id))
        } finally { claim.release() }

        // A subsequent recovery can reserve ownership once the prior claim is released.
        checkNotNull(SessionOperations.reserveSession(capture = false, noteId = id)).release()
    }

    @Test fun passageSizeLimitsExplainRecoveryAndKeepTheEditedDraftUnsaved() {
        val sizeMessage = "This saved passage is too large to open. Its original text is still saved."
        val unreadable = model(read = { throw PassageSizeException(sizeMessage) })
        assertEquals(sizeMessage, await(unreadable) { it.error != null }.error)

        val id = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        val chunk = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(chunk, 1000)
        app.store.complete(app.store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk,
            start = 0, end = 1000, text = "Keep the saved text", uncertain = false)))
        val passage = app.store.passages(id).single()
        val editing = model()
        runBlocking {
            val error = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { editing.errors.first() } }
            assertFalse(editing.edit(passage, "x".repeat(128 * 1024 + 1)))
            val message = error.await()
            assertTrue(message.contains("Shorten"))
            assertTrue(message.contains("draft is still open"))
        }
        assertEquals(passage, app.store.passages(id).single())
    }

    @Test fun legacyRestoreWaitsForTheTransferSnapshot() = runBlocking {
        val passage = excludedLegacyPassage()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = AtomicBoolean(false)
        val model = model(read = {
            if (gate.get() && !app.store.passages(passage.noteId).single().excluded) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        model.select(passage.noteId)
        await(model) { it.note?.id == passage.noteId && it.passages == listOf(passage) && it.error == null }

        gate.set(true)
        val restore = async(Dispatchers.Main.immediate) { model.restorePassage(passage) }
        try {
            assertTrue("The database committed before the synthetic read stalled", entered.await(10, TimeUnit.SECONDS))
            assertFalse(app.store.passages(passage.noteId).single().excluded)
            assertTrue(model.library.value.passages.single().excluded)
            assertNull(model.latestTransferSnapshot(passage.noteId))
            assertNull("The action reported success with stale copy text", withTimeoutOrNull(200) { restore.await() })
        } finally {
            gate.set(false)
            release.countDown()
        }
        assertTrue(withTimeout(10_000) { restore.await() })
        val published = checkNotNull(model.latestTransferSnapshot(passage.noteId))
        assertEquals(passage.text, transferableTranscript(published.passages))
    }

    @Test fun refreshFailureKeepsTheRestoredPassageAndDisablesTransfer() = runBlocking {
        val passage = excludedLegacyPassage()
        val failing = AtomicBoolean(false)
        val model = model(read = { if (failing.get()) throw IOException("Synthetic post-commit read failure") })
        model.select(passage.noteId)
        await(model) { it.note?.id == passage.noteId && it.passages == listOf(passage) && it.error == null }

        failing.set(true)
        assertTrue(withTimeout(10_000) { model.restorePassage(passage) })
        assertFalse(app.store.passages(passage.noteId).single().excluded)
        assertNotNull(model.library.value.error)
        assertNull(model.latestTransferSnapshot(passage.noteId))

        failing.set(false)
        model.retryLibrary()
        await(model) { it.note?.id == passage.noteId && it.passages.singleOrNull()?.excluded == false && it.error == null }
        assertEquals(passage.text, transferableTranscript(checkNotNull(model.latestTransferSnapshot(passage.noteId)).passages))
    }

    /** The retired background-sound check left passages like this in existing notes. */
    private fun excludedLegacyPassage(): Passage {
        val saved = completedPassage()
        app.store.writableDatabase.execSQL("UPDATE passages SET excluded=1 WHERE id=?", arrayOf<Any>(saved.id))
        return app.store.passages(saved.noteId).single().also { assertTrue(it.excluded) }
    }

    private fun completedPassage(): Passage {
        val id = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        val chunk = app.store.beginChunk(id, 0, false)
        app.store.finishChunk(chunk, 1000)
        app.store.complete(checkNotNull(app.store.nextChunk(id)), listOf(
            Passage(noteId = id, chunkId = chunk, start = 0, end = 1000, text = "Review this synthetic text", uncertain = true)
        ))
        app.store.state(id, "READY")
        return app.store.passages(id).single()
    }

    @Test fun notificationLaunchIsNotReplayedOverSavedNavigation() {
        val first = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        val second = app.store.create("en", LEGACY_WHISPER_ENGINE).also { created += it }
        app.store.rename(first, "Notification session A")
        app.store.rename(second, "Notification session B")
        val intent = Intent(app, MainActivity::class.java).putExtra(RecordingService.NOTE, first)
        ActivityScenario.launch<MainActivity>(intent).use { activity ->
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Notification session A").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Notification session B").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Notification session B").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Notification session B").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty()
            }
            activity.recreate()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Notification session B").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Notification session A").fetchSemanticsNodes().isEmpty()
            }
        }
    }
}
