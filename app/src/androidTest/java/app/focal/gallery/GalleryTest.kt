package app.focal.gallery

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.ui.design.FocalTheme
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders every screen and state of the rebuilt interface from fake, immutable state (no service, no model) in
 * Light and Dark, at the design's 412 x 915 dp reference frame and a fixed density of 2, and writes each as a PNG to
 * the app's external files (`gallery/<stage>/<shot>.png`) for side-by-side review with the design renders.
 *
 * Run it on a display of at least 824 x 1830 px at 320 dpi (`adb shell wm size 824x1830; adb shell wm density 320`),
 * so the frame is on screen in full; then `adb pull /sdcard/Android/data/app.focal/files/gallery`. To re-render a few
 * shots, pass their name prefixes: `-e shots t16-open,now-playing` (the others in the stage's folder are kept).
 *
 * The window sizes of spec 5.14 ([AdaptiveShots]) need a display of their own size, and are skipped on any smaller
 * one: `landscape` with that display turned and locked (`adb shell settings put system accelerometer_rotation 0;
 * adb shell wm user-rotation lock 1`), `tablet` on 1600 x 2560 px and `wide` on it turned. Run each alone
 * (`-e class app.focal.gallery.GalleryTest#landscape`).
 */
@RunWith(AndroidJUnit4::class)
class GalleryTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var shot by mutableStateOf<Shot?>(null)

    @Before
    fun edgeToEdge() {
        // Touch mode, whatever a test before left (a hardware key leaves it): focus rings show only in keyboard mode.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            // As MainActivity: the keyboard resizes the window, it never pans it (a landscape keyboard would push
            // the frame up under the status bar).
            @Suppress("DEPRECATION")
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test
    fun foundation() = render("foundation", FoundationShots.all)

    @Test
    fun library() = render("library", LibraryShots.all)

    @Test
    fun scene() = render("scene", SceneShots.all)

    @Test
    fun note() = render("note", NoteShots.all)

    @Test
    fun finish() = render("finish", FinishShots.all)

    @Test
    fun coverage() = render("coverage", CoverageShots.all)

    @Test
    fun transitions() = render("transitions", TransitionShots.all)

    /** A phone in landscape (spec 5.14): run alone, with the display turned to 1830 x 824 px at 320 dpi. */
    @Test
    fun landscape() = render("landscape", AdaptiveShots.landscape)

    /** A tablet held upright, one pane (800 x 1280 dp): run alone on a 1600 x 2560 px display at 320 dpi. */
    @Test
    fun tablet() = render("tablet", AdaptiveShots.tablet)

    /** Two panes (1280 x 800 dp): run alone on a 2560 x 1600 px display at 320 dpi. */
    @Test
    fun wide() = render("wide", AdaptiveShots.wide)

    private fun render(stage: String, all: List<Shot>) {
        // `-e shots t16-,now-playing` renders only the shots whose names start with one of these, over the others.
        val only = InstrumentationRegistry.getArguments().getString(SHOTS_ARGUMENT)?.split(',')?.filter { it.isNotBlank() }
        val shots = if (only == null) all else all.filter { shot -> only.any { shot.name.startsWith(it) } }
        // A stage whose frames do not fit the display (the landscape, tablet and two-pane frames on a phone held
        // upright) is skipped, not rendered cut off: run it on a display of its size.
        val window = rule.activity.window.decorView
        val fits = shots.all { it.width.value * DENSITY <= window.width && it.height.value * DENSITY <= window.height }
        Assume.assumeTrue("The display is smaller than this stage's frames", fits)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "gallery/$stage")
            .apply { if (only == null) deleteRecursively(); mkdirs() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            KeyboardProbe(keyboard)
            val current = shot ?: return@setContent
            key(current.name) {
                CompositionLocalProvider(LocalDensity provides Density(DENSITY, current.fontScale)) {
                    FocalTheme(dark = current.dark) {
                        Box(Modifier.testTag(FRAME).requiredSize(current.width, current.height).background(FocalTheme.colors.paper)) {
                            current.content()
                        }
                    }
                }
            }
        }
        // The first composition of the window still lacks its insets: warm up on the first shot, uncaptured.
        shot = shots.firstOrNull()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        shot = null
        rule.mainClock.advanceTimeByFrame()
        for (next in shots) {
            shot = next
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            next.click?.let { tag ->
                rule.mainClock.advanceTimeBy(SETTLE_MS)
                rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            }
            // Content prepared off the main thread arrives in real time: frames run until it is on screen.
            next.await?.let { text ->
                rule.waitUntil(AWAIT_TIMEOUT_MS) {
                    rule.mainClock.advanceTimeByFrame()
                    rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
            }
            rule.mainClock.advanceTimeBy(next.settleMs)
            rule.waitForIdle()
            // The keyboard moves in real time, not on the test's clock: a shot with a focused field is taken with the
            // keyboard fully open, every other one with it at rest.
            val typing = rule.onAllNodes(isFocused() and hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            awaitKeyboard { keyboard.atRest && (!typing || keyboard.bottom > 0) }
            if (typing) {
                // The page answers the keyboard on the test's clock (a focused field scrolls itself into view above
                // it): let that land too.
                rule.mainClock.advanceTimeBy(next.settleMs)
                rule.waitForIdle()
            }
            val image = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
            File(directory, "${next.name}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            // ... and the next shot starts with it closed, so no list is resized under it while it plays.
            shot = null
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            awaitKeyboard { keyboard.atRest && keyboard.bottom == 0 }
        }
    }

    private val keyboard = KeyboardState()

    private fun awaitKeyboard(condition: () -> Boolean) {
        try {
            rule.waitUntil(KEYBOARD_TIMEOUT_MS, condition)
        } catch (_: ComposeTimeoutException) {
            // A keyboard that never shows (or never leaves) does not hold the gallery up.
        }
    }

    private companion object {
        const val FRAME = "gallery-frame"
        const val DENSITY = 2f
        const val SETTLE_MS = 1_000L
        const val PNG_QUALITY = 100
        const val SHOTS_ARGUMENT = "shots"
        const val KEYBOARD_TIMEOUT_MS = 3_000L
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}

/** Where the keyboard is, read in the layout phase as the window's insets change (plain fields: the test polls them). */
private class KeyboardState {
    @Volatile var bottom = 0
    @Volatile var atRest = true
}

/** Reports the keyboard's inset and whether it has reached the end of its animation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardProbe(state: KeyboardState) {
    val ime = WindowInsets.ime
    val target = WindowInsets.imeAnimationTarget
    Spacer(
        Modifier.layout { measurable, constraints ->
            state.bottom = ime.getBottom(this)
            state.atRest = state.bottom == target.getBottom(this)
            val placeable = measurable.measure(constraints)
            layout(0, 0) { placeable.place(0, 0) }
        },
    )
}

/** One gallery render: [content] in a fresh composition, captured after [settleMs] of animation time. */
class Shot(
    val name: String,
    val dark: Boolean,
    val fontScale: Float = 1f,
    val settleMs: Long = 1_000L,
    /** A node to click (by test tag) once the shot has settled, before [settleMs] more. */
    val click: String? = null,
    /** Text the shot waits for (real time) after [click]: content that is prepared off the main thread. */
    val await: String? = null,
    /** The frame: the design's 412 x 915 dp reference, or a landscape, tablet or two-pane window (spec 5.14). */
    val width: Dp = 412.dp,
    val height: Dp = 915.dp,
    val content: @Composable () -> Unit,
)

/** Light and Dark variants of the same composition. */
fun themed(name: String, fontScale: Float = 1f, settleMs: Long = 1_000L, content: @Composable () -> Unit): List<Shot> = listOf(
    Shot("$name-light", dark = false, fontScale = fontScale, settleMs = settleMs, content = content),
    Shot("$name-dark", dark = true, fontScale = fontScale, settleMs = settleMs, content = content),
)
