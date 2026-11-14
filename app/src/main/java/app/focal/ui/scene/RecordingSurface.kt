package app.focal.ui.scene

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.R
import app.focal.recording.SpeechAvailability
import app.focal.ui.components.BottomColumn
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.LiveMeter
import app.focal.ui.components.MaterialBounds
import app.focal.ui.components.NineBars
import app.focal.ui.components.PauseBadge
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMeter
import app.focal.ui.components.WaveMode
import app.focal.ui.components.WaveRenderer
import app.focal.ui.components.focusRing
import app.focal.ui.components.glassHighlight
import app.focal.ui.components.pressScale
import app.focal.ui.components.rememberBottomColumnState
import app.focal.ui.components.sceneMaterial
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.Scene
import app.focal.ui.design.chromeAlign
import app.focal.ui.library.StageMetrics
import app.focal.ui.library.spokenDuration
import app.focal.ui.shell.LiveSurfaceState
import app.focal.ui.shell.LocalDockAreas
import app.focal.ui.shell.NavigationBar
import app.focal.ui.shell.SceneChoreography
import app.focal.ui.shell.SceneEntry
import app.focal.ui.shell.SystemBarsState
import app.focal.ui.state.LiveParagraph
import app.focal.ui.state.PauseReason
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What the scene shows: immutable, from the recording view model in the app and from fakes in the gallery. */
@Immutable
data class SceneUi(
    val elapsedSeconds: Long = 0,
    val paused: Boolean = false,
    val pauseReason: PauseReason? = null,
    val speech: SpeechAvailability = SpeechAvailability.READY,
    val paragraphs: List<LiveParagraph> = emptyList(),
    /** The first recordings: "Text appears after each pause." until the first text (spec 4.21.5). */
    val hint: Boolean = false,
    /** Notifications are off: the hint slot says Pause and Finish are only in Focal (spec 5.3.8). */
    val notificationsOff: Boolean = false,
) {
    val storageFull: Boolean get() = paused && pauseReason == PauseReason.STORAGE
    val hasText: Boolean get() = paragraphs.isNotEmpty()
}

/** What the scene's own controls do (Pause, Resume and Finish are the root dock's). */
@Stable
class SceneActions(
    /** The chevron, the root's collapse action: fold into the stage (T5′). */
    val collapse: () -> Unit = {},
    /** Space in the scene (spec 8.5). */
    val togglePause: () -> Unit = {},
)

/**
 * The recording surface (spec 4.21, 6.4): the logo's material growing out of the stage (or the empty library's
 * resting instrument) into the full-window scene and folding back, every element a function of p read in the layout
 * and draw phases, so neither a drag nor a spring recomposes it. At p = 1 it is the scene: the chevron, the hero
 * wave, the big thin timer, the state slot, the hint or the live text, the "Latest" pill; Pause, Resume and Finish
 * are the root dock's, dressed by the material's edge. Finish settles it into the dock's pill (T7). Under reduced
 * motion, and when no origin is on screen, it crossfades in place at alpha p.
 *
 * [timerFocus] receives focus after an expand (spec 8.6).
 */
