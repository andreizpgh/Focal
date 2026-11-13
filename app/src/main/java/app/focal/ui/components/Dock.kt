package app.focal.ui.components

import android.graphics.Paint
import android.os.Build
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.focal.R
import app.focal.ui.design.FocalClock
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.Scene
import app.focal.ui.design.TriggerClock
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.design.smoothstep
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the dock holds (spec 4.3). The geometry is the bottom column's; only contents, widths and dress change. */
@Immutable
sealed interface DockState {
    /** Record alone, x 24-388: there is nothing to search yet. */
    data object LibraryEmpty : DockState
    /** Search circle + Record. */
    data object LibraryIdle : DockState
    /** While a capture runs (library and scene): Pause + Finish, or Resume pill + Finish while paused. */
    data class Live(val paused: Boolean) : DockState
    /**
     * A note: Play or Pause playback (or nothing for text only) + the progress pill or Copy. With [append], a second
     * circle between them continues the recording into this note (● "Continue recording"). [noteId] says whose dock
     * it is: what the pill remembers (its fill, having been ready, "Copied") belongs to that note only.
     */
    data class Note(val circle: PlayCircle, val pill: NotePill, val append: Boolean = false, val noteId: String? = null) : DockState
    /** Notes are selected in the library: ✕ "Done selecting" + "Delete N". */
    data class Selecting(val count: Int) : DockState
}

enum class PlayCircle { Play, PausePlayback, Unavailable, None }

enum class ProgressPhase { Transcribing, Waiting, Preparing }

@Immutable
sealed interface NotePill {
    /** [fraction] is resolved audio over total audio; Waiting and Preparing keep a static fill. */
    data class Progress(val phase: ProgressPhase, val fraction: Float) : NotePill
    data class Copy(val enabled: Boolean = true) : NotePill
}

/**
 * What the dock's controls do. [copy] performs the copy and returns whether `setPrimaryClip` succeeded; the pill
 * then confirms (haptic, check, "Copied"). [finish] fires its own `Confirm` once the service accepts the command
 * (spec 2.9), so the dock adds none. [openSettings] adds the accessibility action on Record and Finish in the
 * library (spec 4.23.6).
 */
@Stable
class DockActions(
    val search: () -> Unit = {},
    val record: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val finish: () -> Unit = {},
    val play: () -> Unit = {},
    val pausePlayback: () -> Unit = {},
    val copy: suspend () -> Boolean = { false },
    val openSettings: (() -> Unit)? = null,
    /** ● on a note: the recording continues into it. */
    val append: () -> Unit = {},
    /** ✕ while notes are selected: the selection ends. */
    val closeSelection: () -> Unit = {},
    /** "Delete N": the selected notes go to the trash (with Undo). */
    val deleteSelected: () -> Unit = {},
)

/**
 * What a keyboard asks of the dock (spec 8.5): Ctrl+C runs the Copy pill's own tap, so it copies and confirms (check,
 * "Copied", `Confirm`) exactly as a tap does, and does nothing where a tap would do nothing.
 */
@Stable
class DockRequests {
    internal var copies by mutableIntStateOf(0)
        private set

    fun copy() {
        copies++
    }
}

/** The dock's two dresses: on paper, or on the recording material (spec 4.7). */
enum class Dress { Paper, Material }

/** Where the recording material is, for the dock's dress (window pixels): inside it the material dress, outside paper. */
object MaterialBounds {
    val Everywhere = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    val Nowhere = Rect.Zero

    /** Everything above the window y [edge]: the material's bottom edge passing over a bottom dock. */
    fun above(edge: Float) = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, edge)
}

/** Dock geometry (spec 4.3, 4.7). */
object DockMetrics {
    val height = FocalGrid.x56
    val circle = FocalGrid.x56
    val gap = FocalGrid.x12
    /** The labelled Resume pill that the circle widens into while paused (x 24-172). */
    val resume = 148.dp
    /** The landscape rail (spec 5.14): the circle above the primary circle, centred in this box. */
    val railHeight = circle * 2 + gap
    /** Glyph-only controls in the rail: the record dot and the finish square in a circle (spec 2.7). */
    val railDot = FocalGrid.x12
    val railSquare = GlyphSize.finishSquare
    /**
     * Paused, the rail's circle still widens into a labelled Resume (R1, spec 15: a bare ● read as Record): the dot
     * above "Resume", at least this wide (the 96 dp rail less 8 dp each side) and 8 dp inside the label.
     */
    val railResume = 80.dp
    val railResumePadding = FocalGrid.x8
    val railResumeGap = FocalGrid.x4
    val glyphGap = FocalGrid.x8
    /** The record dot of a note's "Continue recording" circle: the rail's size, a glyph on its own. */
    val appendDot = FocalGrid.x12
    /** The material's Finish pill: shadow 0 8 28 (CSS blur, so sigma 14) and a 1 dp ring. */
    val actionShadowY = FocalGrid.x8
    val actionShadowSigma = 14.dp
    val ring = 1.dp
}

