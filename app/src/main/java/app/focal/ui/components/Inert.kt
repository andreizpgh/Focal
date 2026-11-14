package app.focal.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * A layer that is off screen or covered (the page beside settings, the pages under the open scene or under search, a
 * bar that Edit or Find replaced): while [inert], accessibility services do not see it, and keyboard and D-pad focus
 * cannot enter it (spec 4.23.1, 8.5). Focus properties reach only the nearest focus target, and every scrollable is
 * one, so `canFocus = false` would leave the rows inside a list focusable; the layer is a focus group instead, whose
 * entry is cancelled, so a search into it moves on past it. The group stays in place either way, so toggling it never
 * rebuilds the focus tree under it.
 */
fun Modifier.inert(inert: Boolean): Modifier = this
    .then(if (inert) Modifier.clearAndSetSemantics { } else Modifier)
    .focusProperties { if (inert) onEnter = { cancelFocusChange() } }
    .focusGroup()

/**
 * While [closed], a touch that lands on the layer stops at it and presses nothing inside; a finger that was already
 * down when it closed goes on as it was (a pull that opened a page over the layer keeps its finger to the end).
 */
fun Modifier.closedToNewTouches(closed: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        val shut = HashSet<PointerId>()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (change in event.changes) {
                if (change.changedToDownIgnoreConsumed() && closed()) shut += change.id
                if (change.id in shut) {
                    change.consume()
                    if (!change.pressed) shut -= change.id
                }
            }
        }
    }
}

/**
 * While [on], every touch on the layer stops at it and presses nothing inside: a bar that another replaced, a menu
 * that is fading out.
 */
fun Modifier.untouchable(on: Boolean): Modifier = if (!on) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