@Composable
fun RecordingSurface(
    live: LiveSurfaceState,
    scene: SceneUi,
    meter: WaveMeter,
    actions: SceneActions,
    modifier: Modifier = Modifier,
    bars: SystemBarsState? = null,
    timerFocus: FocusRequester = remember { FocusRequester() },
    listState: LazyListState = rememberLazyListState(),
) {
    val density = LocalDensity.current
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val window = LocalWindowInfo.current.containerSize
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val navigationBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    val timers = rememberSceneTimerStyles()
    val compact = density.fontScale >= SceneMetrics.COMPACT_FONT
    // The page area the scene's column keeps (spec 5.14: beside the rail; the whole window over two panes) and the one
    // Finish settles into.
    val areas = LocalDockAreas.current
    val rail = LocalWindowLayout.current.rail
    val area = areas.scene.span(window.width, density)
    val landingArea = areas.landing.span(window.width, density)
    val slots = remember(window, statusTop, navigationBottom, timers, compact, density, area, landingArea, rail, rtl) {
        SceneSlots.of(
            window.width.toFloat(), window.height.toFloat(), statusTop, navigationBottom, timers.sceneLine, compact, density,
            area = area, landing = landingArea, rail = rail, rtl = rtl,
        )
    }
    val entry = live.entry
    val landing = live.landingNote != null
    // The surface's own rule ([LiveSurfaceState.spatial]), with the motion setting this composition sees.
    val spatial = !reduced && (live.overLibrary() || landing)
    val choreography = live.choreography
    EntryEffects(live, choreography, meter)
    // "↓ Latest"'s alpha: the text region clears a band above it while it shows.
    val latest = remember { Animatable(0f) }
    val lift = remember { Animatable(if (scene.hasText) 1f else 0f) }
    LaunchedEffect(scene.hasText) {
        when {
            !scene.hasText -> lift.snapTo(0f)
            reduced -> lift.snapTo(1f)
            else -> lift.animateTo(1f, FocalMotion.stage())
        }
    }
    // The stage's timer width depends only on the time's format (its reserved template).
    val template = TimerMetrics.template(scene.elapsedSeconds)
    val stageTimerWidth = timers.stageWidth(scene.elapsedSeconds)
    val frame = remember(slots, spatial, entry, timers, template, rtl, density) {
        derivedStateOf {
            if (!spatial) Morph.rest(lift.value, slots)
            else Morph.frame(live.expansion.value, lift.value, origin(live, entry, slots, timers, stageTimerWidth, rtl, density), slots)
        }
    }
    val open by remember(live) { derivedStateOf { live.open } }
    val fold = remember(live) { FoldScroll(live, scope) }
    val touch = remember(live) { SurfaceTouch(live, scope, fold) }
    for (drag in listOf(fold, touch)) {
        drag.travel = { slots.height - (live.stageBounds?.bottom ?: restStage(slots, density).bottom) }
        drag.density = density.density
        drag.reduced = reduced || !spatial
    }
    touch.mayFold = { at -> slots.mayFold(at.x, at.y, slots.dockTop - with(density) { FocalLayout.dockInset.toPx() }) }
    // The dock's dress follows the material's clip; under the crossfade it flips at half (spec 6.2, 2.8.6).
    DisposableEffect(live, spatial, frame) {
        live.material = if (spatial) { { frame.value.clip } } else { { if (live.expansion.value >= HALF) MaterialBounds.Everywhere else MaterialBounds.Nowhere } }
        onDispose { live.material = null }
    }
    // The status and navigation icons turn light where the material is behind them (spec 6.2).
    if (bars != null) {
        // Along the bottom, or on a side in a phone turned to landscape with three buttons (spec 5.14).
        val navigationInsets = WindowInsets.navigationBars
        val navigation = NavigationBar.of(
            slots.width, slots.height,
            left = navigationInsets.getLeft(density, LayoutDirection.Ltr), right = navigationInsets.getRight(density, LayoutDirection.Ltr),
            bottom = navigationBottom.roundToInt(),
        )
        LaunchedEffect(bars, spatial, slots, navigation) {
            snapshotFlow {
                when {
                    live.landingNote != null && spatial -> BarsInput.Landing(Morph.landing(live.landing.value, slots).first)
                    live.landingNote != null -> BarsInput.Fade(live.landingAlpha.value)
                    spatial -> BarsInput.Morph(frame.value.clip)
                    else -> BarsInput.Fade(live.expansion.value)
                }
            }.collect { input ->
                when (input) {
                    is BarsInput.Morph -> bars.followMaterial(input.clip, navigation, density)
                    is BarsInput.Landing -> bars.followLanding(input.clip, slots.statusTop, navigation)
                    is BarsInput.Fade -> bars.followCrossfade(input.alpha)
                }
            }
        }
        DisposableEffect(bars) { onDispose { bars.followMaterial(null, navigation, density) } }
    }
    // The library switches to its live layout only once it is covered (spec 2.8.2).
    LaunchedEffect(live, live.entries, spatial) {
        snapshotFlow { live.expansion.value >= LIVE_LAYOUT_AT || !spatial }.collect { if (it) live.cover() }
    }
    val wantsFocus = rememberFocusHandOff()
    LaunchedEffect(open) { if (open && wantsFocus()) runCatching { timerFocus.requestFocus() } }

    val moving = live.dragging || live.expansion.isRunning
    val collapseLabel = stringResource(R.string.a11y_collapse)
    Box(
        modifier
            .fillMaxSize()
            .preferredFrameRate(if (moving) FrameRateCategory.High else FrameRateCategory.Normal)
            .surfaceTouch(touch)
            .onPreviewKeyEvent { event ->
                if (open && event.type == KeyEventType.KeyUp && event.key == Key.Spacebar) {
                    actions.togglePause()
                    true
                } else false
            }
            .then(
                // Only the open scene is read; in flight it is motion, not content.
                if (open) Modifier.semantics { collapse(collapseLabel) { actions.collapse(); true } } else Modifier.clearAndSetSemantics { },
            ),
    ) {
        // The material and everything on it, clipped to the morph's rounded rect (or the landing's).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val f = frame.value
                    val (clip, radius) = if (landing && spatial) Morph.landing(live.landing.value, slots) else f.clip to f.radius
                    shape = MorphShape(clip, radius)
                    this.clip = true
                    alpha = (if (spatial) 1f else live.expansion.value) * live.landingAlpha.value
                },
        ) {
            Material(live, frame, slots, spatial, landing, colors.stageRing, wake = if (entry == SceneEntry.RecordEmpty && spatial) live.wake else null)
            if (entry == SceneEntry.Record && spatial) LeavingNote(live, frame, choreography)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = choreography.chrome.value * live.landingContent.value }) {
                // The stage's typesetting travels only out of (and into) a live stage: Record grows the scene out of a
                // note or the resting instrument, whose text leaves on its own (T1′, T1′e).
                TextRegion(
                    live, scene, frame, slots, fold, listState, latest,
                    stage = spatial && live.covered && !(entry == SceneEntry.RecordEmpty && live.restBounds != null),
                )
                Hint(scene, frame, slots, reduced)
                StateLine(scene, frame, slots, lift, spatial)
                Wave(scene, meter, frame, live, choreography, spatial, entry)
                Timer(scene, frame, timers, live, choreography, spatial, timerFocus)
                Chevron(frame, slots, actions.collapse)
                LatestPill(open, listState, slots, latest)
            }
            if (landing) LandingDock(scene.paused, live)
        }
        // T1′e: the resting instrument's paper dress, over the material until it is opaque (Light only).
        if (entry == SceneEntry.RecordEmpty && spatial && !colors.dark && live.restBounds != null) {
            NineBars(
                WaveMode.Live(meter), WaveDress.paper(colors),
                Modifier.placeAt { frame.value.wave }.graphicsLayer { alpha = 1 - live.wake.value },
                renderer = WaveRenderer.inFlight, entry = choreography.wave,
            )
        }
    }
}

