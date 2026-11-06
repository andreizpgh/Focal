package app.focal.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.focal.ui.design.FocalGestures
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** How a feedback message ended. Deferred actions (Delete, Remove audio) commit on [Dismissed]. */
enum class FeedbackResult { ActionPerformed, Dismissed }

/** The optional action: "Undo" carries the undo glyph; "Storage" and "Try again" are words only. */
data class FeedbackAction(val label: String, val icon: FocalIcon? = null)

/** One message on screen. */
@Stable
class FeedbackMessage internal constructor(
    val text: String,
    val action: FeedbackAction?,
    private val continuation: CancellableContinuation<FeedbackResult>,
) {
    internal fun finish(result: FeedbackResult) { if (continuation.isActive) continuation.resume(result) }
}

/**
 * The feedback pill's state (spec 4.8): one message at a time, a new one replaces the old (which ends Dismissed, so
 * its deferred action commits). [show] suspends until the message ends.
 */
@Stable
class FeedbackHostState {
    var current: FeedbackMessage? by mutableStateOf(null)
        private set

    suspend fun show(text: String, action: FeedbackAction? = null): FeedbackResult {
        current?.finish(FeedbackResult.Dismissed)
        var message: FeedbackMessage? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                message = FeedbackMessage(text, action, continuation).also { current = it }
            }
        } finally {
            // A replacing message is already current; only the message that ended clears itself.
            if (current === message) current = null
        }
    }

    /** Ends the message as Dismissed: it timed out, was swiped away, the app stopped or settings covered it. */
    fun dismiss() { current?.finish(FeedbackResult.Dismissed) }

    internal fun perform() { current?.finish(FeedbackResult.ActionPerformed) }
}

/** Feedback pill geometry (spec 4.8). */
object FeedbackMetrics {
    val minHeight = Grid.x48
    /** The message starts 20 dp inside the pill; the action ends 8 dp inside it. */
    val textStart = Grid.x20
    val actionEnd = Grid.x8
    val actionPadding = Grid.x16
    /** From font scale 1.3 the message wraps to two lines and the pill grows with this vertical padding. */
    val wrapPadding = 14.dp
    const val WRAP_FONT_SCALE = 1.3f
    /** It rises this far from behind the dock, and sinks back the same way. */
    val travel = Grid.x16
}

/**
 * The feedback pill (spec 4.8): exactly the bottom column's width, `s1`, the message in `meta` and an optional text
 * action. It rises from behind the dock (translateY +16 to 0 on `move`, alpha on `fade`), exits on `move` +
 * `fadeFast`, and a replacing message crossfades in place. It stays for 8 s, or the user's accessibility timeout, and
 * indefinitely while touch exploration is on; scrolling and taps elsewhere never dismiss it; a swipe down does. A
 * polite live region with the action also as a custom action.
 */
@Composable
fun FeedbackPill(state: FeedbackHostState, modifier: Modifier = Modifier) {
    val message = state.current
    var shown by remember { mutableStateOf(message) }
    val enter = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(message) {
        if (message != null) {
            val replacing = shown != null && alpha.value > 0f
            shown = message
            drag.snapTo(0f)
            if (!replacing) {
                launch { enter.animateTo(1f, FocalMotion.move()) }
                alpha.animateTo(1f, FocalMotion.fade())
            }
        } else {
            launch { enter.animateTo(0f, FocalMotion.move()) }
            alpha.animateTo(0f, FocalMotion.fadeFast())
            shown = null
        }
    }
    // Timing: the recommended accessibility timeout, or none at all while touch exploration is on.
    val context = LocalContext.current
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val platform = context.getSystemService(AccessibilityManager::class.java)
        if (platform?.isTouchExplorationEnabled == true) return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            FocalTiming.FEEDBACK_MS.toLong(), containsIcons = false, containsText = true, containsControls = message.action != null,
        ) ?: FocalTiming.FEEDBACK_MS.toLong()
        delay(timeout)
        state.dismiss()
    }
    // A deferred action commits when the app leaves the foreground.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.dismiss() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val visible = shown ?: return
    val colors = FocalTheme.colors
    val travel = with(density) { FeedbackMetrics.travel.toPx() }
    val wraps = density.fontScale >= FeedbackMetrics.WRAP_FONT_SCALE
    val actionLabel = visible.action?.label
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1 - enter.value) * travel + drag.value
                this.alpha = alpha.value
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                onDragStopped = { velocity ->
                    val flung = velocity > with(density) { FocalGestures.FLING_DP_PER_S.dp.toPx() }
                    if (flung || drag.value > travel) state.dismiss() else drag.animateTo(0f, FocalMotion.move())
                },
            )
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (actionLabel != null) customActions = listOf(CustomAccessibilityAction(actionLabel) { state.perform(); true })
            }
            .heightIn(min = FeedbackMetrics.minHeight)
            .background(colors.s1, FocalShapes.pill),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(
                start = FeedbackMetrics.textStart, end = FeedbackMetrics.actionEnd,
                top = if (wraps) FeedbackMetrics.wrapPadding else 0.dp, bottom = if (wraps) FeedbackMetrics.wrapPadding else 0.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FadeThrough(visible.text, Modifier.weight(1f).padding(end = FeedbackMetrics.actionEnd), contentAlignment = Alignment.CenterStart) { text ->
                Text(
                    text, style = FocalType.meta, color = colors.ink,
                    maxLines = if (wraps) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            visible.action?.let { action ->
                FocalTextButton(
                    text = action.label, onClick = { state.perform() }, leading = action.icon,
                    style = FocalType.action, horizontalPadding = FeedbackMetrics.actionPadding,
                )
            }
        }
    }
}
