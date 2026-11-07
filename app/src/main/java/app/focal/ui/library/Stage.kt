package app.focal.ui.library

import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.Animatable as ColorAnimatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.core.Title
import app.focal.recording.SpeechAvailability
import app.focal.ui.components.Mark
import app.focal.ui.components.NineBars
import app.focal.ui.components.PauseBadge
import app.focal.ui.components.PauseBadgeMetrics
import app.focal.ui.components.ProgressPhase
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMeter
import app.focal.ui.components.WaveMode
import app.focal.ui.components.focusRing
import app.focal.ui.components.markBox
import app.focal.ui.components.pressScale
import app.focal.ui.components.sceneMaterial
import app.focal.ui.components.waitingText
import app.focal.ui.design.FocalDisplay
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.Scene
import app.focal.ui.design.Stage
import app.focal.ui.design.alignWith
import app.focal.ui.design.lineScale
import app.focal.ui.scene.StageTranscript
import app.focal.ui.scene.TimerText
import app.focal.ui.scene.TranscriptInks
import app.focal.ui.scene.rememberTimerStyles
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.Origin
import app.focal.ui.shell.pageOrigin
import app.focal.ui.shell.rememberStageOrigin
import app.focal.ui.shell.stageText
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteRow
import app.focal.ui.state.PauseReason
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The stage's geometry (spec 4.22). Card-local: the card is x 24-388 of the window, y 56-296 at rest. */
object StageMetrics {
    val minHeight = 240.dp
    /** In a short window (a phone in landscape, spec 4.22.1) the card is 200 tall and its text shows one line. */
    val shortMinHeight = 200.dp

    fun minHeight(short: Boolean): Dp = if (short) shortMinHeight else minHeight
    fun textLinesMin(short: Boolean): Int = if (short) TEXT_LINES_SHORT else TEXT_LINES_MIN
    /** Every text sits this far inside the card. */
    val inset = FocalGrid.x20
    /** The footer row: 48 dp, 16 dp from the card's bottom and right edges. */
    val footer = FocalGrid.x48
    val footerInset = FocalGrid.x16
    /** The text starts this far under the title and ends at least this far above the footer. */
    val textGap = FocalGrid.x8
    val textClearance = FocalGrid.x20
    /** Between the title and the time on its first line. */
    val timeGap = FocalGrid.x16
    /** When the time + 16 dp takes more than this share of the text column, it drops under the title. */
    const val TIME_SHARE = 0.4f
    /** From this font scale the title may take 3 lines and the length takes its own row (spec 4.22.7). */
    const val LARGE_FONT = 1.5f
    const val TITLE_LINES = 2
    const val TITLE_LINES_LARGE = 3
    const val TEXT_LINES_MIN = 2
    const val TEXT_LINES_SHORT = 1
    const val TEXT_LINES_MAX = 4
    /** The length's unit sits this far after its numerals; a second group this far after the unit (spec 4.22.4). */
    val unitGap = 5.dp
    val groupGap = 10.dp
    /** The length yields to the pill when they would come closer than this. */
    val footerYield = FocalGrid.x12

    // The live state (spec 4.22.3).
    val wave = FocalGrid.x56
    /** The timer starts this far after the wave's box (x 132 = 44 + 72 + 16), as the text region below it. */
    val liveGap = FocalGrid.x16
    /** The live text region fades in over its top this far (`DstIn`): about 1.4 lines, growing with the font scale. */
    val liveMask = 34.dp

    // The readiness shimmer (2.2.1 `stageShimmer`).
    val shimmerWidth = 180.dp
    const val SHIMMER_ANGLE = 18f
    /** The band is masked out of the card's highlight band (F1): nothing above 48 dp, full from 80 dp. */
    val shimmerMaskStart = FocalGrid.x48
    val shimmerMaskEnd = 80.dp
    const val SHIMMER_SHOULDER_AT = 0.25f
}

/** The stage pill's test tag (device tests and the screenshot gallery press it). */
const val STAGE_PILL_TAG = "stage-pill"