/** What the system bars follow: the morph's clip, the landing's clip, or a crossfade's alpha. */
private sealed interface BarsInput {
    data class Morph(val clip: Rect) : BarsInput
    data class Landing(val clip: Rect) : BarsInput
    data class Fade(val alpha: Float) : BarsInput
}

private const val HALF = 0.5f
/** The library switches to its live layout under the scene from this p (2.8.2 `liveLayoutSwitch`). */
private const val LIVE_LAYOUT_AT = 0.95f

/** Where the morph grows from: the stage's live slots, the empty library's resting instrument, or the stage's rest place. */
private fun origin(
    live: LiveSurfaceState,
    entry: SceneEntry,
    slots: SceneSlots,
    timers: TimerStyles,
    timerWidth: Float,
    rtl: Boolean,
    density: Density,
): MorphOrigin {
    val startTimer = Offset(slots.instrumentX, slots.start.timerTop + slots.timerLine / 2)
    // From the empty library the morph grows out of the resting instrument while it is there; once the library has
    // its live stage (it switched while covered), the scene folds into the stage like any other.
    if (entry == SceneEntry.RecordEmpty) live.restBounds?.let { return MorphOrigin.rest(it, startTimer) }
    val bounds = live.stageBounds ?: restStage(slots, density)
    val head = max(with(density) { StageLiveMetrics.wave.toPx() }, timers.stageLine)
    return MorphOrigin.stage(bounds, head, timerWidth, timers.stageScale, rtl, density)
}

/**
 * Where the stage sits with the list at its top (16 dp under the status bar, 240 tall; 200 in a short window): the
 * origin while it is not laid out.
 */
private fun restStage(slots: SceneSlots, density: Density): Rect = with(density) {
    val top = slots.statusTop + FocalLayout.topFade.toPx()
    Rect(slots.columnLeft, top, slots.columnRight, top + StageMetrics.minHeight(short = slots.twoColumn).toPx())
}