private enum class CircleFace { None, Search, Pause, Resume, Play, PausePlayback, PlayUnavailable, Close, Append }

private enum class PillFace { Record, Finish, NotePrimary, Delete }

private fun circleFace(state: DockState) = when (state) {
    DockState.LibraryEmpty -> CircleFace.None
    DockState.LibraryIdle -> CircleFace.Search
    is DockState.Live -> if (state.paused) CircleFace.Resume else CircleFace.Pause
    is DockState.Note -> when (state.circle) {
        PlayCircle.Play -> CircleFace.Play
        PlayCircle.PausePlayback -> CircleFace.PausePlayback
        PlayCircle.Unavailable -> CircleFace.PlayUnavailable
        PlayCircle.None -> CircleFace.None
    }
    is DockState.Selecting -> CircleFace.Close
}

/** The second circle, between the first and the pill: ● "Continue recording" on a note that can take more. */
private fun secondFace(state: DockState) = if (state is DockState.Note && state.append) CircleFace.Append else CircleFace.None

private fun pillFace(state: DockState) = when (state) {
    DockState.LibraryEmpty, DockState.LibraryIdle -> PillFace.Record
    is DockState.Live -> PillFace.Finish
    is DockState.Note -> PillFace.NotePrimary
    is DockState.Selecting -> PillFace.Delete
}

/** Every dock morph follows a tap (Search, Record, Pause, Finish, a page change): it counts from it. */
@OptIn(ExperimentalSharedTransitionApi::class)
private val MoveBounds = BoundsTransform { _, _ -> FocalMotion.fromTrigger(FocalMotion.move()) }