/** What the stage can do; the page provides it. */
@Stable
class StageActions(
    val open: (NoteRow) -> Unit,
    val expand: () -> Unit,
    val copy: suspend (NoteRow) -> Boolean,
    val openSettings: () -> Unit,
    /** The row menu at a touch point inside the card. */
    val menu: (NoteRow, IntOffset) -> Unit,
    /** The row menu's items as accessibility actions, offered on a note stage before "Open settings" (spec 12). */
    val menuActions: (NoteRow) -> List<CustomAccessibilityAction> = { emptyList() },
)

/** Where the last press landed, for a long-press menu at the touch point. */
private class PressPoint { var at = Offset.Zero }

/**
 * The stage (spec 4.22): one card in the instrument's material at the top of the library that holds whatever is
 * current: the live capture, or one note (transcribing, waiting, ready). [menu] draws the row menu of the stage's
 * note when it is open; [title] replaces the title while it is renamed in place. [shown] says whether the card is on
 * screen now (the readiness moment plays only there). [liveSeconds] is the recording's time, read only where it is
 * shown and spoken, so the card does not recompose once a second (the model's own when null).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StageCard(
    stage: StageUi,
    dates: LibraryDates,
    today: LocalDate,
    meter: WaveMeter,
    actions: StageActions,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
    title: (@Composable () -> Unit)? = null,
    shown: () -> Boolean = { true },
    liveSeconds: (() -> Long)? = null,
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    // The one shimmer of the readiness moment: u is its linear progress; its layer exists only while it runs.
    val shimmer = remember { Animatable(0f) }
    var shimmering by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val press = remember { PressPoint() }
    val settingsLabel = stringResource(R.string.a11y_open_settings)
    // A live stage's description changes with its time: it is resolved apart, so only that part recomposes.
    val seconds = liveSeconds ?: { (stage as? StageUi.Live)?.elapsedSeconds ?: 0L }
    val spoken = remember { SpokenText() }
    if (stage is StageUi.Live) Speaks(spoken) { stageDescription(stage.copy(elapsedSeconds = seconds()), dates, today) }
    val description = if (stage is StageUi.Live) null else stageDescription(stage, dates, today)
    val note = (stage as? StageUi.Note)?.row
    val clickLabel = stringResource(if (note != null) R.string.a11y_open_note else R.string.a11y_expand_recording)
    val large = LocalDensity.current.fontScale >= StageMetrics.LARGE_FONT
    val origin = rememberStageOrigin(
        note?.id, if (large) StageMetrics.TITLE_LINES_LARGE else StageMetrics.TITLE_LINES,
        titleText = note?.let { noteTitle(it, dates) }.orEmpty(), titleMuted = note?.title?.isBlank() == true,
        // The stage holds a note only while nothing records; live, Finish opens the note it is recording (T7b).
        dock = note?.let { pageDock(it, capturing = false) } ?: FinishedDock,
    )
    Box(
        modifier
            .fillMaxWidth()
            // The page transforms grow out of the card at rest (outside its press scale).
            .pageOrigin(origin)
            .semantics(mergeDescendants = true) {
                contentDescription = description ?: spoken.text
                // While its title is renamed the note's menu is out of reach for touch, and so for TalkBack.
                val menu = if (title == null) note?.let(actions.menuActions).orEmpty() else emptyList()
                customActions = menu + CustomAccessibilityAction(settingsLabel) { actions.openSettings(); true }
                if (note == null) expand(clickLabel) { actions.expand(); true }
            }
            .pressScale(source)
            .focusRing(FocalShapes.sheet)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.takeIf { it.pressed && !it.previousPressed }?.let { press.at = it.position }
                    }
                }
            }
            .combinedClickable(
                // While its title is renamed in place, the card itself does nothing.
                enabled = title == null,
                interactionSource = source, indication = null, onClickLabel = clickLabel,
                onLongClick = note?.let { row -> { actions.menu(row, IntOffset(press.at.x.roundToInt(), press.at.y.roundToInt())) } },
                onClick = { if (note != null) actions.open(note) else actions.expand() },
            )
            .clip(FocalShapes.sheet)
            .sceneMaterial(FocalShapes.sheetRadius, ring = colors.stageRing),
    ) {
        if (shimmering) Spacer(Modifier.matchParentSize().shimmer { shimmer.value })
        when (stage) {
            is StageUi.Live -> RecordedStage { LiveStage(stage, meter, seconds) }
            is StageUi.Note -> RecordedStage { NoteStage(
                stage, dates, today, actions, title, origin, shown,
                onReadyMoment = {
                    if (!reduced) scope.launch {
                        delay(FocalTiming.READY_SHIMMER_DELAY_MS)
                        shimmer.snapTo(0f)
                        shimmering = true
                        try {
                            shimmer.animateTo(1f, tween(FocalMotion.readyShimmer.durationMillis, easing = LinearEasing))
                        } finally {
                            shimmering = false
                        }
                    }
                },
            ) }
        }
        menu()
    }
}

/**
 * The stage's content, drawn through a graphics layer the recording surface and the page transforms draw too: Record
 * grows the scene out of this card and the note leaves with the clip's top-left (T1′); the stage opens into its
 * note's page with its content riding the clip (T16′, T7b). While the live stage opens into the page, the layer keeps
 * the recording it had when Finish was tapped: the content is leaving, it must not turn into the note on its way out.
 */