/** A rounded rect inside the layer's bounds: the morph's clip. */
private class MorphShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** Places a child at the window rect [rect] inside the full-window surface (read in the layout phase). */
private fun Modifier.placeAt(rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(Constraints.fixed(max(0, r.width.roundToInt()), max(0, r.height.roundToInt())))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

/**
 * Places a child at its own size, centred on [centreX] with its top at [top] (both window pixels, layout phase); with
 * [middle], [top] is where its vertical middle goes.
 */
private fun Modifier.placeCentred(maxWidth: Float, centreX: () -> Float, middle: Boolean = false, top: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = maxWidth.roundToInt()))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val y = if (middle) top() - placeable.height / 2f else top()
        placeable.place((centreX() - placeable.width / 2f).roundToInt(), y.roundToInt())
    }
}

/**
 * The light-up starts from the full logo: the meter is seeded at v = 1 and relaxes with its release (spec 3.2). Folding
 * back into the empty library's resting instrument, the material fades as it lands (reverse T1′e).
 */
@Composable
private fun EntryEffects(live: LiveSurfaceState, choreography: SceneChoreography, meter: WaveMeter) {
    LaunchedEffect(choreography) {
        if (choreography.seedsMeter) withFrameNanos { nanos -> (meter as? LiveMeter)?.seed(1f, nanos / NANOS_PER_MS) }
    }
    LaunchedEffect(choreography) {
        if (choreography.kind != SceneEntry.RecordEmpty) return@LaunchedEffect
        snapshotFlow { live.restBounds != null && live.expansion.targetValue <= 0f && live.expansion.value < WAKE_FADE_AT }.collect { landing ->
            if (landing) live.wake.animateTo(0f, FocalMotion.fadeFast())
        }
    }
}

private const val NANOS_PER_MS = 1_000_000L
/** Folding into the empty library, the material starts fading out below this p. */
private const val WAKE_FADE_AT = 0.25f

/**
 * The logo's material in the clip (spec 2.2): the gradient and the highlight stretch with the clip, the grain stays
 * put in the window, anchored where the card's own grain was, so the hand-off at p = 0 is seamless; the Dark ring
 * separates the card from dark paper as long as it is a card (alpha 1 - p). In T7 the material keeps the window's
 * size and the clip cuts it.
 */
@Composable
private fun Material(
    live: LiveSurfaceState,
    frame: State<MorphFrame>,
    slots: SceneSlots,
    spatial: Boolean,
    landing: Boolean,
    ring: Color,
    wake: Animatable<Float, *>?,
) {
    val box: () -> Rect = { if (spatial && !landing) frame.value.clip else slots.window }
    Spacer(
        Modifier
            .placeAt(box)
            .graphicsLayer { alpha = wake?.value ?: 1f }
            .sceneMaterial(grainOrigin = { box().topLeft - frame.value.grainAnchor }),
    )
    if (ring.alpha > 0f && spatial && !landing) {
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val f = frame.value
                if (f.ringAlpha <= 0f) return@drawBehind
                val width = RingWidth.toPx()
                drawRoundRect(
                    ring, topLeft = f.clip.topLeft + Offset(width / 2, width / 2), size = Size(f.clip.width - width, f.clip.height - width),
                    cornerRadius = CornerRadius(max(0f, f.radius - width / 2)), style = Stroke(width), alpha = f.ringAlpha,
                )
            },
        )
    }
}

private val RingWidth = 1.dp

/** T1′: the stage's note content leaves with the clip's top-left (`fadeFast` from Record); it never comes back. */
@Composable
private fun LeavingNote(live: LiveSurfaceState, frame: State<MorphFrame>, entry: SceneChoreography) {
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = entry.note.value }
            .drawBehind {
                val layer = live.stageNote ?: return@drawBehind
                if (entry.note.value <= 0f) return@drawBehind
                val clip = frame.value.clip
                translate(clip.left, clip.top) { drawLayer(layer) }
            },
    )
}

/**
 * The text region (spec 4.21.6, 6.4): one box from the timer's ease-out to the clip's bottom less 20 to 120 dp, so
 * the latest line rides the card's bottom edge with the finger; a top alpha mask of 34 to 128 dp. In it, two
 * typesettings at their own widths and x, fading through: the stage's (17/24, at the clip's left + 20) and the
 * scene's (20/30, at the column). The scene's is the scrolling list; its upward leftovers fold the scene.
 */
