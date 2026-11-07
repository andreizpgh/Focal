package app.focal.ui.shell

import android.annotation.SuppressLint
import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.WindowSdkExtensions
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.focal.ui.design.Hinge
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * The activity window's folds and hinges (androidx.window's [WindowInfoTracker]), as they change: unfolding, a
 * posture change, a window moved across the hinge. None outside an activity. The first frame already has them where
 * the device's window extensions can say so at once (version 9 and later): a recreation (every rotation, fold or
 * unfold recreates the activity) then lays the panes out at the hinge from the start, instead of a frame or two at the
 * reference width that jump to it. Elsewhere none until the first report.
 */
@Composable
fun rememberDisplayFeatures(): List<DisplayFeature> {
    val activity = LocalActivity.current
    val tracker = remember(activity) { activity?.let(WindowInfoTracker::getOrCreate) }
    val initial = remember(activity, tracker) {
        // The extensions could not say after all, or the tracker does not read the current layout (a test's
        // decorator): the first report says it instead.
        if (activity == null || tracker == null) emptyList() else runCatching { currentFeatures(tracker, activity) }.getOrDefault(emptyList())
    }
    val features = remember(activity, tracker) {
        if (activity == null || tracker == null) emptyFlow() else tracker.windowLayoutInfo(activity).map { it.displayFeatures }
    }
    val current by features.collectAsStateWithLifecycle(initial)
    return current
}

/** The window's folds and hinges now (window extensions 9 and later); none when the extensions cannot say. */
// The version is checked as lint's own explanation shows; its detector does not see the check here (AGP 9 lint).
@SuppressLint("RequiresWindowSdk")
private fun currentFeatures(tracker: WindowInfoTracker, activity: Activity): List<DisplayFeature> {
    if (WindowSdkExtensions.getInstance().extensionVersion >= 9) {
        return tracker.getCurrentWindowLayoutInfo(activity).displayFeatures
    }
    return emptyList()
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
    return hingeOf(bounds.left, bounds.right, width, density, rtl)
}

/** The hinge whose edges are [left] and [right] in the window's pixels, in dp from the start edge of a window [width] wide. */
internal fun hingeOf(left: Int, right: Int, width: Dp, density: Density, rtl: Boolean): Hinge =
    with(density) { Hinge.of(left.toDp(), right.toDp(), width, rtl) }