/**
 * The dock (spec 4.3): a 56 dp circle and a 56 dp pill on the bottom column, with a second circle between them on a
 * note that can go on recording. Bounds animate on `move`
 * (`animateBounds` in a `LookaheadScope`); glyphs and labels fade through; containers that change crossfade without
 * a coverage dip. Both slots swap on one q, so the circle and the pill are always at the same phase; a change that a
 * tap announced counts from the tap ([clock], started in its handler), even when the new contents arrive a frame or
 * two later. While a capture runs, [material] (the recording material's bounds in the window, read in the draw
 * phase) wipes each control from its paper dress to its material dress (spec 2.8.6), so a drag never recomposes the
 * dock. Touch and semantics belong to the control, never to a dress.
 *
 * In a short window it is a [rail] (spec 5.14): the circle above the primary control, which is a 56 dp circle with
 * its glyph only (the label is its content description, and a long press shows it), centred in a fixed box so that a
 * circle growing in moves the primary one on `move`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Dock(
    state: DockState,
    actions: DockActions,
    modifier: Modifier = Modifier,
    material: (() -> Rect)? = null,
    clock: TriggerClock? = null,
    hidden: Boolean = false,
    requests: DockRequests? = null,
    rail: Boolean = false,
) {
    val circle = circleFace(state)
    val pill = pillFace(state)
    // The rail holds two controls only (spec 5.14): there the note's menu continues the recording.
    val second = if (rail) CircleFace.None else secondFace(state)
    val selected = (state as? DockState.Selecting)?.count ?: 0
    val swap = rememberDockSwap(DockFaces(circle, pill, second), clock)
    val leaving = swap.leaving()
    val circleWidth = when (circle) {
        CircleFace.None -> 0.dp
        CircleFace.Resume -> DockMetrics.resume
        else -> DockMetrics.circle
    }
    val edge = if (state is DockState.Live) material else null
    if (rail) {
        // The rail's box is as wide as the labelled Resume at every moment, so the circle widening into it (and back)
        // never moves the box: both controls stay centred on the rail's axis.
        val resumeWidth = railResumeWidth()
        LookaheadScope {
            Column(
                modifier.size(resumeWidth, DockMetrics.railHeight),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val circleSize = if (circle == CircleFace.None) 0.dp else DockMetrics.circle
                val railCircleWidth = if (circle == CircleFace.Resume) resumeWidth else DockMetrics.circle
                CircleSlot(
                    face = circle, leaving = leaving?.circle, q = swap::q, actions = actions, edge = edge, rail = true,
                    modifier = Modifier.animateBounds(this@LookaheadScope, boundsTransform = MoveBounds).size(railCircleWidth, circleSize),
                )
                Spacer(Modifier.height(if (circleSize > 0.dp) DockMetrics.gap else 0.dp))
                PillSlot(
                    face = pill, leaving = leaving?.pill, q = swap::q, notePill = (state as? DockState.Note)?.pill,
                    noteId = (state as? DockState.Note)?.noteId, actions = actions,
                    edge = edge, hidden = hidden, requests = requests, rail = true, selected = selected,
                    modifier = Modifier.animateBounds(this@LookaheadScope, boundsTransform = MoveBounds).size(DockMetrics.circle),
                )
            }
        }
        return
    }
    LookaheadScope {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            CircleSlot(
                face = circle,
                leaving = leaving?.circle,
                q = swap::q,
                actions = actions,
                edge = edge,
                modifier = Modifier.animateBounds(this@LookaheadScope, boundsTransform = MoveBounds)
                    .width(circleWidth).height(DockMetrics.height),
            )
            Spacer(Modifier.width(if (circleWidth > 0.dp) DockMetrics.gap else 0.dp))
            // The second circle grows in between them on `move`, and the pill gives it the room.
            val secondWidth = if (second == CircleFace.None) 0.dp else DockMetrics.circle
            CircleSlot(
                face = second,
                leaving = leaving?.second,
                q = swap::q,
                actions = actions,
                edge = null,
                modifier = Modifier.animateBounds(this@LookaheadScope, boundsTransform = MoveBounds)
                    .width(secondWidth).height(DockMetrics.height),
            )
            Spacer(Modifier.width(if (secondWidth > 0.dp) DockMetrics.gap else 0.dp))
            PillSlot(
                face = pill,
                leaving = leaving?.pill,
                q = swap::q,
                notePill = (state as? DockState.Note)?.pill,
                noteId = (state as? DockState.Note)?.noteId,
                actions = actions,
                edge = edge,
                hidden = hidden,
                requests = requests,
                selected = selected,
                modifier = Modifier.animateBounds(this@LookaheadScope, boundsTransform = MoveBounds)
                    .weight(1f).heightIn(min = DockMetrics.height),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircleSlot(face: CircleFace, leaving: CircleFace?, q: () -> Float, actions: DockActions, edge: (() -> Rect)?, modifier: Modifier, rail: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val description = when (face) {
        CircleFace.None -> null
        CircleFace.Search -> stringResource(R.string.cd_search_open)
        CircleFace.Pause -> stringResource(R.string.action_pause)
        CircleFace.Resume -> stringResource(R.string.action_resume)
        CircleFace.Play -> stringResource(R.string.action_play)
        CircleFace.PausePlayback -> stringResource(R.string.action_pause_playback)
        CircleFace.PlayUnavailable -> stringResource(R.string.a11y_play_unavailable)
        CircleFace.Close -> stringResource(R.string.cd_selection_close)
        CircleFace.Append -> stringResource(R.string.action_append)
    }
    val onClick: (() -> Unit)? = when (face) {
        CircleFace.Close -> actions.closeSelection
        CircleFace.Append -> actions.append
        CircleFace.Search -> actions.search
        CircleFace.Pause -> actions.pause
        CircleFace.Resume -> actions.resume
        CircleFace.Play -> actions.play
        CircleFace.PausePlayback -> actions.pausePlayback
        CircleFace.None, CircleFace.PlayUnavailable -> null
    }
    val control = if (description == null) modifier else modifier
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            // Cleared semantics drop the click of `clickable` below, so the action is stated here.
            if (onClick == null) disabled() else this.onClick { onClick(); true }
        }
        .pressScale(source)
        .focusRing(FocalShapes.pill)
        .tapOrLabel(source, onClick, rail) { scope.launch { tooltip.show() } }
    RailTooltip(description.takeIf { rail }, tooltip) {
        DressWipe(edge, control) { dress ->
            SlotSwap(face, leaving, q) { shown, containerAlpha, contentAlpha ->
                if (shown != CircleFace.None) {
                    TonalContainer(dress, containerAlpha)
                    // Unbounded: "● Resume" keeps its width (never wraps) while the circle widens under it.
                    Box(
                        Modifier.wrapContentWidth(unbounded = true).heightIn(min = if (rail) 0.dp else DockMetrics.height).graphicsLayer { alpha = contentAlpha() },
                        contentAlignment = Alignment.Center,
                    ) { CircleContent(shown, dress, rail) }
                }
            }
        }
    }
}

/**
 * A control's tap; in the rail also a long press that shows its label (spec 5.14), disabled controls included (the
 * progress circle says "Transcribing" only that way).
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tapOrLabel(source: MutableInteractionSource, onClick: (() -> Unit)?, rail: Boolean, showLabel: () -> Unit): Modifier = when {
    !rail -> clickable(source, indication = null, enabled = onClick != null, onClick = { onClick?.invoke() })
    onClick != null -> combinedClickable(source, indication = null, onLongClick = showLabel, onClick = onClick)
    else -> pointerInput(showLabel) { detectTapGestures(onLongPress = { showLabel() }) }
}

/** In the rail, a plain tooltip with the control's label on its start side, shown by a long press or a hover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailTooltip(label: String?, state: TooltipState, content: @Composable () -> Unit) {
    if (label == null) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Start),
        tooltip = { PlainTooltip { Text(label, style = FocalType.meta) } },
        state = state,
        enableUserInput = false,
        content = content,
    )
}

/** The rail's labelled Resume: [DockMetrics.railResume], or wider when "Resume" needs it (a large font). */
@Composable
private fun railResumeWidth(): Dp {
    val label = stringResource(R.string.action_resume)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(label, density) {
        val text = with(density) { measurer.measure(label, FocalType.label, maxLines = 1).size.width.toDp() }
        maxOf(DockMetrics.railResume, text + DockMetrics.railResumePadding * 2)
    }
}