@Composable
private fun TextRegion(
    live: LiveSurfaceState,
    scene: SceneUi,
    frame: State<MorphFrame>,
    slots: SceneSlots,
    fold: FoldScroll,
    listState: LazyListState,
    latest: Animatable<Float, *>,
    stage: Boolean,
) {
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    val readLabel = stringResource(R.string.a11y_read_latest)
    var announced by remember { mutableStateOf("") }
    val stageInks = TranscriptInks(colors.stageInk, colors.stageRecede, Scene.faint)
    val sceneInks = TranscriptInks(colors.sceneInk, colors.sceneRecede, Scene.faint)
    // On the stage a storage-full pause recedes the transcript under its "Storage is full" line (4.22.3); the scene
    // keeps its latest stretch bright and says it in the state line alone (4.21.4).
    val stageFocus = !scene.storageFull
    val paragraphs = scene.paragraphs
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val f = frame.value
                clipRect(top = f.textTop, bottom = f.textBottom) { this@drawWithContent.drawContent() }
                // The mask dissolves text that has scrolled up to the region's top. A transcript that still fits sits
                // at the top, latest stretch and all, unmasked: the mask grows in as the text overflows, full once a
                // line has gone under the edge (and whenever the stage's typesetting shows, always bottom-anchored), so
                // a line the edge cuts is always faded out at it.
                val masked = max(listState.overflow(FocalType.sceneText.lineHeight.toPx()), f.stageTextAlpha)
                val mask = f.textMask * masked
                if (mask >= 1f) drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = f.textTop, endY = f.textTop + mask),
                    blendMode = BlendMode.DstIn,
                )
                // While "↓ Latest" shows, the text ends 8 dp above it: no glyph sits under or against its glass.
                val a = latest.value
                if (a > 0f) {
                    val end = slots.latestBottom - (SceneMetrics.jumpHeight + SceneMetrics.jumpClearance).toPx()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black, 1f to Color.Black.copy(alpha = 1 - a),
                            startY = end - SceneMetrics.jumpFade.toPx(), endY = end,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        if (stage) {
            // The stage's typesetting, bottom-anchored at its own width; it moves with the clip's left edge.
            val inset = with(density) { StageLiveMetrics.inset.toPx() }
            val stageWidth = { (live.stageBounds?.width ?: (slots.columnRight - slots.columnLeft)) - 2 * inset }
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val f = frame.value
                        val placeable = measurable.measure(Constraints(minWidth = 0, maxWidth = stageWidth().roundToInt().coerceAtLeast(0)))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(f.stageTextLeft.roundToInt(), (f.textBottom - placeable.height).roundToInt())
                        }
                    }
                    .graphicsLayer { alpha = frame.value.stageTextAlpha }
                    .clearAndSetSemantics { },
            ) {
                StageTranscript(
                    scene.paragraphs, FocalType.stageWords, stageInks, stageFocus,
                    Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true),
                    hint = if (scene.hint && !scene.storageFull) { { Text(stringResource(R.string.hint_first_text), style = FocalType.stageText, color = colors.stageRecede) } } else null,
                    footer = stageFooter(scene),
                )
            }
        }
        SceneTranscript(
            scene.paragraphs, listState, FocalType.sceneWords, sceneInks,
            contentPadding = with(density) {
                val left = slots.textLeft.toDp()
                val right = (slots.width - slots.textRight).toDp()
                if (LocalLayoutDirection.current == LayoutDirection.Rtl) PaddingValues(start = right, end = left) else PaddingValues(start = left, end = right)
            },
            modifier = Modifier
                .placeAt { val f = frame.value; Rect(0f, f.textTop, slots.width, f.textBottom) }
                .graphicsLayer { alpha = frame.value.sceneTextAlpha }
                .nestedScroll(fold)
                // Reads the paragraphs, not the whole scene: the scene changes every second with its timer, and a
                // modifier that captured it would compose the transcript again every second while recording.
                .semantics {
                    val latest = paragraphs.lastOrNull()?.text
                    if (latest != null) customActions = listOf(CustomAccessibilityAction(readLabel) { announced = latest; true })
                },
        )
    }
    // "Read latest paragraph" speaks through a polite live region (spec 5.3.2).
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announced })
}

/**
 * How far the scene's list runs past its region, over [line] (0 while it all fits, 1 from a line on), read where it
 * is drawn. Arriving paragraphs open from zero height, so it grows smoothly as the text starts to overflow.
 */
private fun LazyListState.overflow(line: Float): Float {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return 0f
    if (items.size < info.totalItemsCount || line <= 0f) return 1f
    val content = items.sumOf { it.size } + info.mainAxisItemSpacing * (items.size - 1) + info.beforeContentPadding + info.afterContentPadding
    return ((content - info.viewportSize.height) / line).coerceIn(0f, 1f)
}

