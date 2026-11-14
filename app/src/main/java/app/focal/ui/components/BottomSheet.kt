package app.focal.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Bottom sheet geometry (spec 4.19). */
object SheetMetrics {
    val handleWidth = FocalGrid.x32
    val handleHeight = FocalGrid.x4
    val handleTop = FocalGrid.x12
    val padding = FocalGrid.x24
    val bottom = FocalGrid.x16
}

/**
 * The bottom sheet (spec 4.19), used only for the microphone rationale: drawn in the activity's window (no dialog,
 * so the system bars never fall out of step with the theme), `paper` in Light and `s1` in Dark, top radius 28, a
 * 32 x 4 grab handle, the `scrim` behind. It rises on `move` while the scrim fades in (`fade`); a tap on the scrim
 * dismisses it, and predictive back drags it down with the gesture (cancel springs it back on `press`).
 */
@Composable
fun FocalBottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var composed by remember { mutableStateOf(visible) }
    val shown = remember { Animatable(if (visible) 1f else 0f) }
    val back = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            composed = true
            back.snapTo(0f)
            shown.animateTo(1f, FocalMotion.moveUnit())
        } else {
            shown.animateTo(0f, FocalMotion.moveUnit())
            composed = false
        }
    }
    if (!composed) return
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = visible) { events ->
        try {
            events.collect { event -> back.snapTo(event.progress) }
            onDismiss()
        } catch (cancelled: CancellationException) {
            // The handler's coroutine ends with the cancelled gesture, so the sheet springs back in the composition's.
            scope.launch { back.animateTo(0f, FocalMotion.press()) }
            throw cancelled
        }
    }
    val colors = FocalTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { hideFromAccessibility() }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .drawBehind { drawRect(colors.scrim, alpha = shown.value * (1 - back.value)) },
        )
        Column(
            Modifier
                .widthIn(max = FocalLayout.contentMaxWidth)
                .fillMaxWidth()
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer { translationY = (1 - shown.value) * height + back.value * height }
                .semantics { isTraversalGroup = true }
                .background(if (colors.dark) colors.s1 else colors.paper, FocalShapes.sheetTop)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(start = SheetMetrics.padding, end = SheetMetrics.padding, bottom = SheetMetrics.bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.padding(top = SheetMetrics.handleTop))
            Box(Modifier.size(SheetMetrics.handleWidth, SheetMetrics.handleHeight).background(colors.faint, FocalShapes.pill))
            content()
        }
    }
}