@Composable
private fun CircleContent(face: CircleFace, dress: Dress, rail: Boolean) {
    val colors = FocalTheme.colors
    val ink = if (dress == Dress.Material) Scene.ink else colors.ink
    if (rail && face == CircleFace.Resume) {
        // A rail item's stack: the pill's ● above its word, centred (R1 kept in the rail's width).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RecordDot(ink)
            Spacer(Modifier.height(DockMetrics.railResumeGap))
            Text(stringResource(R.string.action_resume), style = FocalType.label, color = ink, maxLines = 1, softWrap = false)
        }
        return
    }
    when (face) {
        CircleFace.Search -> Glyph(FocalIcon.Search, ink)
        CircleFace.Pause -> Glyph(FocalIcon.Pause, ink)
        CircleFace.Play -> Glyph(FocalIcon.Play, ink)
        CircleFace.PausePlayback -> Glyph(FocalIcon.Pause, ink)
        CircleFace.PlayUnavailable -> Glyph(FocalIcon.Play, colors.faint)
        CircleFace.Close -> Glyph(FocalIcon.Close, ink)
        // The dot Record and Resume carry: on a note it records more of the same note.
        CircleFace.Append -> RecordDot(ink, size = DockMetrics.appendDot)
        CircleFace.Resume -> LabelRow(stringResource(R.string.action_resume), ink) { RecordDot(ink) }
        CircleFace.None -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PillSlot(
    face: PillFace,
    leaving: PillFace?,
    q: () -> Float,
    notePill: NotePill?,
    noteId: String?,
    actions: DockActions,
    edge: (() -> Rect)?,
    modifier: Modifier,
    hidden: Boolean,
    requests: DockRequests?,
    rail: Boolean = false,
    selected: Int = 0,
) {
    val source = remember { MutableInteractionSource() }
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val haptics = rememberFocalHaptics()
    var copied by remember(noteId) { mutableStateOf(false) }
    // The count the Delete pill last showed: it keeps its number while it leaves, after the selection has emptied.
    val shownCount = remember { mutableIntStateOf(selected) }
    if (selected > 0) SideEffect { shownCount.intValue = selected }
    val deleteCount = maxOf(1, if (selected > 0) selected else shownCount.intValue)
    val deleteLabel = pluralStringResource(R.plurals.action_delete_notes, deleteCount, deleteCount)
    val copyEnabled = (notePill as? NotePill.Copy)?.enabled == true
    val settingsLabel = stringResource(R.string.a11y_open_settings)
    val copyLabel = stringResource(R.string.action_copy)
    val copiedLabel = stringResource(R.string.action_copied)
    val progress = notePill as? NotePill.Progress
    val progressDescription = progress?.let { progressDescription(it) }
    val description = when (face) {
        PillFace.Record -> stringResource(R.string.action_record)
        PillFace.Finish -> stringResource(R.string.action_finish)
        PillFace.NotePrimary -> progressDescription ?: if (copied) copiedLabel else copyLabel
        PillFace.Delete -> deleteLabel
    }
    val onClick: (() -> Unit)? = when (face) {
        PillFace.Record -> actions.record
        PillFace.Finish -> actions.finish
        PillFace.Delete -> actions.deleteSelected.takeIf { selected > 0 }
        PillFace.NotePrimary -> if (copyEnabled && !copied) {
            {
                scope.launch {
                    if (actions.copy()) {
                        haptics.copy()
                        copied = true
                        delay(FocalTiming.COPIED_HOLD_MS)
                        copied = false
                    }
                }
            }
        } else null
    }
    // A keyboard's Copy is this pill's tap (only while it is the note's Copy).
    val tap by rememberUpdatedState(onClick.takeIf { face == PillFace.NotePrimary })
    if (requests != null) LaunchedEffect(requests) {
        var seen = requests.copies
        snapshotFlow { requests.copies }.collect { count ->
            if (count != seen) {
                seen = count
                tap?.invoke()
            }
        }
    }
    val control = modifier
        .clearAndSetSemantics {
            contentDescription = description
            if (progress != null) {
                progressBarRangeInfo = ProgressBarRangeInfo(announcedStep(progress.fraction), 0f..1f)
            } else {
                role = Role.Button
            }
            // The progress pill is not a button (spec 8.2: disabled, with its range); Copied is a moment, not a state.
            if (onClick == null && !copied) disabled()
            if (onClick != null) this.onClick { onClick(); true }
            if (face == PillFace.NotePrimary && progress == null) liveRegion = LiveRegionMode.Polite
            val openSettings = actions.openSettings
            if (openSettings != null && face != PillFace.NotePrimary) {
                customActions = listOf(CustomAccessibilityAction(settingsLabel) { openSettings(); true })
            }
        }
        .pressScale(source)
        .focusRing(FocalShapes.pill)
        .tapOrLabel(source, onClick, rail) { scope.launch { tooltip.show() } }
    RailTooltip(description.takeIf { rail }, tooltip) {
        DressWipe(edge, control) { dress ->
            SlotSwap(face, leaving, q) { shown, containerAlpha, contentAlpha ->
                when (shown) {
                    PillFace.Record -> {
                        PrimaryContainer(dress, containerAlpha)
                        PillContent(dress, contentAlpha) { ink ->
                            if (rail) RecordDot(ink, size = DockMetrics.railDot)
                            else LabelRow(stringResource(R.string.action_record), ink) { RecordDot(ink) }
                        }
                    }
                    PillFace.Finish -> {
                        PrimaryContainer(dress, containerAlpha)
                        PillContent(dress, contentAlpha) { ink ->
                            if (rail) FinishSquare(ink, size = DockMetrics.railSquare)
                            else LabelRow(stringResource(R.string.action_finish), ink) { FinishSquare(ink) }
                        }
                    }
                    // Keyed by its note: another note's pill starts from that note's state, with no readiness tick
                    // and no fill left over from the one before (two panes switch notes under one dock).
                    PillFace.NotePrimary -> key(noteId) {
                        NotePrimaryPill(notePill ?: NotePill.Copy(), copied, containerAlpha, contentAlpha, hidden, rail)
                    }
                    PillFace.Delete -> {
                        PrimaryContainer(dress, containerAlpha)
                        PillContent(dress, contentAlpha) { ink ->
                            if (rail) Glyph(FocalIcon.Delete, ink)
                            else LabelRow(deleteLabel, ink) { Glyph(FocalIcon.Delete, ink, size = GlyphSize.inPill) }
                        }
                    }
                }
            }
        }
    }
}

/** The progress pill is announced at 25 % steps only (spec 4.5.3). */
private fun announcedStep(fraction: Float): Float = (fraction.coerceIn(0f, 1f) * ANNOUNCE_STEPS).toInt() / ANNOUNCE_STEPS
private const val ANNOUNCE_STEPS = 4f
private const val PERCENT = 100

@Composable
private fun progressDescription(progress: NotePill.Progress): String = when (progress.phase) {
    ProgressPhase.Transcribing -> (announcedStep(progress.fraction) * PERCENT).roundToInt().let { pluralStringResource(R.plurals.a11y_transcribing, it, it) }
    ProgressPhase.Waiting -> stringResource(R.string.stage_waiting)
    ProgressPhase.Preparing -> stringResource(R.string.stage_preparing)
}

@Composable
private fun PillContent(dress: Dress, alpha: () -> Float, content: @Composable (Color) -> Unit) {
    val ink = if (dress == Dress.Material) Scene.actionLabel else FocalTheme.colors.onInv
    // Sized by its content, so a label that wraps at a large font scale grows the pill (min 56 dp).
    Box(Modifier.fillMaxWidth().heightIn(min = DockMetrics.height).graphicsLayer { this.alpha = alpha() }, contentAlignment = Alignment.Center) {
        content(ink)
    }
}

/** A centred glyph + 8 dp + `label`: the 56 dp pills' content (spec 4.5.1). Wraps at large font scales. */
@Composable
private fun LabelRow(text: String, color: Color, glyph: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = FocalGrid.x16), verticalAlignment = Alignment.CenterVertically) {
        glyph()
        Spacer(Modifier.width(DockMetrics.glyphGap))
        Text(text, style = FocalType.label, color = color, textAlign = TextAlign.Center)
    }
}

