package app.focal

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.recording.LiveSession
import app.focal.ui.FocalTheme
import app.focal.ui.LibraryScreen
import app.focal.ui.NoteScreen
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UiMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchAndFilterMoveExistingRowsInBothDirections() {
        assertTrue("System animations must be enabled", ValueAnimator.areAnimatorsEnabled())
        val now = System.currentTimeMillis()
        val notes = listOf(
            Note(id = "first", title = "First session", created = now, language = "en", state = "READY",
                duration = 30_000, error = ""),
            Note(id = "second", title = "Flagged session", created = now, language = "en", state = "READY",
                duration = 30_000, error = "", reviewCount = 1),
        )
        var searchOpen by mutableStateOf(false)
        var attentionOnly by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FocalTheme("light") {
                Box(Modifier.size(width = 360.dp, height = 720.dp)) {
                    LibraryScreen(notes = notes, loaded = true, live = LiveSession(), open = {},
                        searchOpen = searchOpen, setSearchOpen = { searchOpen = it },
                        attentionOnly = attentionOnly, setAttentionOnly = { attentionOnly = it },
                        inputActive = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        fun y(title: String) = compose.onNodeWithText(title).getUnclippedBoundsInRoot().top.value
        fun requireIntermediate(start: Float, middle: Float, end: Float, label: String) {
            assertTrue("$label did not move enough: $start -> $end", abs(end - start) > 4f)
            assertTrue("$label did not pass between endpoints: $start -> $middle -> $end",
                middle > minOf(start, end) + .5f && middle < maxOf(start, end) - .5f)
        }

        val closed = y("First session")
        compose.runOnIdle { searchOpen = true }
        compose.mainClock.advanceTimeBy(100)
        val opening = y("First session")
        compose.mainClock.advanceTimeBy(1_000)
        val open = y("First session")
        requireIntermediate(closed, opening, open, "Search opening")
        compose.runOnIdle { searchOpen = false }
        compose.mainClock.advanceTimeBy(100)
        val closing = y("First session")
        compose.mainClock.advanceTimeBy(1_000)
        val closedAgain = y("First session")
        requireIntermediate(open, closing, closedAgain, "Search closing")

        val all = y("Flagged session")
        compose.runOnIdle { attentionOnly = true }
        compose.mainClock.advanceTimeBy(100)
        val filtering = y("Flagged session")
        compose.mainClock.advanceTimeBy(1_000)
        val filtered = y("Flagged session")
        requireIntermediate(all, filtering, filtered, "Review filter removal")
        compose.runOnIdle { attentionOnly = false }
        compose.mainClock.advanceTimeBy(100)
        val restoring = y("Flagged session")
        compose.mainClock.advanceTimeBy(1_000)
        val restored = y("Flagged session")
        requireIntermediate(filtered, restoring, restored, "Review filter insertion")
    }

    @Test fun searchRemainsAvailableAfterBrowsingFarDownTheList() {
        val now = System.currentTimeMillis()
        val notes = (1..30).map { index ->
            Note(id = "session-$index", title = "Session $index", created = now, language = "en", state = "READY",
                duration = 30_000, error = "")
        }
        var searchOpen by mutableStateOf(false)
        lateinit var listState: LazyListState
        compose.setContent {
            FocalTheme("light") {
                listState = rememberLazyListState()
                Box(Modifier.size(width = 360.dp, height = 720.dp)) {
                    LibraryScreen(notes = notes, loaded = true, live = LiveSession(), open = {},
                        searchOpen = searchOpen, setSearchOpen = { searchOpen = it },
                        listState = listState, inputActive = false)
                }
            }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(18)
        val indexBefore = compose.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("The fixture must be scrolled well below the top", indexBefore >= 15)
        compose.runOnIdle { searchOpen = true }
        compose.onNodeWithContentDescription("Search sessions").assertIsDisplayed()
        assertEquals("Opening search must preserve the visible list position", indexBefore,
            compose.runOnIdle { listState.firstVisibleItemIndex })
    }

    @Test fun timestampAndPassageFirstLineShareBaseline() {
        val note = Note(id = "note", title = "A session", created = System.currentTimeMillis(), language = "en",
            state = "READY", duration = 60_000, error = "")
        val passage = Passage(id = 1, noteId = note.id, chunkId = "chunk", start = 15_000, end = 20_000,
            text = "A synthetic passage for alignment.", uncertain = false)
        compose.setContent {
            FocalTheme("light") {
                Box(Modifier.size(width = 360.dp, height = 720.dp)) {
                    NoteScreen(note = note, library = LibraryState(note = note, passages = listOf(passage), loaded = true),
                        live = null, reviewOnly = false, toggleReview = {}, edit = {}, retry = {},
                        playing = false, audioAvailable = true, play = {}, rename = {})
                }
            }
        }
        fun baseline(text: String): Float {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing text layout for $text", layouts.size == 1)
            return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        val timestamp = baseline("00:15")
        val transcript = baseline("A synthetic passage for alignment.")
        assertTrue("Timestamp and first line differ: $timestamp vs $transcript px", abs(timestamp - transcript) <= 1.5f)
    }
}
