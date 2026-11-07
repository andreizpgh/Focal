package app.focal.gallery

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.ui.design.FocalTheme
import app.focal.ui.shell.BackEase
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.ShellState
import app.focal.ui.shell.rememberShellState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The expansion p under real input (spec 6.5, 2.8.5): dragging the live stage down opens the scene, swiping the scene
 * up folds it, and predictive back drives p itself (1 - 0.4 * BackEase(progress)), springs back on cancel and folds
 * on commit. Fake state; no service.
 */
@RunWith(AndroidJUnit4::class)
class SceneGestureTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shell: ShellState

    private fun showLiveLibrary() {
        val scene = live()
        rule.setContent {
            FocalTheme(dark = false) {
                shell = rememberShellState()
                FocalShell(shell, destination = { LibraryDestination(libraryOf(scene)) }, scene = { SceneLayer(shell, scene) })
            }
        }
        rule.waitForIdle()
    }

    private val p: Float get() = shell.live.expansion.value

    @Test fun draggingTheLiveStageDownOpensTheSceneAndSwipingUpFoldsIt() {
        showLiveLibrary()
        rule.onNode(hasContentDescription("Recording", substring = true)).performTouchInput { swipeDown(startY = top + height * 0.2f, endY = top + height * 3f, durationMillis = 400) }
        rule.waitForIdle()
        assertEquals(1f, p, 0f)
        rule.onNode(hasContentDescription("Collapse. Recording continues")).performTouchInput {
            swipeUp(startY = centerY + height * 6f, endY = centerY + height, durationMillis = 150)
        }
        rule.waitForIdle()
        assertEquals(0f, p, 0f)
    }

    /**
     * A drag on the live stage about 30° below the horizontal (rightward: the settings swipe takes only leftward ones)
     * passes the list's vertical slop on its way, but heads more sideways than down: no drag of the scene begins, and
     * it stays folded (spec 6.5). (The finger is lifted before the test waits: a held pull keeps the list's overscroll
     * stretch drawing, so Compose never idles under it.)
     */
    @Test fun aMostlySidewaysDragOnTheStageLeavesTheSceneFolded() {
        showLiveLibrary()
        val openings = shell.live.entries
        rule.onNode(hasContentDescription("Recording", substring = true)).performTouchInput {
            down(Offset(left + width * 0.1f, top + height * 0.2f))
            repeat(DIAGONAL_STEPS) { moveBy(Offset(width * 0.08f, width * 0.08f * TAN_30)) }
            up()
        }
        rule.waitForIdle()
        assertEquals("no drag of the scene began", openings, shell.live.entries)
        assertEquals(0f, p, 0f)
    }

    @Test fun aShortDragSpringsBack() {
        showLiveLibrary()
        rule.onNode(hasContentDescription("Recording", substring = true)).performTouchInput { swipeDown(startY = top + height * 0.2f, endY = top + height * 0.6f, durationMillis = 600) }
        rule.waitForIdle()
        assertEquals(0f, p, 0f)
    }

    @Test fun predictiveBackPreviewsTheFoldAndCommitsIt() {
        showLiveLibrary()
        rule.runOnUiThread { runBlocking { shell.live.expansion.snapTo(1f) } }
        rule.waitForIdle()
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(PROGRESS_X, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        rule.waitForIdle()
        assertEquals(1 - 0.4f * BackEase.transform(HALF), p, TOLERANCE)
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
        assertEquals(1f, p, 0f)
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(PROGRESS_X, EDGE_Y, 1f, BackEventCompat.EDGE_LEFT))
        }
        rule.waitForIdle()
        assertEquals(0.6f, p, TOLERANCE)
        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(0f, p, 0f)
    }

    private companion object {
        const val EDGE_Y = 900f
        const val PROGRESS_X = 300f
        const val HALF = 0.5f
        const val TOLERANCE = 0.01f
        const val DIAGONAL_STEPS = 10
        const val TAN_30 = 0.577f
    }
}
