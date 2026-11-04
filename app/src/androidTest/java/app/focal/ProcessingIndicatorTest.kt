package app.focal

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.focal.ui.FocalTheme
import app.focal.ui.FocusProcessingIndicator
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProcessingIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun processingIndicatorHasVisibleMotionAndIndeterminateSemantics() {
        assertTrue("System animations must be enabled to check motion", ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FocalTheme("light") {
                FocusProcessingIndicator(
                    modifier = Modifier.size(width = 32.dp, height = 24.dp)
                        .background(Color.White).testTag("processing"),
                    contentDescription = "Transcribing",
                    color = Color.Black,
                )
            }
        }
        val indicator = compose.onNodeWithTag("processing")
        indicator.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        ))
        fun columnHeights(): IntArray {
            val pixels = indicator.captureToImage().toPixelMap()
            return IntArray(pixels.width) { x ->
                val ink = (0 until pixels.height).filter { y -> pixels[x, y].red < .9f }
                if (ink.isEmpty()) 0 else ink.last() - ink.first() + 1
            }
        }

        compose.mainClock.advanceTimeBy(160)
        val first = columnHeights()
        compose.mainClock.advanceTimeBy(500)
        val second = columnHeights()
        val density = compose.density.density
        val greatestHeightChange = first.indices.maxOf { abs(first[it] - second[it]) }
        assertTrue("The waveform must visibly change bar height, not only opacity: $greatestHeightChange px",
            greatestHeightChange >= (3 * density).roundToInt().coerceAtLeast(3))
    }
}
