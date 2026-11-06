package app.focal.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.focal.R
import app.focal.ui.components.CopyLabel
import app.focal.ui.components.ProgressPhase
import app.focal.ui.components.actionShadowPaint
import app.focal.ui.components.copyPillWidth
import app.focal.ui.components.focusRing
import app.focal.ui.components.glassHighlight
import app.focal.ui.components.pressScale
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.Scene
import app.focal.ui.design.Stage
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.design.smoothstep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The stage's one pill (spec 4.22.4): the progress pill while a note becomes text, then Copy. */
@Immutable
sealed interface StagePillUi {
    /** [fraction] is resolved over total audio; Waiting and Preparing have no track. */
    data class Progress(val phase: ProgressPhase, val fraction: Float) : StagePillUi

    /** [fresh]: Ready and not yet copied, the white `stageAction`. [enabled]: there is text to copy. */
    data class Copy(val fresh: Boolean, val enabled: Boolean) : StagePillUi
}

/** Stage pill geometry (spec 4.22.4). */
object StagePillMetrics {
    val height = 48.dp
    val copyWidth = 136.dp
    /** The progress pill is max(136, label + 2 x this) wide; the track is inset as much. */
    val padding = 20.dp
    val glyph = 18.dp
    val glyphGap = 6.dp
    /** The progress label's line box and the track inside the 48 dp pill (measured: 9-29 and 35-37). */
    val labelTop = 9.dp
    val labelTrackGap = 6.dp
    val track = 2.dp
    val trackBottom = 11.dp
    /** Copy's label keeps at least this much above and below it when a large font grows the pill past 48 dp. */
    val copyInsetY = Grid.x12
    /** The white Copy's shadow: 0 6 20 (a CSS blur, so sigma 10). */
    val shadowY = 6.dp
    val shadowSigma = 10.dp
    val ring = 1.dp
    /** A disabled Copy (no speech) draws its label at this alpha. */
    const val DISABLED_ALPHA = 0.4f
    const val PERCENT = 100
    const val ANNOUNCE_STEPS = 4f
}

/**
 * The stage's pill. Progress and Copy are one control, so readiness is one morph (T8″): when the note turns Ready
 * on screen (the activity RESUMED, the first time for this note), the pill narrows to 136 dp with its right edge
 * fixed (`move`), its fill goes from glass to white (`fade`) and its content fades through from "Transcribing ·
 * 100%" and the track to the copy glyph and "Copy" (`fade`), all from the same frame; `Confirm` fires on that frame
 * and [onReadyMoment] starts the card's one shimmer. Off screen, or a second time, the pill simply shows the white
 * Copy. Copy confirms with the check and "Copied" for 1600 ms, then relaxes to glass (the note is no longer fresh).
 */
