package app.focal.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Which system bars sit over the recording material (spec 2.1, 6.2). Paper screens use the theme's icon appearance;
 * while the scene is behind a bar, that bar uses light icons in both themes. The settings swipe never flips anything
 * (both pages are paper), and the dimmed library behind the morph still reads as paper.
 */
@Stable
class SystemBarsState {
    var statusOnMaterial by mutableStateOf(false)
        private set
    var navigationOnMaterial by mutableStateOf(false)
        private set

    /**
     * Follows the morph's clip ([clip] in window pixels) with the spec's thresholds and hysteresis: the status icons
     * turn light once the clip's top reaches the status bar's top + 12 dp and turn back only below + 16 dp; the
     * navigation icons once the clip reaches 12 dp into the [navigation] bar, back when it is less than 8 dp in. Call
     * it from a `snapshotFlow` over the clip, never from composition. [clip] null: no material on screen.
     */
    fun followMaterial(clip: Rect?, navigation: NavigationBar, density: Density) = with(density) {
        if (clip == null) {
            statusOnMaterial = false
            navigationOnMaterial = false
            return@with
        }
        statusOnMaterial = clip.top <= (if (statusOnMaterial) STATUS_RELEASE else STATUS_FLIP).toPx()
        navigationOnMaterial = navigation.reach(clip) >= (if (navigationOnMaterial) NAVIGATION_RELEASE else NAVIGATION_FLIP).toPx()
    }

    /** [followMaterial] with the navigation bar along the bottom, its top at [navigationTop]. */
    fun followMaterial(clip: Rect?, navigationTop: Float, density: Density) = followMaterial(clip, NavigationBar.bottom(navigationTop), density)

    /**
     * T7: as the scene settles into the dock's pill, each bar returns to the theme's appearance in the first frame the
     * clip's edge leaves it: the top below the status bar's bottom ([statusBottom]), the edge facing the [navigation]
     * bar off its inner edge.
     */
    fun followLanding(clip: Rect, statusBottom: Float, navigation: NavigationBar) {
        statusOnMaterial = clip.top < statusBottom
        navigationOnMaterial = navigation.reach(clip) > 0f
    }

    /** Under reduced motion the scene crossfades in place: both bars flip when its alpha passes one half. */
    fun followCrossfade(alpha: Float) {
        val on = alpha >= HALF
        statusOnMaterial = on
        navigationOnMaterial = on
    }

    private companion object {
        val STATUS_FLIP = 12.dp
        val STATUS_RELEASE = 16.dp
        val NAVIGATION_FLIP = 12.dp
        val NAVIGATION_RELEASE = 8.dp
        const val HALF = 0.5f
    }
}

/**
 * Applies the icon appearance to the window: dark icons on Light paper, light icons on Dark paper and over the
 * material. Edge-to-edge itself (transparent bars, no contrast scrim) is set up by the activity.
 */
@Composable
fun SystemBarsEffect(state: SystemBarsState, dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context.findActivity() ?: return
    val status = state.statusOnMaterial
    val navigation = state.navigationOnMaterial
    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark && !status
            isAppearanceLightNavigationBars = !dark && !navigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Where the navigation bar stands (window pixels): along the bottom, or on a [side], as three buttons do on a phone
 * turned to landscape (spec 5.14). [inner] is its edge toward the window's content.
 */
@Immutable
data class NavigationBar(val side: Side, val inner: Float) {
    enum class Side { Bottom, Left, Right }

    /** How far [clip] reaches past the bar's inner edge, into the bar (negative while it stops short of it). */
    fun reach(clip: Rect): Float = when (side) {
        Side.Bottom -> clip.bottom - inner
        Side.Left -> inner - clip.left
        Side.Right -> clip.right - inner
    }

    companion object {
        fun bottom(top: Float) = NavigationBar(Side.Bottom, top)

        /**
         * From the navigation bar's insets in a window [width] x [height] (pixels, absolute left and right): a side
         * bar only when nothing is at the bottom.
         */
        fun of(width: Float, height: Float, left: Int, right: Int, bottom: Int): NavigationBar = when {
            bottom > 0 || left == 0 && right == 0 -> NavigationBar(Side.Bottom, height - bottom)
            right > 0 -> NavigationBar(Side.Right, width - right)
            else -> NavigationBar(Side.Left, left.toFloat())
        }
    }
}
