package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.GlyphSize
import app.focal.ui.components.focusRing
import app.focal.ui.components.pressScale
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.spokenDuration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Player strip geometry (spec 4.10) and the jump pill's (4.11). */
object PlayerMetrics {
    val height = FocalGrid.x48
    val timeMin = FocalGrid.x40
    val trackGap = FocalGrid.x12
    val track = FocalGrid.x4
    /** The track breaks this far on each side of the playhead. */
    val trackBreak = FocalGrid.x4
    val playheadWidth = FocalGrid.x4
    val playhead = FocalGrid.x24
    val playheadDragging = FocalGrid.x32
    val jumpHeight = FocalGrid.x40
    val jumpTarget = FocalGrid.x48
    val jumpStart = FocalGrid.x12
    val jumpEnd = FocalGrid.x16
    val jumpGlyphGap = FocalGrid.x8
    /** The jump pill's bottom sits this far above the strip or the dock. */
    val jumpAbove = FocalGrid.x12
    const val JUMP_START_SCALE = 0.9f
}

/**
 * The player's position as the page draws it: the audio's own position, read per frame ([frame] ticks while it plays,
 * so only draw lambdas and once-a-second texts follow it), or the playhead under the finger while it scrubs.
 */
@Stable
class PlayerPosition(private val audio: () -> Long) {
    var durationMs by mutableLongStateOf(0L)
    /** Bumped every frame while audio plays; read it where the position is read. */
    var frame by mutableLongStateOf(0L)
    /** The playhead under the finger, while it is dragged. */
    var scrubMs by mutableStateOf<Long?>(null)

    fun ms(): Long {
        frame
        return (scrubMs ?: audio()).coerceIn(0, durationMs.coerceAtLeast(0))
    }

    /** Whole seconds shown: changes once a second, so the time labels recompose once a second. */
    val seconds: Long by derivedStateOf { NoteTimes.seconds(ms()) }
}

/**
 * The player strip (spec 4.10): elapsed time, the track with the centre-bar playhead, the total. The whole 48 dp row is
 * the drag target: the playhead grows to 32 dp while it is dragged and the text follows it; the audio seeks on
 * release. For TalkBack it is one adjustable "Audio position", moved 5 s per swipe.
 */
@Composable
internal fun PlayerStrip(position: PlayerPosition, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val seek by rememberUpdatedState(onSeek)
    val head = remember { Animatable(PlayerMetrics.playhead.value) }
    val dragging = position.scrubMs != null
    LaunchedEffect(dragging, reduced) {
        val target = (if (dragging) PlayerMetrics.playheadDragging else PlayerMetrics.playhead).value
        if (reduced) head.snapTo(target) else head.animateTo(target, FocalMotion.press())
    }
    val duration = position.durationMs
    val label = stringResource(R.string.a11y_audio_position)
    val spokenAt = spokenDuration(position.seconds)
    val spokenTotal = spokenDuration(NoteTimes.seconds(duration))
    val state = stringResource(R.string.a11y_position_of, spokenAt, spokenTotal)
    val track = remember { TrackBounds() }
    Row(
        modifier
            .fillMaxWidth()
            .height(PlayerMetrics.height)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = state
                // The whole seconds the labels show, not the per-frame position: the node changes once a second.
                progressBarRangeInfo = ProgressBarRangeInfo(
                    (position.seconds * MS).toFloat(), 0f..PlayerSteps.rangeEnd(duration), PlayerSteps.steps(duration),
                )
                setProgress { target ->
                    seek(target.roundToInt().toLong().coerceIn(0, duration))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val left = track.left
                    val width = track.width.coerceAtLeast(1f)
                    fun at(x: Float): Long {
                        val fraction = ((x - left) / width).coerceIn(0f, 1f)
                        return ((if (rtl) 1 - fraction else fraction) * position.durationMs).roundToInt().toLong()
                    }
                    position.scrubMs = at(down.position.x)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            position.scrubMs = at(change.position.x)
                            change.consume()
                        }
                        position.scrubMs?.let(seek)
                    } finally {
                        // Also when the strip leaves under the finger (the audio ended, a recording began), which
                        // cancels the gesture: a scrub position left behind would hold the next playback's playhead.
                        position.scrubMs = null
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            NoteTimes.clock(position.seconds * MS), style = FocalType.caption, color = colors.ink, maxLines = 1,
            modifier = Modifier.widthIn(min = PlayerMetrics.timeMin),
        )
        Spacer(Modifier.width(PlayerMetrics.trackGap))
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPlaced {
                    track.left = it.positionInParent().x
                    track.width = it.size.width.toFloat()
                }
                .drawBehind {
                    val total = position.durationMs.coerceAtLeast(1)
                    val fraction = position.ms().toFloat() / total
                    val x = (if (rtl) 1 - fraction else fraction) * size.width
                    val track = PlayerMetrics.track.toPx()
                    val gap = PlayerMetrics.trackBreak.toPx() + PlayerMetrics.playheadWidth.toPx() / 2
                    val top = (size.height - track) / 2
                    val round = CornerRadius(track / 2)
                    val (playedFrom, playedTo) = if (rtl) (x + gap) to size.width else 0f to (x - gap)
                    val (restFrom, restTo) = if (rtl) 0f to (x - gap) else (x + gap) to size.width
                    if (restTo > restFrom) drawRoundRect(colors.lens, Offset(restFrom, top), Size(restTo - restFrom, track), round)
                    if (playedTo > playedFrom) drawRoundRect(colors.ink, Offset(playedFrom, top), Size(playedTo - playedFrom, track), round)
                    val headWidth = PlayerMetrics.playheadWidth.toPx()
                    val headHeight = head.value.dp.toPx()
                    drawRoundRect(
                        colors.ink, Offset(x - headWidth / 2, (size.height - headHeight) / 2), Size(headWidth, headHeight),
                        CornerRadius(headWidth / 2),
                    )
                },
        )
        Spacer(Modifier.width(PlayerMetrics.trackGap))
        Text(
            NoteTimes.clock(duration), style = FocalType.caption, color = colors.recede, maxLines = 1, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = PlayerMetrics.timeMin),
        )
    }
}

