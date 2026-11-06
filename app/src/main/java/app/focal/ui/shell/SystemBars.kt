package app.focal.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
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
     * navigation icons once its bottom reaches the navigation inset's top + 12 dp, back above + 8 dp. Call it from a
     * `snapshotFlow` over the clip, never from composition. [clip] null: no material on screen.
     */
    fun followMaterial(clip: Rect?, navigationTop: Float, density: Density) = with(density) {
        if (clip == null) {
            statusOnMaterial = false
            navigationOnMaterial = false
            return@with
        }
        statusOnMaterial = clip.top <= (if (statusOnMaterial) STATUS_RELEASE else STATUS_FLIP).toPx()
        navigationOnMaterial = clip.bottom >= navigationTop + (if (navigationOnMaterial) NAVIGATION_RELEASE else NAVIGATION_FLIP).toPx()
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