@Composable
private fun RecordedStage(content: @Composable () -> Unit) {
    val shell = LocalShell.current
    val live = shell.live
    val layer = rememberGraphicsLayer()
    DisposableEffect(live, layer) {
        live.stageNote = layer
        onDispose { if (live.stageNote === layer) live.stageNote = null }
    }
    Box(
        Modifier.drawWithContent {
            if (!shell.pages.freezesStage()) layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        },
    ) { content() }
}

/** The live stage's timer: the only part of the card that reads the recording's time while it composes. */
@Composable
private fun LiveTimer(seconds: () -> Long, style: TextStyle, width: (Long) -> Float, color: ColorProducer, modifier: Modifier = Modifier) =
    TimerText(seconds(), style, width, color, modifier)

/** A description that changes on its own clock, kept apart from the node that speaks it. */
@Stable
private class SpokenText {
    var text by mutableStateOf("")
}

/** Resolves [text] in a scope of its own and hands it to [holder], read by the stage's semantics. */
@Composable
private fun Speaks(holder: SpokenText, text: @Composable () -> String) {
    val resolved = text()
    SideEffect { holder.text = resolved }
}

/** TalkBack's reading of the stage (spec 7.8, 8.2). */
@Composable
private fun stageDescription(stage: StageUi, dates: LibraryDates, today: LocalDate): String = when (stage) {
    is StageUi.Live -> {
        val elapsed = spokenDuration(stage.elapsedSeconds)
        val reason = when (stage.pauseReason) {
            PauseReason.CALL -> stringResource(R.string.a11y_reason_call)
            PauseReason.SILENCED -> stringResource(R.string.a11y_reason_other_app)
            PauseReason.STORAGE -> stringResource(R.string.a11y_reason_storage)
            null -> null
        }
        val latest = stage.paragraphs.lastOrNull()?.text
        when {
            stage.paused && reason != null -> stringResource(R.string.a11y_stage_paused_reason, elapsed, reason)
            stage.paused -> stringResource(R.string.a11y_stage_paused, elapsed)
            latest != null -> stringResource(R.string.a11y_stage_live, elapsed, latest)
            else -> stringResource(R.string.a11y_stage_live_empty, elapsed)
        }
    }
    is StageUi.Note -> {
        val note = stringResource(
            R.string.a11y_stage_note,
            noteTitle(stage.row, dates),
            spokenWhen(stage.row.created, dates, today),
            spokenDuration(stage.row.durationMs / MS_PER_S),
        )
        // The card's description replaces its texts for TalkBack: the reason drawn as the last line is spoken too.
        val reason = stage.row.waitingReason?.takeIf { stage.row.phase == NotePhase.WAITING }
        if (reason == null) note else stringResource(R.string.a11y_stage_note_reason, note, waitingText(reason, page = false))
    }
}

private const val MS_PER_S = 1000L

