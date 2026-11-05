package app.focal

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.focal.data.NoteStore
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class RecordingDatesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun recording(store: NoteStore, title: String, day: LocalDate, flagged: Boolean): String {
        val id = store.insertNoteForTests(listOf("Synthetic date navigation passage."), 1_000, title,
            state = if (flagged) "INTERRUPTED" else "READY")
        try {
            store.audioFile(id).writeBytes(ByteArray(32_000))
            val created = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            store.writableDatabase.execSQL("UPDATE notes SET created=? WHERE id=?", arrayOf<Any>(created, id))
            // Publish the final date through the store's normal change notification.
            store.state(id, if (flagged) "INTERRUPTED" else "READY")
            return id
        } catch (error: Throwable) {
            store.delete(id)
            throw error
        }
    }

    private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "MMMM d" else "MMMM d, yyyy", Locale.ENGLISH))
    }

    @Test fun browsingDaysPreservesSearchAndReviewWhileOpeningARecording() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.storageReady.await() }
        val store = app.store
        val ids = mutableListOf<String>()
        val today = LocalDate.now()
        val olderDay = today.minusDays(7)
        val excludedDay = today.minusDays(8)
        val olderLabel = dayLabel(olderDay, today)
        val excludedLabel = dayLabel(excludedDay, today)
        val olderTitle = "qxdates older recording"
        val dates = hasContentDescription("Browse recording dates")
        fun dateState(label: String) = dates and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label)
        fun dayRow(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        fun waitForDay(label: String) {
            try {
                compose.waitUntil(10_000) { compose.onAllNodes(dateState(label)).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                println("Date state diagnostic for $label: " + compose.onRoot().printToString())
                throw error
            }
        }
        try {
            repeat(8) { index ->
                ids += recording(store, "qxdates today $index", today, flagged = index % 2 == 0)
                ids += recording(store, "qxdates yesterday $index", today.minusDays(1), flagged = index % 2 == 0)
            }
            ids += recording(store, olderTitle, olderDay, flagged = true)
            ids += recording(store, "Unrelated recording", excludedDay, flagged = true)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Search recordings").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Search recordings").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("qxdates")
            compose.onNode(hasSetTextAction()).performImeAction()
            // Recordings with the same time are listed newest first; only the top of the list is composed.
            compose.waitUntil(10_000) {
                store.search("qxdates").size == 17 &&
                    compose.onAllNodesWithText("qxdates today 7").fetchSemanticsNodes().isNotEmpty()
            }
            waitForDay("Today")
            compose.onAllNodesWithText("Today").assertCountEquals(1)
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            // The stable top spacer and eight today recordings precede Yesterday.
            list.performScrollToIndex(9)
            waitForDay("Yesterday")
            compose.onNode(hasText("Yesterday") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()

            compose.onNodeWithContentDescription("Browse recording dates").performClick()
            compose.onNodeWithText("Choose day").assertIsDisplayed()
            dayRow("All dates").assertIsSelected().assertTextContains("17 recordings")
            dayRow("Yesterday").assertIsNotSelected()
            dayRow("All dates").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose day").fetchSemanticsNodes().isEmpty() }
            waitForDay("Yesterday")
            compose.onNodeWithContentDescription("Browse recording dates").performClick()
            dayRow(olderLabel).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose day").fetchSemanticsNodes().isEmpty() }
            waitForDay(olderLabel)
            compose.onNodeWithContentDescription("Browse recording dates").assertIsSelected()
            compose.onNodeWithText(olderTitle).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            waitForDay(olderLabel)
            compose.onNodeWithContentDescription("Browse recording dates").assertIsSelected()
            compose.onNodeWithText(olderTitle).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertTextContains("qxdates")

            compose.onNode(hasText("1 to review") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsDisplayed()
            val reviewFilter = hasText("to review", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
            compose.onNode(reviewFilter).performClick()
            compose.onNode(hasText("Show all") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
            compose.onNodeWithContentDescription("Browse recording dates").performClick()
            dayRow("Today").assertTextContains("4 recordings")
            dayRow("Yesterday").assertTextContains("4 recordings")
            dayRow(olderLabel).assertIsSelected().assertTextContains("1 recording")
            dayRow(excludedLabel).assertDoesNotExist()
            dayRow("Today").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose day").fetchSemanticsNodes().isEmpty() }
            waitForDay("Today")
            compose.onAllNodesWithText("Today").assertCountEquals(1)
            compose.onNode(hasSetTextAction()).assertTextContains("qxdates")
            compose.onNode(hasText("Show all") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
            compose.onNodeWithText("Unrelated recording").assertDoesNotExist()
            compose.onNodeWithText(olderTitle).assertDoesNotExist()

            compose.onNodeWithContentDescription("Browse recording dates").performClick()
            dayRow("Today").assertIsSelected()
            dayRow("All dates").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose day").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Browse recording dates").assertIsNotSelected()
            compose.onNode(hasSetTextAction()).assertTextContains("qxdates")
            compose.onNode(hasText("Show all") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
            compose.onNodeWithContentDescription("Browse recording dates").performClick()
            dayRow("All dates").assertIsSelected()
            dayRow(olderLabel).assertTextContains("1 recording")
            dayRow(excludedLabel).assertDoesNotExist()
        } finally {
            ids.forEach(store::delete)
        }
    }
}