/** The stage's last line: "Storage is full" in ink (the only caption), or a model condition, receded. */
@Composable
private fun stageFooter(scene: SceneUi): (@Composable () -> Unit)? {
    val colors = FocalTheme.colors
    return when {
        scene.storageFull -> { { Text(stringResource(R.string.paused_storage), style = FocalType.stageText.copy(fontWeight = FocalWeight.Medium), color = colors.stageInk) } }
        scene.speech == SpeechAvailability.PREPARING -> { { Text(stringResource(R.string.state_preparing), style = FocalType.stageText, color = colors.stageRecede) } }
        scene.speech == SpeechAvailability.UNAVAILABLE -> { { Text(stringResource(R.string.state_later), style = FocalType.stageText, color = colors.stageRecede) } }
        else -> null
    }
}

/**
 * The hint before the first text (spec 4.21.5, 5.3.8): "Notifications are off, …" once per recording, else the first
 * recordings' "Text appears after each pause."; it arrives late in the morph and leaves on `fadeFast` with the first
 * text.
 */
@Composable
private fun Hint(scene: SceneUi, frame: State<MorphFrame>, slots: SceneSlots, reduced: Boolean) {
    val colors = FocalTheme.colors
    val text = when {
        scene.notificationsOff -> stringResource(R.string.notice_notifications_off)
        scene.hint -> stringResource(R.string.hint_first_text)
        else -> return
    }
    val shown = remember { Animatable(if (scene.hasText) 0f else 1f) }
    LaunchedEffect(scene.hasText) {
        val target = if (scene.hasText) 0f else 1f
        if (reduced) shown.snapTo(target) else shown.animateTo(target, FocalMotion.fadeFast())
    }
    // In two columns it waits on the first text line, at the column's start edge, where the text will replace it
    // (spec 5.14); otherwise centred under the instrument (4.21.5).
    val twoColumn = slots.twoColumn
    Text(
        text, style = FocalType.secondary.copy(lineBreak = LineBreak.Heading), color = colors.sceneRecede,
        textAlign = if (twoColumn) chromeAlign() else TextAlign.Center,
        modifier = Modifier
            .placeCentred(slots.textRight - slots.textLeft, { slots.hintX }, middle = twoColumn) { slots.hintTop }
            .then(if (twoColumn) Modifier.fillMaxWidth() else Modifier)
            .graphicsLayer { alpha = frame.value.lateAlpha * shown.value },
    )
}

/**
 * The state slot (spec 4.21.4): empty while recording and while paused, except "Storage is full" (it rides 2 dp under
 * the timer's box and arrives with the scene's typesetting) or a model condition (late, at its slot).
 */
@Composable
private fun StateLine(scene: SceneUi, frame: State<MorphFrame>, slots: SceneSlots, lift: Animatable<Float, *>, spatial: Boolean) {
    val colors = FocalTheme.colors
    val storage = scene.storageFull
    val text = when {
        storage -> stringResource(R.string.paused_storage)
        scene.speech == SpeechAvailability.PREPARING -> stringResource(R.string.state_preparing)
        scene.speech == SpeechAvailability.UNAVAILABLE -> stringResource(R.string.state_later)
        else -> return
    }
    val density = LocalDensity.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(text) {
        shown.snapTo(0f)
        shown.animateTo(1f, FocalMotion.fadeFast())
    }
    Text(
        text, style = FocalType.meta.copy(fontWeight = FocalWeight.Medium), color = colors.sceneRecede, textAlign = TextAlign.Center, maxLines = 2,
        modifier = Modifier
            .placeCentred(slots.instrumentWidth, { frame.value.timerCentre.x }) {
                val f = frame.value
                val gap = with(density) { lerp(SceneMetrics.startTimerToState.toPx(), SceneMetrics.liveTimerToState.toPx(), lift.value) }
                f.timerCentre.y + slots.timerLine * f.timerScale / 2 + gap
            }
            .graphicsLayer {
                val f = frame.value
                alpha = shown.value * (if (!spatial) 1f else if (storage) f.sceneTextAlpha else f.lateAlpha)
            }
            .clearAndSetSemantics { },
    )
}