/** "4 minutes 16 seconds": each part omitted when 0 (spec 7.8 `a11y_duration`). */
@Composable
fun spokenDuration(seconds: Long): String {
    val (h, m, s) = Lengths.spokenParts(seconds)
    val parts = buildList {
        if (h > 0) add(pluralStringResource(R.plurals.a11y_hours, h.toInt(), h.toInt()))
        if (m > 0) add(pluralStringResource(R.plurals.a11y_minutes, m.toInt(), m.toInt()))
        if (s > 0 || (h == 0L && m == 0L)) add(pluralStringResource(R.plurals.a11y_seconds, s.toInt(), s.toInt()))
    }
    return parts.joinToString(" ")
}

/** "Today, 16:05" and the other full forms TalkBack reads. */
@Composable
fun spokenWhen(epochMs: Long, dates: LibraryDates, today: LocalDate): String = dates.spoken(epochMs, today, datelines())

/** The `dateline*` templates (spec 7.1): "Today, %s", "Yesterday, %s", "%1$s, %2$s". */
@Composable
fun datelines(): SpokenDateline {
    val todayFormat = stringResource(R.string.dateline_today)
    val yesterdayFormat = stringResource(R.string.dateline_yesterday)
    val datedFormat = stringResource(R.string.dateline)
    return remember(todayFormat, yesterdayFormat, datedFormat) {
        SpokenDateline({ todayFormat.format(it) }, { yesterdayFormat.format(it) }, { date, time -> datedFormat.format(date, time) })
    }
}

/** The note's title, or the fallback "Note at 14:32". */
@Composable
fun noteTitle(row: NoteRow, dates: LibraryDates): String =
    row.title.ifBlank { stringResource(R.string.title_fallback, dates.time(row.created)) }

/** The preview under a title: a derived title is not repeated under itself (spec 4.12.2). */
fun previewAfterTitle(row: NoteRow): String =
    if (!row.titleIsCustom && row.title.isNotBlank()) Title.remainder(row.preview) else row.preview

/**
 * The readiness shimmer (2.2.1 `stageShimmer`, T8″): a 180 dp band rotated 18°, white 0 → 3.5 % → 10 % → 3.5 % → 0
 * across its width, crossing the card left to right with `readyShimmer`; its alpha is sin(π·u). Drawn between the
 * material and the text, clipped by the card, and masked out of the highlight band (F1). [progress] is u (linear),
 * read in the draw phase.
 */
private fun Modifier.shimmer(progress: () -> Float): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        val u = progress()
        val band = StageMetrics.shimmerWidth.toPx()
        val slant = size.height * tan(StageMetrics.SHIMMER_ANGLE * PI.toFloat() / HALF_TURN)
        val x = FocalMotion.readyShimmer.easing.transform(u)
        val centre = -band - slant + x * (size.width + 2 * (band + slant))
        val peak = Color.White.copy(alpha = Stage.SHIMMER_PEAK)
        val shoulder = Color.White.copy(alpha = Stage.SHIMMER_SHOULDER)
        val brush = Brush.horizontalGradient(
            0f to Color.Transparent, StageMetrics.SHIMMER_SHOULDER_AT to shoulder, HALF to peak,
            1 - StageMetrics.SHIMMER_SHOULDER_AT to shoulder, 1f to Color.Transparent,
            startX = centre - band / 2, endX = centre + band / 2,
        )
        val reach = size.height / cos(StageMetrics.SHIMMER_ANGLE * PI.toFloat() / HALF_TURN)
        rotate(StageMetrics.SHIMMER_ANGLE, pivot = Offset(centre, size.height / 2)) {
            drawRect(brush, topLeft = Offset(centre - band / 2, size.height / 2 - reach), size = androidx.compose.ui.geometry.Size(band, 2 * reach), alpha = sin(PI.toFloat() * u))
        }
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, 1f to Color.Black,
                startY = StageMetrics.shimmerMaskStart.toPx(), endY = StageMetrics.shimmerMaskEnd.toPx(),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private const val HALF = 0.5f
private const val HALF_TURN = 180f

/**
 * The live state (spec 4.22.3): the live wave (dimmed to the full profile at 40 % with its badge while paused), the
 * running timer beside it, and the latest text, bottom-anchored under a 34 dp mask. No word says "Recording" or
 * "Paused"; only a storage-full pause writes one line.
 */
