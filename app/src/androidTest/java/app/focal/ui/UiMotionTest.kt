package app.focal.ui

import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.gallery.LibraryDestination
import app.focal.gallery.idleModel
import app.focal.ui.components.BottomColumn
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.NotePill
import app.focal.ui.components.PlayCircle
import app.focal.ui.components.rememberBottomColumnState
import app.focal.ui.design.FocalTheme
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.ShellState
import app.focal.ui.shell.rememberShellState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Motion and stillness under real input: the settings page follows the finger and settles on a spring, a short swipe
 * springs back, Back closes it with the same motion (spec 4.23); the dock never moves vertically between states and
 * the feedback pill never moves Record (4.3, 4.8). Fake state; no service.
 */
@RunWith(AndroidJUnit4::class)
class UiMotionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: ShellState
    private val s: Float get() = shell.settings.progress.value

    private fun showLibrary() {
        assertTrue("System animations must be enabled", ValueAnimator.areAnimatorsEnabled())
        rule.setContent {
            FocalTheme(dark = false) {
                shell = rememberShellState()
                FocalShell(shell, destination = { LibraryDestination(idleModel()) })
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    /** Values of s sampled every frame for [ms]. */
    private fun sample(ms: Long): List<Float> = buildList {
        repeat((ms / FRAME_MS).toInt()) {
            rule.mainClock.advanceTimeByFrame()
            add(s)
        }
    }

    private fun assertSettles(samples: List<Float>, from: Float, to: Float) {
        val margin = (maxOf(from, to) - minOf(from, to)) * MARGIN
        assertTrue("Passes between $from and $to: $samples", samples.any { it > minOf(from, to) + margin && it < maxOf(from, to) - margin })
        assertEquals(to, samples.last(), 0f)
        val steps = samples.zipWithNext { a, b -> b - a }
        assertTrue("Never turns back (no bounce): $samples", if (to > from) steps.all { it >= -EPSILON } else steps.all { it <= EPSILON })
    }

    @Test fun settingsFollowTheFingerAndSettleOpenThenBackClosesThem() {
        showLibrary()
        rule.onRoot().performTouchInput {
            down(Offset(width * START_X, centerY))
            repeat(DRAG_STEPS) { moveBy(Offset(-width * DRAG / DRAG_STEPS, 0f), delayMillis = STEP_MS) }
        }
        rule.mainClock.advanceTimeByFrame()
        val held = s
        // s is the finger's travel over D_s (the width and the 8 dp seam), less the touch slop at most.
        assertTrue("Follows the finger: s = $held", held in 0.3f..DRAG)
        rule.mainClock.advanceTimeBy(HOLD_MS)
        assertEquals("Holds still under a still finger", held, s, 0f)
        rule.onRoot().performTouchInput { up() }
        assertSettles(sample(SETTLE_MS), held, 1f)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertSettles(sample(SETTLE_MS), 1f, 0f)
    }

    @Test fun aShortSlowSwipeSpringsBack() {
        showLibrary()
        rule.onRoot().performTouchInput {
            down(Offset(width * START_X, centerY))
            repeat(DRAG_STEPS) { moveBy(Offset(-width * SHORT / DRAG_STEPS, 0f), delayMillis = SLOW_STEP_MS) }
            up()
        }
        val released = s
        assertTrue("Tracked before the release: s = $released", released > 0f)
        assertSettles(listOf(released) + sample(SETTLE_MS), released, 0f)
    }

    @Test fun theDockKeepsItsRowAcrossStatesAndTheFeedbackPillNeverMovesRecord() {
        var state by mutableStateOf<DockState>(DockState.LibraryIdle)
        lateinit var bottom: BottomColumnState
        rule.setContent {
            FocalTheme(dark = false) {
                bottom = rememberBottomColumnState()
                BottomColumn(bottom, dock = { Dock(state, DockActions()) }, modifier = Modifier.fillMaxSize())
            }
        }
        fun pill(description: String): DpRect {
            rule.waitForIdle()
            return rule.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
        }
        val record = pill("Record")
        val scope = MainScope()
        rule.runOnUiThread { scope.launch { bottom.feedback.show("Note deleted", FeedbackAction("Undo", FocalIcon.Undo)) } }
        rule.waitForIdle()
        rule.onNodeWithText("Note deleted").assertExists()
        assertEquals("The feedback pill rises above Record, which stays", record, pill("Record"))
        rule.runOnUiThread { bottom.feedback.dismiss() }

        listOf(
            DockState.Live(paused = false) to "Finish",
            DockState.Live(paused = true) to "Finish",
            DockState.Note(PlayCircle.Play, NotePill.Copy()) to "Copy",
            DockState.LibraryEmpty to "Record",
        ).forEach { (next, description) ->
            rule.runOnIdle { state = next }
            val bounds = pill(description)
            assertEquals("$next keeps the dock's row (top)", record.top, bounds.top)
            assertEquals("$next keeps the dock's row (bottom)", record.bottom, bounds.bottom)
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val FRAME_MS = 16L
        const val START_X = 0.8f
        const val DRAG = 0.45f
        const val SHORT = 0.12f
        const val DRAG_STEPS = 12
        const val STEP_MS = 16L
        const val SLOW_STEP_MS = 48L
        const val HOLD_MS = 200L
        const val SETTLE_MS = 1_200L
        const val EPSILON = 1e-4f
        /** An in-between value is at least this share of the travel away from either end. */
        const val MARGIN = 0.1f
    }
}