@Composable
fun StagePill(
    pill: StagePillUi,
    noteId: String,
    onCopy: suspend () -> Boolean,
    onReadyMoment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val haptics = rememberFocalHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val progress = pill as? StagePillUi.Progress
    val copy = pill as? StagePillUi.Copy
    var copied by remember { mutableStateOf(false) }
    val label = progress?.let { progressLabel(it) }
    val labelWidth = label?.let { remember(it, density) { measurer.measure(it, FocalType.action).size.width } } ?: 0
    val copyText = stringResource(R.string.action_copy)
    val copiedText = stringResource(R.string.action_copied)
    // At large font sizes the pill widens to keep "Copied" inside it and grows taller than 48 dp (spec 8.4).
    val copyWidth = remember(copyText, copiedText, density) {
        with(density) {
            maxOf(
                StagePillMetrics.copyWidth.roundToPx(),
                copyPillWidth(
                    measurer.measure(copyText, FocalType.action).size.width, measurer.measure(copiedText, FocalType.action).size.width,
                    StagePillMetrics.glyph.roundToPx(), StagePillMetrics.glyphGap.roundToPx(), StagePillMetrics.padding.roundToPx(),
                ),
            )
        }
    }
    val line = with(density) { FocalType.action.lineHeight.toPx() }
    val trackTop = with(density) { StagePillMetrics.labelTop.toPx() + line + StagePillMetrics.labelTrackGap.toPx() }
    val pillHeight = with(density) {
        maxOf(
            StagePillMetrics.height.toPx(),
            if (progress != null) trackTop + (StagePillMetrics.track + StagePillMetrics.trackBottom).toPx() else line + 2 * StagePillMetrics.copyInsetY.toPx(),
        ).roundToInt()
    }
    val targetWidth = with(density) {
        if (progress == null) copyWidth.toFloat()
        else maxOf(StagePillMetrics.copyWidth.toPx(), labelWidth + 2 * StagePillMetrics.padding.toPx())
    }
    val width = remember { Animatable(targetWidth) }
    val white = remember { Animatable(if (copy?.fresh == true) 1f else 0f) }
    val q = remember { Animatable(if (copy != null) 1f else 0f) }
    val fill = remember { Animatable(progress?.fraction ?: 1f) }
    var lastLabel by remember { mutableStateOf(label) }
    if (label != null) lastLabel = label
    // Ready announces itself to TalkBack (spoken only, X6).
    var announced by remember { mutableStateOf(false) }
    // Both faces are composed only while the morph runs.
    var morphing by remember { mutableStateOf(false) }

    LaunchedEffect(copy != null) {
        if (copy == null) {
            q.snapTo(0f)
            return@LaunchedEffect
        }
        if (q.value >= 1f) return@LaunchedEffect
        val moment = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && FreshNotes.claimMoment(noteId)
        if (moment) {
            haptics.ready()
            announced = true
            if (reduced) {
                fill.snapTo(1f); width.snapTo(targetWidth); white.snapTo(1f); q.snapTo(1f)
                return@LaunchedEffect
            }
            onReadyMoment()
            fill.snapTo(1f)
            morphing = true
            launch { width.animateTo(targetWidth, FocalMotion.move()) }
            launch { white.animateTo(1f, FocalMotion.fade()) }
            q.animateTo(1f, FocalMotion.fade())
            morphing = false
        } else {
            fill.snapTo(1f); width.snapTo(targetWidth); q.snapTo(1f)
        }
    }
    LaunchedEffect(targetWidth) { if (!width.isRunning || q.value >= 1f) width.animateTo(targetWidth, FocalMotion.move()) }
    LaunchedEffect(progress?.fraction) {
        val target = progress?.fraction ?: return@LaunchedEffect
        // The fill moves with each committed window, never backwards.
        if (target > fill.value) fill.animateTo(target.coerceIn(0f, 1f), FocalMotion.move())
    }
    val whiteTarget = copy != null && (copy.fresh || copied)
    LaunchedEffect(whiteTarget) { if (q.value >= 1f) white.animateTo(if (whiteTarget) 1f else 0f, FocalMotion.fade()) }

    val source = remember { MutableInteractionSource() }
    val enabled = copy?.enabled == true
    val copyLabel = stringResource(if (copy?.fresh == true) R.string.a11y_copy_note_new else R.string.a11y_copy_note)
    val copiedLabel = copiedText
    val progressSpoken = progress?.let { progressDescription(it) }
    val readyLabel = stringResource(R.string.a11y_note_ready)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun copyNow() {
        scope.launch {
            if (onCopy()) {
                haptics.copy()
                copied = true
                delay(FocalTiming.COPIED_HOLD_MS)
                copied = false
            }
        }
    }
    Box(
        modifier
            .layout { measurable, _ ->
                val w = width.value.roundToInt()
                val h = pillHeight
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            // Its own node (spec 8.2: the stage, "then its Copy button"), not merged into the card's description.
            .semantics(mergeDescendants = true) { }
            .clearAndSetSemantics {
                if (progress != null) {
                    contentDescription = progressSpoken.orEmpty()
                    progressBarRangeInfo = ProgressBarRangeInfo(announcedStep(progress.fraction), 0f..1f)
                    disabled()
                } else {
                    role = Role.Button
                    contentDescription = if (copied) copiedLabel else copyLabel
                    liveRegion = LiveRegionMode.Polite
                    if (!enabled) disabled() else if (!copied) this.onClick { copyNow(); true }
                }
            }
            .pressScale(source)
            .focusRing(FocalShapes.pill, color = Scene.ink)
            .clickable(source, indication = null, enabled = enabled && !copied, onClick = ::copyNow)
            .drawWithCache {
                val outline = FocalShapes.pill.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                val highlight = glassHighlight(outline, StagePillMetrics.ring.toPx())
                val gradient = Brush.verticalGradient(listOf(Scene.actionTop, Scene.actionBottom))
                val shadow = actionShadowPaint(StagePillMetrics.shadowY.toPx(), StagePillMetrics.shadowSigma.toPx(), Stage.actionShadow)
                val ring = StagePillMetrics.ring.toPx()
                val radius = size.height / 2
                onDrawBehind {
                    val w = white.value
                    if (w < 1f) {
                        drawOutline(outline, Scene.glass)
                        drawPath(highlight, Scene.glassHighlight)
                    }
                    if (w > 0f) {
                        if (shadow != null) {
                            shadow.alpha = (Stage.actionShadow.alpha * w * MAX_ALPHA).roundToInt()
                            drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, shadow) }
                        }
                        drawRoundRect(
                            Scene.actionRing, topLeft = Offset(-ring, -ring), size = Size(size.width + 2 * ring, size.height + 2 * ring),
                            cornerRadius = CornerRadius(radius + ring), alpha = w,
                        )
                        drawPath(path, gradient, alpha = w)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The outgoing progress content: the label and its track, out at 1 - smoothstep(0, 0.5, q).
        val shownLabel = lastLabel
        if (progress != null || morphing) {
            val withTrack = (progress?.phase ?: ProgressPhase.Transcribing) == ProgressPhase.Transcribing
            Box(Modifier.matchParentSize().graphicsLayer { alpha = 1 - smoothstep(0f, HALF, q.value) }) {
                Text(
                    shownLabel.orEmpty(), style = FocalType.action, color = Scene.ink, maxLines = 1,
                    modifier = Modifier.align(if (withTrack) Alignment.TopCenter else Alignment.Center)
                        .padding(top = if (withTrack) StagePillMetrics.labelTop else 0.dp),
                )
                if (withTrack) {
                    Spacer(
                        Modifier.align(Alignment.TopCenter).padding(top = with(density) { trackTop.toDp() })
                            .padding(horizontal = StagePillMetrics.padding).fillMaxWidth().height(StagePillMetrics.track)
                            .drawWithCache {
                                val corner = CornerRadius(size.height / 2)
                                onDrawBehind {
                                    drawRoundRect(Stage.track, cornerRadius = corner)
                                    val filled = size.width * fill.value
                                    drawRoundRect(
                                        Scene.ink, topLeft = Offset(if (rtl) size.width - filled else 0f, 0f),
                                        size = Size(filled, size.height), cornerRadius = corner,
                                    )
                                }
                            },
                    )
                }
            }
        }
        if (copy != null) {
            Box(Modifier.graphicsLayer { alpha = smoothstep(HALF, 1f, q.value) * if (enabled) 1f else StagePillMetrics.DISABLED_ALPHA }) {
                CopyFace(copied, white)
            }
        }
        // A spoken-only "Note ready" for TalkBack at the moment (nothing is drawn).
        Box(
            Modifier.width(1.dp).height(1.dp).semantics(mergeDescendants = true) { }.clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                if (announced) contentDescription = readyLabel
            },
        )
    }
}

private const val HALF = 0.5f
private const val MAX_ALPHA = 255f

/**
 * The copy glyph and "Copy", or the check and "Copied": glyph 18 + 6 + `action` (spec 4.22.4), centred on "Copy".
 * The ink follows the fill: `stageInk` on glass, `#111111` on white (it recomposes only while the fill changes).
 */
@Composable
private fun CopyFace(copied: Boolean, white: Animatable<Float, *>) {
    val color = lerp(Scene.ink, Scene.actionLabel, white.value)
    CopyLabel(copied, color, FocalType.action, StagePillMetrics.glyph, StagePillMetrics.glyphGap)
}


@Composable
private fun progressLabel(progress: StagePillUi.Progress): String = when (progress.phase) {
    ProgressPhase.Transcribing -> stringResource(R.string.stage_transcribing, (progress.fraction.coerceIn(0f, 1f) * StagePillMetrics.PERCENT).toInt())
    ProgressPhase.Waiting -> stringResource(R.string.stage_waiting)
    ProgressPhase.Preparing -> stringResource(R.string.stage_preparing)
}

/** Spoken at 25 % steps only (spec 4.5.3, 8.2). */
@Composable
private fun progressDescription(progress: StagePillUi.Progress): String = when (progress.phase) {
    ProgressPhase.Transcribing -> (announcedStep(progress.fraction) * StagePillMetrics.PERCENT).roundToInt()
        .let { pluralStringResource(R.plurals.a11y_transcribing, it, it) }
    ProgressPhase.Waiting -> stringResource(R.string.stage_waiting)
    ProgressPhase.Preparing -> stringResource(R.string.stage_preparing)
}

private fun announcedStep(fraction: Float): Float =
    (fraction.coerceIn(0f, 1f) * StagePillMetrics.ANNOUNCE_STEPS).toInt() / StagePillMetrics.ANNOUNCE_STEPS