private const val MS = 1000L

/** Where the track lies in the strip, so a drag maps onto it whatever width the times take. */
private class TrackBounds {
    var left = 0f
    var width = 0f
}

/**
 * Enters from 16 dp lower with alpha (`move` / `fade`) and leaves the same way (spec 4.10); [onGone] runs once it has
 * left, so the strip stays in the bottom column for its exit.
 */
@Composable
internal fun StripMotion(visible: Boolean, onGone: () -> Unit, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(visible, reduced) {
        val target = if (visible) 1f else 0f
        if (reduced) shown.snapTo(target) else shown.animateTo(target, if (visible) FocalMotion.fade() else FocalMotion.fadeFast())
        if (!visible) gone()
    }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(visible, reduced) {
        val target = if (visible) 1f else 0f
        if (reduced) rise.snapTo(target) else rise.animateTo(target, FocalMotion.move())
    }
    Box(
        Modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1 - rise.value) * FocalGrid.x16.toPx()
        },
    ) { content() }
}

/**
 * "Now playing" with ↑ or ↓ toward the playing line, while follow is suspended (spec 4.11): `s1`, 40 dp (48 dp target),
 * its right edge on the column's, 12 dp above the topmost bottom element. It enters with `fade` and a 0.9 to 1 scale
 * (`press`) and leaves on `fadeFast`.
 */
@Composable
internal fun JumpPill(visible: Boolean, up: Boolean, bottom: BottomColumnState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(PlayerMetrics.JUMP_START_SCALE) }
    LaunchedEffect(visible, reduced) {
        if (reduced) {
            alpha.snapTo(if (visible) 1f else 0f)
            scale.snapTo(1f)
        } else if (visible) {
            scale.snapTo(PlayerMetrics.JUMP_START_SCALE)
            coroutineScope {
                launch { scale.animateTo(1f, FocalMotion.press()) }
                alpha.animateTo(1f, FocalMotion.fade())
            }
        } else alpha.animateTo(0f, FocalMotion.fadeFast())
    }
    if (!visible && alpha.value <= 0f) return
    val source = remember { MutableInteractionSource() }
    val description = stringResource(R.string.jump_now_playing)
    Box(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints())
                val column = minOf(constraints.maxWidth - 2 * FocalLayout.gutter.roundToPx(), FocalLayout.contentMaxWidth.roundToPx())
                val right = (constraints.maxWidth - column) / 2 + column
                val anchor = bottom.top.value.takeUnless { it.isNaN() } ?: constraints.maxHeight.toFloat()
                // The 48 dp target is centred on the 40 dp pill, whose bottom is 12 dp above the element under it.
                val pillBottom = anchor - PlayerMetrics.jumpAbove.toPx()
                val y = (pillBottom + (PlayerMetrics.jumpTarget - PlayerMetrics.jumpHeight).toPx() / 2 - placeable.height).roundToInt()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = if (layoutDirection == LayoutDirection.Rtl) constraints.maxWidth - right else right - placeable.width
                    placeable.place(x, y)
                }
            }
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .height(PlayerMetrics.jumpTarget)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (visible) this.onClick { onClick(); true } else disabled()
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = null, enabled = visible, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(PlayerMetrics.jumpHeight)
                .background(colors.s1, FocalShapes.pill)
                .padding(start = PlayerMetrics.jumpStart, end = PlayerMetrics.jumpEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(if (up) FocalIcon.ArrowUp else FocalIcon.ArrowDown, colors.ink, size = GlyphSize.small)
            Spacer(Modifier.width(PlayerMetrics.jumpGlyphGap))
            Text(description, style = FocalType.action.copy(fontWeight = FocalWeight.Medium), color = colors.ink, maxLines = 1)
        }
    }
}