/**
 * The Note dock's pill (spec 4.5.3, T8): the progress pill and Copy are one control, so readiness is one morph. The
 * fill moves on `move` at each commit and never backwards; at Ready it reaches 100 %, the background goes `s1` to
 * `inv` on `fade` and the label fades through to the copy glyph + "Copy"; `Confirm` fires on that first frame.
 * Copy confirms with the check and "Copied" for 1600 ms.
 */
@Composable
private fun BoxScope.NotePrimaryPill(pill: NotePill, copied: Boolean, containerAlpha: () -> Float, contentAlpha: () -> Float, hidden: Boolean, rail: Boolean) {
    val colors = FocalTheme.colors
    val haptics = rememberFocalHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val target = when (pill) {
        is NotePill.Progress -> pill.fraction.coerceIn(0f, 1f)
        is NotePill.Copy -> 1f
    }
    val ready = pill is NotePill.Copy
    val fill = remember { Animatable(target) }
    val inverted = remember { Animatable(if (ready) 1f else 0f) }
    val copyShown = remember { Animatable(if (ready) 1f else 0f) }
    var wasReady by remember { mutableStateOf(ready) }
    // Forward it moves; backward (a retry starts over what was full) it is simply the new value.
    LaunchedEffect(target) { if (target > fill.value) fill.animateTo(target, FocalMotion.move()) else fill.snapTo(target) }
    LaunchedEffect(ready) {
        // Felt only with the dock in sight: not while Edit or Find has it down behind the keyboard.
        if (ready && !wasReady && !hidden && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) haptics.ready()
        wasReady = ready
        launch { inverted.animateTo(if (ready) 1f else 0f, FocalMotion.fade()) }
        copyShown.animateTo(if (ready) 1f else 0f, FocalMotion.fade())
    }
    val enabled = pill !is NotePill.Copy || pill.enabled
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Container: the `s1` track, the `inv` fill from the start edge (its leading end straight), clipped to the pill.
    // In the rail's circle the fill rises from the bottom, like a level.
    Box(
        Modifier.matchParentSize().drawWithCache {
            val outline = FocalShapes.pill.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            onDrawBehind {
                val a = containerAlpha()
                val base = if (enabled) lerp(colors.s1, colors.inv, inverted.value) else colors.s1
                drawOutline(outline, base, alpha = a)
                if (enabled) clipPath(clip) {
                    if (rail) {
                        val height = size.height * fill.value
                        drawRect(colors.inv, topLeft = Offset(0f, size.height - height), size = Size(size.width, height), alpha = a)
                    } else {
                        val width = size.width * fill.value
                        val left = if (rtl) size.width - width else 0f
                        drawRect(colors.inv, topLeft = Offset(left, 0f), size = Size(width, size.height), alpha = a)
                    }
                }
            }
        },
    )
    if (rail) {
        // The rail's circle has room for the glyph only: the progress is its level, Copy its glyph.
        val ink = if (enabled) colors.onInv else colors.faint
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = contentAlpha() * smoothstep(0.5f, 1f, copyShown.value) },
            contentAlignment = Alignment.Center,
        ) { CopyGlyph(copied, ink) }
        return
    }
    val label = when (pill) {
        is NotePill.Progress -> when (pill.phase) {
            ProgressPhase.Transcribing -> stringResource(R.string.action_transcribing)
            ProgressPhase.Waiting -> stringResource(R.string.stage_waiting)
            ProgressPhase.Preparing -> stringResource(R.string.stage_preparing)
        }
        is NotePill.Copy -> stringResource(R.string.action_transcribing)
    }
    // The progress label twice: ink underneath, onInv on top clipped to the fill, so it reads at every percentage.
    Box(
        Modifier.fillMaxWidth().heightIn(min = DockMetrics.height)
            .graphicsLayer { alpha = contentAlpha() * (1 - smoothstep(0f, 0.5f, copyShown.value)) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = FocalType.label, color = colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = FocalGrid.x16),
        )
        Box(
            Modifier.matchParentSize().drawWithContent {
                val width = size.width * fill.value
                clipRect(left = if (rtl) size.width - width else 0f, right = if (rtl) size.width else width) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
            label, style = FocalType.label, color = colors.onInv, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = FocalGrid.x16),
        )
        }
    }
    val ink = if (enabled) colors.onInv else colors.faint
    Box(
        Modifier.matchParentSize().graphicsLayer { alpha = contentAlpha() * smoothstep(0.5f, 1f, copyShown.value) },
        contentAlignment = Alignment.Center,
    ) { CopyContent(copied, ink) }
}

