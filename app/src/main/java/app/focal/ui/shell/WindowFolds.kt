package app.focal.ui.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.focal.ui.design.Hinge
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * The activity window's folds and hinges (androidx.window's [WindowInfoTracker]), as they change: unfolding, a
 * posture change, a window moved across the hinge. None outside an activity, and none until the first report.
 */
@Composable
fun rememberDisplayFeatures(): List<DisplayFeature> {
    val activity = LocalActivity.current
    val features = remember(activity) {
        if (activity == null) emptyFlow() else WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { it.displayFeatures }
    }
    val current by features.collectAsStateWithLifecycle(emptyList())
    return current
}

/**
 * The fold or hinge among [features] that splits two panes (spec 5.14), in a window [width] wide: one that runs from
 * top to bottom and separates the window's sides (a foldable in book posture, two screens joined by a hinge). A flat
 * seamless fold does not separate anything, and a horizontal one (tabletop) leaves the layout to the window's size,
 * as on a phone. [features] are in the window's pixels; the shell fills the window, so they are in its own.
 */
internal fun separatingHinge(features: List<DisplayFeature>, width: Dp, density: Density, rtl: Boolean): Hinge? {
    val fold = features.firstNotNullOfOrNull { feature ->
        (feature as? FoldingFeature)?.takeIf { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
    } ?: return null
    val bounds = fold.bounds
    return with(density) { Hinge.of(bounds.left.toDp(), bounds.right.toDp(), width, rtl) }
}
