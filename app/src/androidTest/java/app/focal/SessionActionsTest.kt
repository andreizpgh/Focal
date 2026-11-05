package app.focal

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Passage
import app.focal.recording.RecordingService
import app.focal.ui.FocalApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val longPress = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    @Test fun keepingContextualRenameAfterNotificationPreservesItsTargetAndDraft() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await() }
        val store = app.store
        val other = store.createLegacy("en")
        val target = store.createLegacy("en")
        val title = "Context action fixture"
        val renamed = "Renamed from the list"
        try {
            store.rename(other, "Another saved session")
            store.state(other, "READY")
            store.rename(target, title)
            store.state(target, "READY")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(title) and longPress).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(title) and longPress).performTouchInput { longClick() }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Rename")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Share text").assertIsNotEnabled()
            compose.onNodeWithText("Remove audio").assertIsNotEnabled()
            compose.onNodeWithText("Rename").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextReplacement(renamed)
            compose.activityRule.scenario.onActivity { activity ->
                activity.startActivity(Intent(activity.intent).setClass(activity, MainActivity::class.java)
                    .putExtra(RecordingService.NOTE, other).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Leave this recording?")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains(renamed)
            assertEquals(title, store.note(target)?.title)
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(renamed) and longPress).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Browse recording dates").assertIsDisplayed()
            compose.onNodeWithContentDescription("Recording options").assertDoesNotExist()
            assertEquals(renamed, store.note(target)?.title)
            assertEquals("Another saved session", store.note(other)?.title)
        } finally {
            store.delete(target)
            store.delete(other)
        }
    }

    @Test fun cancelDuringReadDoesNotExposeThePreviousSessionsActions() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await() }
        val store = app.store
        val first = store.createLegacy("en")
        val second = store.createLegacy("en")
        val firstTitle = "First context fixture"
        val secondTitle = "Second context fixture"
        val blockNextRead = AtomicBoolean(false)
        val readStarted = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        try {
            store.rename(first, firstTitle)
            store.state(first, "READY")
            store.rename(second, secondTitle)
            store.audioFile(second).writeBytes(ByteArray(32_000))
            val chunk = store.beginChunk(second, 0, false)
            store.finishChunk(chunk, 1_000)
            store.complete(store.nextChunk(second)!!, listOf(Passage(noteId = second, chunkId = chunk,
                start = 0, end = 1_000, text = "Shareable synthetic text.", uncertain = false)))
            store.duration(second, 1_000)
            store.state(second, "READY")

            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FocalViewModel(app, {
                    if (blockNextRead.getAndSet(false)) {
                        readStarted.countDown()
                        check(finishRead.await(15, TimeUnit.SECONDS)) { "Timed out waiting to release the synthetic read." }
                    }
                }, null) as T
            }
            compose.activityRule.scenario.onActivity { activity ->
                val model = ViewModelProvider(activity, factory)["delayed-actions", FocalViewModel::class.java]
                val opened = MutableStateFlow<Pair<String?, Long>>(null to 0L)
                activity.setContent { FocalApp(model, opened) }
            }
            val firstRow = hasText(firstTitle) and longPress
            val secondRow = hasText(secondTitle) and longPress
            val loading = compose.onNodeWithContentDescription("Loading recording options")
            val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            compose.waitUntil(10_000) { compose.onAllNodes(firstRow).fetchSemanticsNodes().isNotEmpty() }
            blockNextRead.set(true)
            compose.onNode(firstRow).performTouchInput { longClick() }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            compose.onNode(hasText(firstTitle) and heading).assertIsDisplayed()
            loading.assertIsDisplayed()
            compose.onNodeWithText("Rename").assertIsNotEnabled()
            compose.onNodeWithText("Share text").assertIsNotEnabled()
            compose.onNodeWithText("Delete recording").assertIsNotEnabled()

            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            loading.assertDoesNotExist()
            compose.onNode(secondRow).performTouchInput { longClick() }
            compose.onNode(hasText(secondTitle) and heading).assertIsDisplayed()
            compose.onNodeWithText("Share text").assertIsNotEnabled()

            finishRead.countDown()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Share text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(secondTitle) and heading).assertIsDisplayed()
            compose.onNode(hasText(firstTitle) and heading).assertDoesNotExist()
            loading.assertDoesNotExist()
        } finally {
            finishRead.countDown()
            store.delete(first)
            store.delete(second)
        }
    }
}