@Composable
private fun LiveStage(stage: StageUi.Live, meter: WaveMeter, seconds: () -> Long) {
    val shell = LocalShell.current
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    // The scene's timer at the stage's size (spec 4.22.7), with the same reserved widths (R5): the morph scales one
    // into the other.
    val timers = rememberTimerStyles(LocalWindowInfo.current.containerSize.width.toFloat())
    val timerColor = remember { ColorAnimatable(if (stage.paused) colors.stageRecede else Scene.hi) }
    LaunchedEffect(stage.paused, colors.stageRecede) { timerColor.animateTo(if (stage.paused) colors.stageRecede else Scene.hi, FocalMotion.develop()) }
    val badge = remember { Animatable(if (stage.paused) 1f else 0f) }
    LaunchedEffect(stage.paused) { badge.animateTo(if (stage.paused) 1f else 0f, FocalMotion.fadeFast()) }
    val lineHeight = with(density) { FocalType.stageText.lineHeight.toPx() }
    val inks = TranscriptInks(colors.stageInk, colors.stageRecede, Scene.faint)
    val short = LocalWindowLayout.current.rail
    Layout(
        contents = listOf(
            {
                Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        // Under the open scene the stage is covered (the scene draws the same meter): no frames.
                        NineBars(
                            WaveMode.Live(meter, stage.paused), WaveDress.Material, Modifier.markBox(StageMetrics.wave),
                            active = { !shell.live.open },
                        )
                        // The badge is part of the mark, which never mirrors: placed from the wave's left edge in every
                        // layout direction, as the morph places it (wave.left + BADGE_X), so the first frame of an
                        // expand does not move it.
                        val k = StageMetrics.wave.value / Mark.BADGE_REFERENCE_HEIGHT
                        PauseBadge(
                            diameter = { PauseBadgeMetrics.stage },
                            modifier = Modifier
                                .align(AbsoluteAlignment.TopLeft)
                                .absoluteOffset((Mark.BADGE_X * k).dp - PauseBadgeMetrics.scene / 2, (Mark.BADGE_Y * k).dp - PauseBadgeMetrics.scene / 2)
                                .graphicsLayer { alpha = badge.value },
                        )
                    }
                    Spacer(Modifier.width(StageMetrics.liveGap))
                    // The timer's line is centred on the wave's centre (y 104) whatever its glyphs' box measures: a
                    // taller box overhangs the row instead of pushing the wave down.
                    LiveTimer(seconds, timers.stage, timers::stageWidth, color = { timerColor.value }, Modifier.wrapContentHeight(unbounded = true))
                }
            },
            {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .clearAndSetSemantics { }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent, 1f to Color.Black,
                                    endY = StageMetrics.liveMask.toPx() * lineScale(FocalType.stageText),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    StageTranscript(
                        stage.paragraphs, FocalType.stageWords, inks, focus = !stage.storageFull,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true),
                        hint = if (stage.hint && !stage.storageFull) { { Text(stringResource(R.string.hint_first_text), style = FocalType.stageText, color = colors.stageRecede) } } else null,
                        footer = liveFooter(stage),
                    )
                }
            },
        ),
    ) { (head, region), constraints ->
        val width = constraints.maxWidth
        val inset = StageMetrics.inset.roundToPx()
        val column = width - 2 * inset
        // The head row is the wave's height (or the timer's line, if taller), exactly as the morph assumes: the wave
        // box at the inset, the timer's line on its centre.
        val headHeight = max(StageMetrics.wave.roundToPx(), timers.stageLine.roundToInt())
        val top = head.first().measure(Constraints(maxWidth = column, minHeight = headHeight, maxHeight = headHeight))
        val regionTop = inset + headHeight + StageMetrics.liveGap.roundToPx()
        val height = max(StageMetrics.minHeight(short).roundToPx(), regionTop + (StageMetrics.TEXT_LINES_MIN * lineHeight).roundToInt() + inset)
        val text = region.first().measure(Constraints.fixed(column, height - inset - regionTop))
        // Relative: right to left, the row runs from the card's right edge (the wave at 296-368 on the reference
        // frame), as the morph's origin mirrors it (spec 8.5).
        layout(width, height) {
            top.placeRelative(inset, inset)
            text.placeRelative(inset, regionTop)
        }
    }
}

/**
 * The live region's last line: "Storage is full" in ink (the only caption; the transcript above recedes), or a
 * model condition, receded.
 */
