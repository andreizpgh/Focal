package app.focal.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalSettle
import kotlinx.coroutines.delay

/**
 * How a list's rows glide to a new place (`animateItem`, `move`, spec 2.8): only while the rows themselves change,
 * for the spring's settle time after rows are inserted, removed or reordered (their [keys] change). Anything else that
 * moves them is not a change of place: a keyboard that opens or closes resizes the list, and at its end the list
 * re-anchors every row. Glided, the rows already on screen would lag behind while a row that comes into view at the
 * top is placed at once, so for a moment two rows would overlap. While the keyboard moves the rows follow it frame by
 * frame, so a change of rows then does not glide either.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberRowPlacement(keys: List<Any>): FiniteAnimationSpec<IntOffset>? {
    var settled by remember { mutableStateOf(keys) }
    LaunchedEffect(keys) {
        delay(FocalSettle.MOVE_MS)
        settled = keys
    }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val keyboardMoving by remember(density) { derivedStateOf { ime.getBottom(density) != imeTarget.getBottom(density) } }
    return if (keys != settled && !keyboardMoving) RowGlide else null
}

private val RowGlide: FiniteAnimationSpec<IntOffset> = FocalMotion.move()