/** Copy and Copied in the rail's circle: the copy glyph turning into the check (T9 without its labels). */
@Composable
private fun CopyGlyph(copied: Boolean, ink: Color) {
    val check = remember { Animatable(if (copied) 1f else 0f) }
    val checkScale = remember { Animatable(1f) }
    LaunchedEffect(copied) {
        if (copied) launch { checkScale.snapTo(CHECK_START_SCALE); checkScale.animateTo(1f, FocalMotion.press()) }
        check.animateTo(if (copied) 1f else 0f, FocalMotion.fadeFast())
    }
    Box(contentAlignment = Alignment.Center) {
        Glyph(FocalIcon.Copy, ink, modifier = Modifier.graphicsLayer { alpha = 1 - check.value })
        Glyph(FocalIcon.Check, ink, modifier = Modifier.graphicsLayer { alpha = check.value; scaleX = checkScale.value; scaleY = checkScale.value })
    }
}

private const val CHECK_START_SCALE = 0.7f

/** Copy and Copied in the dock's pill (spec 4.5.1): `label`, a 20 dp glyph and an 8 dp gap, centred on "Copy". */
@Composable
private fun CopyContent(copied: Boolean, ink: Color) {
    CopyLabel(copied, ink, FocalType.label, GlyphSize.inPill, DockMetrics.glyphGap, Modifier.padding(horizontal = FocalGrid.x16))
}