@Composable
private fun liveFooter(stage: StageUi.Live): (@Composable () -> Unit)? {
    val colors = FocalTheme.colors
    return when {
        stage.storageFull -> {
            { Text(stringResource(R.string.paused_storage), style = FocalType.stageText.copy(fontWeight = FocalWeight.Medium), color = colors.stageInk) }
        }
        stage.speech == SpeechAvailability.PREPARING -> {
            { Text(stringResource(R.string.state_preparing), style = FocalType.stageText, color = colors.stageRecede) }
        }
        stage.speech == SpeechAvailability.UNAVAILABLE -> {
            { Text(stringResource(R.string.state_later), style = FocalType.stageText, color = colors.stageRecede) }
        }
        else -> null
    }
}

/**
 * A note on the stage (spec 4.22.4): the title with the time on its first baseline, the text under it in whole
 * lines ending on a whole word, and one footer row: the length on the left, one pill on the right.
 */
@Composable
private fun NoteStage(
    stage: StageUi.Note,
    dates: LibraryDates,
    today: LocalDate,
    actions: StageActions,
    renameField: (@Composable () -> Unit)?,
    origin: Origin,
    shown: () -> Boolean,
    onReadyMoment: () -> Unit,
) {
    val colors = FocalTheme.colors
    val pages = LocalShell.current.pages
    val row = stage.row
    val density = LocalDensity.current
    val large = density.fontScale >= StageMetrics.LARGE_FONT
    val noSpeech = LibraryModels.noSpeech(row)
    val fallback = row.title.isBlank()
    val pill = when (row.phase) {
        NotePhase.TRANSCRIBING -> StagePillUi.Progress(ProgressPhase.Transcribing, row.progress ?: 0f)
        NotePhase.WAITING -> StagePillUi.Progress(ProgressPhase.Waiting, 0f)
        NotePhase.PREPARING_MODEL -> StagePillUi.Progress(ProgressPhase.Preparing, 0f)
        else -> StagePillUi.Copy(fresh = stage.fresh, enabled = LibraryModels.copyable(row))
    }
    val lengthYields = remember { Animatable(1f) }
    var overlapping by remember { mutableStateOf(false) }
    LaunchedEffect(overlapping) { lengthYields.animateTo(if (overlapping) 0f else 1f, FocalMotion.fadeFast()) }
    val lineHeight = with(density) { FocalType.stageText.lineHeight.toPx() }
    val short = LocalWindowLayout.current.rail
    val titleText = noteTitle(row, dates)
    // A time on its own line keeps to the title's edge (a derived title follows the note's direction, spec 8.5).
    val titleLeft = alignWith(titleText) == TextAlign.Left
    Layout(
        contents = listOf(
            {
                if (renameField != null) renameField() else Text(
                    titleText, style = FocalType.headline.copy(lineBreak = LineBreak.Heading),
                    color = if (fallback) colors.stageRecede else colors.stageInk,
                    maxLines = if (large) StageMetrics.TITLE_LINES_LARGE else StageMetrics.TITLE_LINES, overflow = TextOverflow.Ellipsis,
                    // While the stage opens into its page (or folds back), a copy of the title flies between them.
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .pageOrigin(origin, title = true)
                        .graphicsLayer { alpha = if (pages.flying(row.id)) 0f else 1f },
                )
            },
            {
                Text(
                    dates.stageWhen(row.created, today), style = FocalType.dateline, color = colors.stageRecede, maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            },
            { Box(Modifier.clearAndSetSemantics { }.stageText(origin)) { StageText(row, noSpeech) } },
            { Box(Modifier.graphicsLayer { alpha = lengthYields.value }) { StageLength(row.durationMs) } },
            { StagePill(pill, row.id, onCopy = { actions.copy(row) }, onReadyMoment = onReadyMoment, Modifier.testTag(STAGE_PILL_TAG), shown = shown) },
        ),
    ) { (titles, times, texts, lengths, pills), constraints ->
        val width = constraints.maxWidth
        val inset = StageMetrics.inset.roundToPx()
        val column = width - 2 * inset
        val time = times.first().measure(Constraints(maxWidth = column))
        // From 1.5x, or when the time is long (a date), it takes its own line under the title (spec 4.22.4, 4.22.7).
        val timeOwnLine = large || time.width + StageMetrics.timeGap.roundToPx() > StageMetrics.TIME_SHARE * column
        val titleWidth = if (timeOwnLine) column else column - time.width - StageMetrics.timeGap.roundToPx()
        // The title takes its whole width, so its text aligns in its own direction over the text under it, however
        // short it is (a box as wide as its text would sit at the start edge, the right in a mirrored layout).
        val title = titles.first().measure(Constraints(minWidth = titleWidth, maxWidth = titleWidth))
        val timeTop = if (timeOwnLine) inset + title.height else inset + title[FirstBaseline] - time[FirstBaseline]
        val headBottom = if (timeOwnLine) inset + title.height + time.height else inset + title.height
        val textTop = headBottom + StageMetrics.textGap.roundToPx()
        // The pill may grow past 48 dp at large font sizes; the footer row grows with it.
        val pill = pills.first().measure(Constraints())
        val footer = max(StageMetrics.footer.roundToPx(), pill.height)
        val footerInset = StageMetrics.footerInset.roundToPx()
        // From 1.5x the length takes its own 48 dp row above the pill's.
        val lengthRow = if (large) StageMetrics.footer.roundToPx() else 0
        // At the card's rest height, the lines that fit above the footer; never fewer than two (the card grows), one
        // in a short window.
        val restFooterTop = StageMetrics.minHeight(short).roundToPx() - footerInset - footer - lengthRow
        val fit = ((restFooterTop - StageMetrics.textClearance.roundToPx() - textTop) / lineHeight).toInt()
        val lines = fit.coerceIn(StageMetrics.textLinesMin(short), StageMetrics.TEXT_LINES_MAX)
        val text = texts.first().measure(Constraints(maxWidth = column, maxHeight = (lines * lineHeight).roundToInt()))
        val height = max(
            StageMetrics.minHeight(short).roundToPx(),
            textTop + text.height + StageMetrics.textClearance.roundToPx() + lengthRow + footer + footerInset,
        )
        val length = lengths.first().measure(Constraints(maxWidth = column))
        val footerTop = height - footerInset - footer
        val pillX = width - footerInset - pill.width
        val lengthTop = if (large) footerTop - lengthRow else footerTop
        val lengthSlot = if (large) lengthRow else footer
        overlapping = !large && inset + length.width + StageMetrics.footerYield.roundToPx() > pillX
        // Relative: right to left, the time sits at the title's left and the pill at the start of the footer (8.5).
        layout(width, height) {
            title.placeRelative(inset, inset)
            when {
                !timeOwnLine -> time.placeRelative(width - inset - time.width, timeTop)
                titleLeft -> time.place(inset, timeTop)
                else -> time.place(width - inset - time.width, timeTop)
            }
            text.placeRelative(inset, textTop)
            length.placeRelative(inset, lengthTop + (lengthSlot - length.height) / 2)
            pill.placeRelative(pillX, footerTop)
        }
    }
}

/**
 * The stage's text: the note's preview in whole lines (a derived title is not repeated), or "No speech was
 * recognized."; a partial failure replaces the last line with "Part of this note couldn't be transcribed.", a waiting
 * note that is not being transcribed with why ([waitingText]: "Not enough free memory to transcribe right now." only for
 * a confirmed out-of-memory).
 */
@Composable
private fun StageText(row: NoteRow, noSpeech: Boolean) {
    val colors = FocalTheme.colors
    if (noSpeech) {
        Text(stringResource(R.string.no_speech), style = FocalType.stageText, color = colors.stageRecede, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val preview = remember(row.preview, row.title, row.titleIsCustom) { AnnotatedString(previewAfterTitle(row)) }
    Column {
        // Balanced breaks, as the stage's reading text: no orphan word on its last line.
        CutText(preview, StageTextStyle, colors.stageRecede, StageMetrics.TEXT_LINES_MAX, Modifier.weight(1f, fill = false))
        // A partial failure or a waiting note's reason takes the text's last line (spec 4.22.4).
        val reason = row.waitingReason?.takeIf { row.phase == NotePhase.WAITING }
        val lastLine = when {
            row.phase == NotePhase.PARTIAL_FAILURE -> stringResource(R.string.partial_failure)
            reason != null -> waitingText(reason, page = false)
            else -> null
        }
        if (lastLine != null) {
            Text(
                lastLine, style = StageReasonStyle, color = colors.stageRecede,
                maxLines = StageMetrics.TEXT_LINES_MIN, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val StageTextStyle = FocalType.stageWords.copy(lineBreak = LineBreak.Paragraph)
/** A reason that needs two lines splits them evenly ("Not enough free memory / to transcribe right now."), never a widow. */
private val StageReasonStyle = FocalType.stageText.copy(lineBreak = LineBreak.Heading)

/**
 * The note's length (spec 4.22.4): thin numerals in `stageLength`, each unit small after them ("12 min",
 * "1 h 12 min", "42 s"), baseline-aligned. The first numeral is pulled left by its left side bearing, so its ink
 * lands on the text column's edge. Each unit sits 5 dp after its numerals' advance as CSS lays it out (every figure's
 * tabular advance plus its tracking), not after the text box Android measures, which ends 1.5 to 3 dp later.
 */
@Composable
private fun StageLength(durationMs: Long) {
    val colors = FocalTheme.colors
    val parts = Lengths.parts(durationMs)
    val units = mapOf(
        LengthUnit.Seconds to stringResource(R.string.len_s),
        LengthUnit.Minutes to stringResource(R.string.len_min),
        LengthUnit.Hours to stringResource(R.string.len_h),
    )
    val numerals = parts.map { it.value.toString() }
    val figures = rememberFigureMetrics()
    val bearing = figures.leftSideBearing(numerals.first().first())
    Layout(
        content = {
            parts.forEachIndexed { index, part ->
                Text(numerals[index], style = FocalType.stageLength, color = colors.stageInk)
                Text(units.getValue(part.unit), style = FocalType.meta.copy(fontWeight = FocalWeight.Medium), color = colors.stageRecede)
            }
        },
        modifier = Modifier.clearAndSetSemantics { },
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val baseline = placeables.maxOf { it[FirstBaseline] }
        val xs = IntArray(placeables.size)
        var x = -bearing
        placeables.forEachIndexed { index, placeable ->
            val group = index / 2
            if (index % 2 == 0) {
                if (group > 0) x += StageMetrics.groupGap.toPx()
                xs[index] = x.roundToInt()
                x += figures.advance(numerals[group])
            } else {
                x += StageMetrics.unitGap.toPx()
                xs[index] = x.roundToInt()
                x += placeable.width
            }
        }
        val height = placeables.maxOf { baseline - it[FirstBaseline] + it.height }
        layout(max(0, x.roundToInt()), height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(xs[index], baseline - placeable[FirstBaseline]) }
        }
    }
}

/** Ink and advance metrics of the thin display figures at the stage's length size, in pixels. */
private class FigureMetrics(private val paint: android.graphics.Paint?, private val trackingPx: Float) {
    private val bounds = Rect()

    /** The ink's left side bearing of [glyph]. */
    fun leftSideBearing(glyph: Char): Float {
        val p = paint ?: return 0f
        p.getTextBounds(glyph.toString(), 0, 1, bounds)
        return bounds.left.coerceAtLeast(0).toFloat()
    }

    /** The advance of [figures] as CSS lays it out: the tabular advances plus the tracking after every figure. */
    fun advance(figures: String): Float {
        val p = paint ?: return 0f
        return p.measureText(figures) + figures.length * trackingPx
    }
}

@Composable
private fun rememberFigureMetrics(): FigureMetrics {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val typeface = resolver.resolve(FocalDisplay, FocalWeight.Thin, FontStyle.Normal, FontSynthesis.None).value as? Typeface
    return remember(typeface, density) {
        val size = with(density) { FocalType.stageLength.fontSize.toPx() }
        val paint = typeface?.let {
            android.graphics.Paint().apply {
                this.typeface = it
                textSize = size
                fontFeatureSettings = FocalType.stageLength.fontFeatureSettings
            }
        }
        FigureMetrics(paint, FocalType.stageLength.letterSpacing.value * size)
    }
}
