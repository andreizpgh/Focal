package app.focal.gallery

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.ui.design.FocalTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders every screen and state of the rebuilt interface from fake, immutable state (no service, no model) in
 * Light and Dark, at the design's 412 x 915 dp reference frame and a fixed density of 2, and writes each as a PNG to
 * the app's external files (`gallery/<stage>/<shot>.png`) for side-by-side review with the design renders.
 *
 * Run it on a display of at least 824 x 1830 px at 320 dpi (`adb shell wm size 824x1830; adb shell wm density 320`),
 * so the frame is on screen in full; then `adb pull /sdcard/Android/data/app.focal/files/gallery`.
 */
@RunWith(AndroidJUnit4::class)
class GalleryTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var shot by mutableStateOf<Shot?>(null)

    @Before
    fun edgeToEdge() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
    }

    @Test
    fun foundation() = render("foundation", FoundationShots.all)

    @Test
    fun library() = render("library", LibraryShots.all)

    @Test
    fun scene() = render("scene", SceneShots.all)

    private fun render(stage: String, shots: List<Shot>) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "gallery/$stage")
            .apply { deleteRecursively(); mkdirs() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val current = shot ?: return@setContent
            key(current.name) {
                CompositionLocalProvider(LocalDensity provides Density(DENSITY, current.fontScale)) {
                    FocalTheme(dark = current.dark) {
                        Box(Modifier.testTag(FRAME).requiredSize(FRAME_WIDTH, FRAME_HEIGHT).background(FocalTheme.colors.paper)) {
                            current.content()
                        }
                    }
                }
            }
        }
        for (next in shots) {
            shot = next
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            next.click?.let { tag ->
                rule.mainClock.advanceTimeBy(SETTLE_MS)
                rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            }
            rule.mainClock.advanceTimeBy(next.settleMs)
            rule.waitForIdle()
            val image = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
            File(directory, "${next.name}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        }
    }

    private companion object {
        const val FRAME = "gallery-frame"
        const val DENSITY = 2f
        val FRAME_WIDTH = 412.dp
        val FRAME_HEIGHT = 915.dp
        const val SETTLE_MS = 1_000L
        const val PNG_QUALITY = 100
    }
}

/** One gallery render: [content] in a fresh composition, captured after [settleMs] of animation time. */
class Shot(
    val name: String,
    val dark: Boolean,
    val fontScale: Float = 1f,
    val settleMs: Long = 1_000L,
    /** A node to click (by test tag) once the shot has settled, before [settleMs] more. */
    val click: String? = null,
    val content: @Composable () -> Unit,
)

/** Light and Dark variants of the same composition. */
fun themed(name: String, fontScale: Float = 1f, settleMs: Long = 1_000L, content: @Composable () -> Unit): List<Shot> = listOf(
    Shot("$name-light", dark = false, fontScale = fontScale, settleMs = settleMs, content = content),
    Shot("$name-dark", dark = true, fontScale = fontScale, settleMs = settleMs, content = content),
)