/** What the dock's slots show: the circle, the pill and the second circle between them. */
@Immutable
private data class DockFaces(val circle: CircleFace, val pill: PillFace, val second: CircleFace = CircleFace.None)

/**
 * One fade-through for the whole dock (spec 4.3, Q7): the faces that leave and the faces that arrive, on one q
 * (`fadeFast`). It counts from the tap that changed the dock when [clock] was started for it (claimed in the first
 * frame after the tap); a change no tap announced (a pause from the notification, a page that loaded) runs its own.
 */
@Stable
private class DockSwap(val shown: DockFaces, private val from: DockFaces?, private val clock: TriggerClock?, private val serial: Int) {
    val own = Animatable(if (from == null || clock != null) 1f else 0f)
    val runsOwn: Boolean get() = from != null && clock == null
    val claimed: Int? get() = if (clock != null) serial else null

    fun q(): Float = when {
        from == null -> 1f
        clock != null -> if (clock.serial == serial) FocalClock.fadeFast(clock.elapsed) else 1f
        else -> own.value
    }

    /** The faces still leaving, read in composition (a derived state: it changes once, when the swap ends). */
    val settled = derivedStateOf { q() >= 1f }
    fun leaving(): DockFaces? = if (settled.value) null else from

    /** What leaves if the dock changes again now: a swap caught before its midpoint keeps what was leaving. */
    fun outgoing(): DockFaces = if (from != null && q() < HALF) from else shown
}

private class DockSwapMemory(var swap: DockSwap, var claimed: Int = 0)

@Composable
private fun rememberDockSwap(faces: DockFaces, clock: TriggerClock?): DockSwap {
    val memory = remember { DockSwapMemory(DockSwap(faces, null, null, 0)) }
    val swap = remember(faces) {
        val last = memory.swap
        if (last.shown == faces) last else Snapshot.withoutReadObservation {
            val from = last.outgoing().takeIf { it != faces }
            val fresh = clock?.takeIf { it.elapsed <= FocalTiming.TRIGGER_LEAD_MS && it.serial != memory.claimed }
            DockSwap(faces, from, fresh, fresh?.serial ?: 0)
        }
    }
    SideEffect {
        memory.swap = swap
        swap.claimed?.let { memory.claimed = it }
    }
    if (swap.runsOwn) LaunchedEffect(swap) { swap.own.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fadeFast())) }
    return swap
}

/**
 * Draws the slot's face during a swap (spec 4.3, Q7): the incoming container beneath at full alpha and the outgoing
 * one on top at 1 - q, so the coverage never dips; contents fade through, the outgoing at 1 - smoothstep(0, 0.5, q)
 * and the incoming at smoothstep(0.5, 1, q).
 */
@Composable
private fun <F : Any> SlotSwap(shown: F, leaving: F?, q: () -> Float, face: @Composable BoxScope.(F, containerAlpha: () -> Float, contentAlpha: () -> Float) -> Unit) {
    val outgoing = leaving?.takeIf { it != shown }
    Box(Modifier.fillMaxWidth().heightIn(min = DockMetrics.height), contentAlignment = Alignment.Center) {
        key(shown) { face(shown, { 1f }, { if (outgoing == null) 1f else smoothstep(HALF, 1f, q()) }) }
        outgoing?.let { old -> key(old) { face(old, { 1 - q() }, { 1 - smoothstep(0f, HALF, q()) }) } }
    }
}

private const val HALF = 0.5f

/** Search, Pause, Play circles and the Resume pill: `s1` on paper, glass on the material. */
@Composable
private fun BoxScope.TonalContainer(dress: Dress, alpha: () -> Float) {
    val colors = FocalTheme.colors
    Box(
        Modifier.matchParentSize().drawWithCache {
            val outline = FocalShapes.pill.createOutline(size, layoutDirection, this)
            val highlight = glassHighlight(outline, DockMetrics.ring.toPx())
            onDrawBehind {
                val a = alpha()
                if (dress == Dress.Paper) drawOutline(outline, colors.s1, alpha = a) else {
                    drawOutline(outline, Scene.glass, alpha = a)
                    drawPath(highlight, Scene.glassHighlight, alpha = a)
                }
            }
        },
    )
}