/**
 * The hero wave (spec 3.2, 4.21.4): it travels from the stage's slot and grows (56 to 136 or 176 dp), drawn from
 * sprites in flight and in its API's live tier at rest. Paused, it rests at the full profile at 40 % with the glow
 * off, and the pause badge rides its bottom-right, growing 22 to 28 dp. In T1′e it wakes from the resting level in
 * the scene's dress as the material arrives.
 */
@Composable
private fun Wave(scene: SceneUi, meter: WaveMeter, frame: State<MorphFrame>, live: LiveSurfaceState, entry: SceneChoreography, spatial: Boolean, kind: SceneEntry) {
    val inFlight by remember(live) { derivedStateOf { live.expansion.value < 1f || live.landingNote != null } }
    val wake = kind == SceneEntry.RecordEmpty && spatial
    NineBars(
        WaveMode.Live(meter, scene.paused), WaveDress.Material,
        Modifier.placeAt { frame.value.wave }.graphicsLayer { alpha = if (wake) live.wake.value else 1f },
        renderer = if (inFlight && spatial) WaveRenderer.inFlight else WaveRenderer.live,
        entry = entry.wave,
    )
    val badge = remember { Animatable(if (scene.paused) 1f else 0f) }
    LaunchedEffect(scene.paused) { badge.animateTo(if (scene.paused) 1f else 0f, FocalMotion.fadeFast()) }
    val density = LocalDensity.current
    PauseBadge(
        diameter = { with(density) { frame.value.badgeDiameter.toDp() } },
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints())
                val c = frame.value.badgeCentre
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((c.x - placeable.width / 2f).roundToInt(), (c.y - placeable.height / 2f).roundToInt())
                }
            }
            .graphicsLayer { alpha = badge.value * (if (wake) live.wake.value else 1f) },
    )
}

/**
 * The timer (spec 4.21.3): laid out once at the scene's size in its reserved box and moved and scaled in the layer
 * (48/76 on the stage to 1), so it never re-measures while it travels. Paused it recedes on `develop`. One merged
 * node with the state: "Recording, 4 minutes 16 seconds" (seconds are not announced as they tick).
 */
@Composable
private fun Timer(scene: SceneUi, frame: State<MorphFrame>, timers: TimerStyles, live: LiveSurfaceState, entry: SceneChoreography, spatial: Boolean, focus: FocusRequester) {
    val colors = FocalTheme.colors
    val paused = remember { Animatable(if (scene.paused) 1f else 0f) }
    LaunchedEffect(scene.paused) { paused.animateTo(if (scene.paused) 1f else 0f, FocalMotion.develop()) }
    val elapsed = spokenDuration(scene.elapsedSeconds)
    val description = when {
        !scene.paused -> stringResource(R.string.a11y_scene_state, elapsed)
        else -> when (scene.pauseReason) {
            PauseReason.CALL -> stringResource(R.string.a11y_scene_state_paused_reason, elapsed, stringResource(R.string.a11y_reason_call))
            PauseReason.SILENCED -> stringResource(R.string.a11y_scene_state_paused_reason, elapsed, stringResource(R.string.a11y_reason_other_app))
            PauseReason.STORAGE -> stringResource(R.string.a11y_scene_state_paused_reason, elapsed, stringResource(R.string.a11y_reason_storage))
            null -> stringResource(R.string.a11y_scene_state_paused, elapsed)
        }
    }
    TimerText(
        scene.elapsedSeconds, timers.scene, timers::sceneWidth,
        color = {
            val p = if (spatial) live.expansion.value else 1f
            lerp(Scene.hi, lerp(colors.stageRecede, colors.sceneRecede, p), paused.value)
        },
        modifier = Modifier
            // At the window's left edge in every layout direction, so the layer's translation lands on the frame's
            // centre: the surface's own (start) alignment would put it at the right edge in a mirrored layout.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val f = frame.value
                val k = f.timerScale
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = k
                scaleY = k
                translationX = f.timerCentre.x - size.width * k / 2
                translationY = f.timerCentre.y - size.height * k / 2
                alpha = entry.timer.value
            }
            .focusRequester(focus)
            .focusable()
            .clearAndSetSemantics { contentDescription = description },
    )
}