/** Record, Finish and Copy: `inv` on paper; the material's white gradient with ring and shadow (spec 2.2). */
@Composable
private fun BoxScope.PrimaryContainer(dress: Dress, alpha: () -> Float) {
    val colors = FocalTheme.colors
    Box(
        Modifier.matchParentSize().drawWithCache {
            val outline = FocalShapes.pill.createOutline(size, layoutDirection, this)
            val gradient = Brush.verticalGradient(listOf(Scene.actionTop, Scene.actionBottom))
            val ring = DockMetrics.ring.toPx()
            val shadow = actionShadowPaint(DockMetrics.actionShadowY.toPx(), DockMetrics.actionShadowSigma.toPx(), Scene.actionShadow)
            val radius = size.height / 2
            onDrawBehind {
                val a = alpha()
                if (dress == Dress.Paper) drawOutline(outline, colors.inv, alpha = a) else {
                    if (shadow != null && a > 0f) {
                        shadow.alpha = (Scene.actionShadow.alpha * a * 255).roundToInt()
                        drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, shadow) }
                    }
                    drawRoundRect(
                        Scene.actionRing, topLeft = Offset(-ring, -ring),
                        size = Size(size.width + 2 * ring, size.height + 2 * ring),
                        cornerRadius = CornerRadius(radius + ring), alpha = a,
                    )
                    drawOutline(outline, gradient, alpha = a)
                }
            }
        },
    )
}

/**
 * A soft drop shadow drawn with the platform's shadow layer (API 28+ draws it on hardware canvases for any shape).
 * Below API 28 the pill has no shadow: it is decorative (spec 9).
 */
internal fun actionShadowPaint(offsetY: Float, sigma: Float, color: Color): Paint? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.TRANSPARENT
        setShadowLayer(blurRadius(sigma), 0f, offsetY, color.copy(alpha = 1f).toArgb())
    }
}

/** The 1 dp inner top highlight on glass: the shape minus itself moved down by [width]. */
internal fun glassHighlight(outline: Outline, width: Float): Path {
    val shape = Path().apply { addOutline(outline) }
    val shifted = Path().apply { addOutline(outline); translate(Offset(0f, width)) }
    return Path().apply { op(shape, shifted, PathOperation.Difference) }
}

/** Where the slot sits in the window, for the dress wipe. */
private class SlotPlacement { var coordinates: LayoutCoordinates? = null }

/**
 * Draws [content] in both dresses split at the material's bounds (window pixels from [material]): paper outside them,
 * the material inside them, glyphs and labels included (`dockDress`, spec 2.8.6). On a bottom dock that is its bottom
 * edge passing over the row; on the landscape rail the clip's end edge and bottom edge both cross it. Without bounds
 * only the paper dress exists. The slot and its paper dress keep their place in the composition whether or not
 * bounds exist, so the slot's bounds animation and the paper content's fade-through carry on when the material
 * arrives behind the dock (T1′e: Pause grows from 0 while Record's left edge moves 24 → 92, as the scene registers
 * its bounds).
 */
@Composable
private fun DressWipe(material: (() -> Rect)?, modifier: Modifier, content: @Composable BoxScope.(Dress) -> Unit) {
    val placement = remember { SlotPlacement() }
    // The bounds in the slot's own pixels, kept within reach of it (the pill's shadow and ring draw outside it).
    fun DrawScope.local(bounds: Rect): Rect {
        val origin = placement.coordinates?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
        val reach = 2 * size.maxDimension
        fun x(v: Float) = (v - origin.x).coerceIn(-reach, reach)
        fun y(v: Float) = (v - origin.y).coerceIn(-reach, reach)
        return Rect(x(bounds.left), y(bounds.top), x(bounds.right), y(bounds.bottom))
    }
    Box(modifier.onPlaced { placement.coordinates = it }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.drawWithContent {
                if (material == null) {
                    drawContent()
                    return@drawWithContent
                }
                val r = local(material())
                if (r.isEmpty) drawContent()
                else clipRect(r.left, r.top, r.right, r.bottom, ClipOp.Difference) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.Center,
        ) { content(Dress.Paper) }
        if (material != null) Box(
            Modifier.matchParentSize().drawWithContent {
                val r = local(material())
                if (!r.isEmpty) clipRect(r.left, r.top, r.right, r.bottom) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.Center,
        ) { content(Dress.Material) }
    }
}