/** The chevron (spec 4.21.2): ⌃ in a 48 dp target centred on the clip, "Collapse. Recording continues". */
@Composable
private fun Chevron(frame: State<MorphFrame>, slots: SceneSlots, collapse: () -> Unit) {
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val label = stringResource(R.string.cd_collapse)
    Box(
        Modifier
            .placeAt {
                val f = frame.value
                val half = with(density) { FocalLayout.target.toPx() } / 2
                val top = f.clip.top + slots.chevronTop
                // Over the instrument: the window's middle, or the start half's in two columns, carried with the clip.
                val x = f.clip.left + slots.instrumentX / slots.width * f.clip.width
                Rect(x - half, top, x + half, top + 2 * half)
            }
            .graphicsLayer { alpha = frame.value.lateAlpha }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { collapse(); true }
            }
            .focusRing(FocalShapes.pill, Scene.ink)
            .clickable(source, indication = null, onClick = collapse),
        contentAlignment = Alignment.Center,
    ) { Glyph(FocalIcon.Up, Scene.chevron, size = SceneMetrics.chevronGlyph) }
}

/**
 * "↓ Latest" (spec 4.11, 5.3.3): scrolled back by more than a paragraph, follow stops and this glass pill appears
 * 12 dp above the dock, right edge on the column; a tap scrolls back on `move` and follow resumes.
 */
@Composable
private fun LatestPill(open: Boolean, listState: LazyListState, slots: SceneSlots, alpha: Animatable<Float, *>) {
    val away by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val shown = open && away
    val scale = remember { Animatable(if (shown) 1f else JUMP_START_SCALE) }
    LaunchedEffect(shown) {
        if (shown) {
            launch {
                // Shown again while fading out, it goes on from the size it has.
                if (alpha.value <= 0f) scale.snapTo(JUMP_START_SCALE)
                scale.animateTo(1f, FocalMotion.press())
            }
            alpha.animateTo(1f, FocalMotion.fade())
        } else alpha.animateTo(0f, FocalMotion.fadeFast())
    }
    if (!shown && alpha.value <= 0f) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val label = stringResource(R.string.jump_latest)
    val latest: () -> Unit = { scope.launch { listState.animateScrollToItem(0) } }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints())
                val bottom = slots.latestBottom + (placeable.height - with(density) { SceneMetrics.jumpHeight.toPx() }) / 2
                val rtl = layoutDirection == LayoutDirection.Rtl
                // Its end edge on the text column's (spec 4.11).
                val x = if (rtl) slots.textLeft else slots.textRight - placeable.width
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(x.roundToInt(), (bottom - placeable.height).roundToInt())
                }
            }
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { latest(); true }
            }
            .pressScale(source)
            .clickable(source, indication = null, onClick = latest)
            .padding(vertical = (FocalLayout.target - SceneMetrics.jumpHeight) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .drawWithCache {
                    val outline = FocalShapes.pill.createOutline(size, layoutDirection, this)
                    val highlight = glassHighlight(outline, RingWidth.toPx())
                    onDrawBehind {
                        drawOutline(outline, Scene.glass)
                        drawPath(highlight, Scene.glassHighlight)
                    }
                }
                .padding(start = SceneMetrics.jumpStart, end = SceneMetrics.jumpEnd)
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(vertical = (SceneMetrics.jumpHeight - SceneMetrics.jumpGlyph) / 2)) {
                Glyph(FocalIcon.ArrowDown, Scene.ink, size = SceneMetrics.jumpGlyph)
            }
            Spacer(Modifier.width(SceneMetrics.jumpGap))
            Text(label, style = FocalType.action.copy(fontWeight = FocalWeight.Medium), color = Scene.ink)
        }
    }
}

private const val JUMP_START_SCALE = 0.9f

/**
 * T7: while the scene settles into the dock's pill, its own Pause | Finish in the scene's dress fade with its content
 * (the root dock, now the note's, is under the scene). Decorative: no touch, no semantics.
 */
@Composable
private fun LandingDock(paused: Boolean, live: LiveSurfaceState) {
    val rail = LocalWindowLayout.current.rail
    BottomColumn(
        state = rememberBottomColumnState(),
        dock = { Dock(DockState.Live(paused), remember { DockActions() }, material = { MaterialBounds.Everywhere }, rail = rail) },
        area = LocalDockAreas.current.dock,
        rail = rail,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = live.landingContent.value }
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            },
    )
}

/** Whether a keyboard or a screen reader is in use: focus then moves with the scene (spec 8.6). */
@Composable
fun rememberFocusHandOff(): () -> Boolean {
    val context = LocalContext.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    return remember(context, inputMode) {
        {
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            accessibility?.isTouchExplorationEnabled == true || inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard
        }
    }
}
