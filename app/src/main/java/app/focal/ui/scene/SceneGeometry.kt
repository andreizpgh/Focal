package app.focal.ui.scene

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.focal.ui.components.DockMetrics
import app.focal.ui.components.Mark
import app.focal.ui.components.PauseBadgeMetrics
import app.focal.ui.components.railDock
import app.focal.ui.design.FocalGestures
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalType
import app.focal.ui.design.lineScale
import app.focal.ui.design.smoothstep
import kotlin.math.max
import kotlin.math.min

/** The scene's layout constants (spec 4.21.7). Positions are built from the insets, never from ref coordinates. */
object SceneMetrics {
    /** The hero wave: 176 dp before the first text, 136 with text; 120 / 88 from font scale 1.5 (compact). */
    val startWave = 176.dp
    val liveWave = 136.dp
    val startWaveCompact = 120.dp
    val liveWaveCompact = 88.dp
    const val COMPACT_FONT = 1.5f

    /** With text, the wave starts this far under the status bar (ref 116 = 40 + 76). */
    val liveWaveFromStatus = 76.dp

    /**
     * Before text, the start hero's centre sits this far above the centre of the free paper (status bar to dock top):
     * a derived optical offset (spec 4.22.5). It is the empty library's rule (its group centred 6.7 dp above the free
     * paper's centre, the instrument's centre 98.8 dp above the group's) at 1.0x, so the scene's start hero lands on
     * the resting instrument's centre (ref 324) and Record from the empty library is a light-up in place.
     */
    val startHeroLift = 105.5.dp

    /** The vertical rhythm with text: wave, 16, timer (84 dp line at 76 sp), 2, state slot (20), 24, text region. */
    val liveWaveToTimer = FocalGrid.x16
    val liveTimerToState = FocalGrid.x2

    /** Before text: wave, 40, timer, 4, state slot; the hint 108 dp under the slot (ref 668). */
    val startWaveToTimer = FocalGrid.x40
    val startTimerToState = FocalGrid.x4
    val stateHint = 108.dp
    val stateSlot = FocalGrid.x20
    val stateToText = FocalGrid.x24

    /** The text region ends this far above the dock's top (ref 795). */
    val textToDock = FocalGrid.x24
    val paragraphGap = FocalGrid.x16
    /** The region's top alpha mask in the scene (2.6). */
    val textMask = 128.dp

    /** The chevron's 48 dp target sits this far under the status bar (ref 48-96, glyph 22 dp). */
    val chevronFromStatus = FocalGrid.x8
    val chevronGlyph = 22.dp

    /** The "Latest" jump pill: 40 dp visual in a 48 dp target, 12 dp above the dock (ref 767-807, spec 4.11). */
    val jumpHeight = FocalGrid.x40
    val jumpAboveDock = FocalGrid.x12
    /** While the jump pill shows, the text region ends this far above it, fading out over [jumpFade]. */
    val jumpClearance = FocalGrid.x8
    val jumpFade = FocalGrid.x16
    val jumpStart = FocalGrid.x12
    val jumpEnd = FocalGrid.x16
    val jumpGlyph = 18.dp
    val jumpGap = FocalGrid.x8
}

/** The stage's live slots, card-local (spec 4.22.3): where the morph starts from. */
object StageLiveMetrics {
    val inset = FocalGrid.x20
    val wave = FocalGrid.x56
    /** The timer and the text region start this far after the wave's box and under the head row. */
    val gap = FocalGrid.x16
    val mask = 34.dp
    val paragraphGap = FocalGrid.x12
}

/** One of the scene's two instrument layouts: before text or with text, in window pixels. */
@Immutable
data class Instrument(val waveTop: Float, val waveHeight: Float, val timerTop: Float, val stateTop: Float) {
    fun waveCentreY(): Float = waveTop + waveHeight / 2

    companion object {
        fun lerp(a: Instrument, b: Instrument, t: Float) = Instrument(
            lerp(a.waveTop, b.waveTop, t), lerp(a.waveHeight, b.waveHeight, t),
            lerp(a.timerTop, b.timerTop, t), lerp(a.stateTop, b.stateTop, t),
        )
    }
}

/**
 * The scene at rest (spec 4.21.7), in window pixels: the instrument before text ([start]) and with text ([live]),
 * the hint, the text region, the chevron and the dock row. Built from the window, the insets and the timer's line
 * height; on the 412 x 915 reference frame it reproduces every ref value of the spec.
 *
 * In a short window ([twoColumn], spec 5.14) the instrument (wave, timer, state) is centred on the start half of the
 * page beside the rail and the text region runs down the end half; the instrument has no room to lift, so it keeps
 * its with-text layout from the start, and the hint waits level with it on the text's half.
 */
@Immutable
data class SceneSlots(
    val width: Float,
    val height: Float,
    val statusTop: Float,
    /** The page's content column (where the stage is). */
    val columnLeft: Float,
    val columnRight: Float,
    val start: Instrument,
    val live: Instrument,
    val timerLine: Float,
    val stateHeight: Float,
    val hintTop: Float,
    val textTop: Float,
    val textBottom: Float,
    val chevronTop: Float,
    val dockTop: Float,
    val dockBottom: Float,
    /** The dock's pill slot (x 92-388; the rail's lower circle): where the scene settles on Finish (T7). */
    val pill: Rect,
    val corner: Float,
    val stageMask: Float,
    val sceneMask: Float,
    val stageBadge: Float,
    val sceneBadge: Float,
    /** The instrument's vertical axis: the window's middle, or the start half's in two columns. */
    val instrumentX: Float = width / 2,
    /** The text region's column, and the hint's centre line over it. */
    val textLeft: Float = columnLeft,
    val textRight: Float = columnRight,
    val hintX: Float = instrumentX,
    /** The jump pill's bottom edge ("↓ Latest", 12 dp above the dock; the text region's bottom in two columns). */
    val latestBottom: Float = Float.NaN,
    /** The rail's edge: a touch beyond it (toward the rail) never folds the scene. NaN without a rail. */
    val railEdge: Float = Float.NaN,
    val railAtLeft: Boolean = false,
    val twoColumn: Boolean = false,
    /** How wide a line under the instrument (the state slot) may run. */
    val instrumentWidth: Float = columnRight - columnLeft,
) {
    val window: Rect get() = Rect(0f, 0f, width, height)
    val centreX: Float get() = width / 2

    fun instrument(lift: Float): Instrument = if (twoColumn) live else Instrument.lerp(start, live, lift)

    /** Whether a drag that starts at ([x], [y]) may fold the scene: above the dock row, or off the rail. */
    fun mayFold(x: Float, y: Float, foldLimit: Float): Boolean = when {
        railEdge.isNaN() -> y < foldLimit
        railAtLeft -> x > railEdge
        else -> x < railEdge
    }

    companion object {
        /**
         * The slots for a window [width] x [height]. [area] is the page area the scene's column is centred in (inside
         * the side insets, beside the rail); [landing] the area whose dock Finish settles into (T7: the note's pane).
         */
        fun of(
            width: Float,
            height: Float,
            statusTop: Float,
            navigationBottom: Float,
            timerLine: Float,
            compact: Boolean,
            density: Density,
            area: ClosedFloatingPointRange<Float> = 0f..width,
            landing: ClosedFloatingPointRange<Float> = area,
            rail: Boolean = false,
            rtl: Boolean = false,
        ): SceneSlots = with(density) {
            fun columnOf(span: ClosedFloatingPointRange<Float>): Pair<Float, Float> {
                val w = span.endInclusive - span.start
                val column = min(w - 2 * FocalLayout.gutter.toPx(), FocalLayout.contentMaxWidth.toPx()).coerceAtLeast(0f)
                val left = span.start + (w - column) / 2
                return left to left + column
            }
            val (columnLeft, columnRight) = columnOf(area)
            val baseline = height - navigationBottom - FocalLayout.dockInset.toPx()
            val state = SceneMetrics.stateSlot.toPx()
            val liveWave = (if (compact) SceneMetrics.liveWaveCompact else SceneMetrics.liveWave).toPx()
            val startWave = (if (compact) SceneMetrics.startWaveCompact else SceneMetrics.startWave).toPx()
            val chevronTop = statusTop + SceneMetrics.chevronFromStatus.toPx()
            if (rail) {
                return@with twoColumns(
                    width, height, statusTop, timerLine, area, rtl, columnLeft, columnRight, baseline, chevronTop,
                    navigationTop = height - navigationBottom, liveWave = liveWave, compactWave = SceneMetrics.liveWaveCompact.toPx(), density,
                )
            }
            val dockBottom = baseline
            val dockTop = dockBottom - DockMetrics.height.toPx()
            val liveTop = statusTop + SceneMetrics.liveWaveFromStatus.toPx()
            val liveTimer = liveTop + liveWave + SceneMetrics.liveWaveToTimer.toPx()
            val liveState = liveTimer + timerLine + SceneMetrics.liveTimerToState.toPx()
            // Before text the hero keeps the full 176 dp box's top, so the compact wave starts where it would.
            val heroCentre = (statusTop + dockTop) / 2 - SceneMetrics.startHeroLift.toPx()
            val startTop = heroCentre - SceneMetrics.startWave.toPx() / 2
            val startTimer = startTop + startWave + SceneMetrics.startWaveToTimer.toPx()
            val startState = startTimer + timerLine + SceneMetrics.startTimerToState.toPx()
            // The pill beside the circle: after it, or before it right to left (spec 8.5).
            val (landingLeft, landingRight) = columnOf(landing)
            val circle = DockMetrics.circle.toPx() + DockMetrics.gap.toPx()
            val pill = if (rtl) Rect(landingLeft, dockTop, landingRight - circle, dockBottom) else Rect(landingLeft + circle, dockTop, landingRight, dockBottom)
            SceneSlots(
                width = width, height = height, statusTop = statusTop,
                columnLeft = columnLeft, columnRight = columnRight,
                start = Instrument(startTop, startWave, startTimer, startState),
                live = Instrument(liveTop, liveWave, liveTimer, liveState),
                timerLine = timerLine, stateHeight = state,
                hintTop = startState + state + SceneMetrics.stateHint.toPx(),
                textTop = liveState + state + SceneMetrics.stateToText.toPx(),
                textBottom = dockTop - SceneMetrics.textToDock.toPx(),
                chevronTop = chevronTop,
                dockTop = dockTop, dockBottom = dockBottom,
                pill = pill,
                corner = FocalShapes.sheetRadius.toPx(),
                stageMask = StageLiveMetrics.mask.toPx() * lineScale(FocalType.stageText), sceneMask = SceneMetrics.textMask.toPx(),
                stageBadge = PauseBadgeMetrics.stage.toPx(), sceneBadge = PauseBadgeMetrics.scene.toPx(),
                instrumentX = (columnLeft + columnRight) / 2,
                latestBottom = dockTop - SceneMetrics.jumpAboveDock.toPx(),
            )
        }

        /** The two-column scene of a short window (spec 5.14): the instrument on the start half, the text on the end. */
        private fun twoColumns(
            width: Float,
            height: Float,
            statusTop: Float,
            timerLine: Float,
            area: ClosedFloatingPointRange<Float>,
            rtl: Boolean,
            columnLeft: Float,
            columnRight: Float,
            baseline: Float,
            chevronTop: Float,
            navigationTop: Float,
            liveWave: Float,
            compactWave: Float,
            density: Density,
        ): SceneSlots = with(density) {
            val middle = (area.start + area.endInclusive) / 2
            val gutter = FocalLayout.gutter.toPx()
            // The start half holds the instrument; the text runs from the middle to the gutter before the rail.
            val instrumentX = if (rtl) (middle + area.endInclusive) / 2 else (area.start + middle) / 2
            val textLeft = if (rtl) area.start + gutter else middle
            val textRight = if (rtl) middle else area.endInclusive - gutter
            val state = SceneMetrics.stateSlot.toPx()
            // The instrument is centred between the chevron and the bottom line; the compact wave if the full one
            // would not fit.
            val top = chevronTop + FocalLayout.target.toPx()
            fun block(wave: Float) = wave + SceneMetrics.liveWaveToTimer.toPx() + timerLine + SceneMetrics.liveTimerToState.toPx() + state
            val wave = if (block(liveWave) <= baseline - top) liveWave else min(liveWave, compactWave)
            val waveTop = max(top, (top + baseline - block(wave)) / 2)
            val timerTop = waveTop + wave + SceneMetrics.liveWaveToTimer.toPx()
            val stateTop = timerTop + timerLine + SceneMetrics.liveTimerToState.toPx()
            val instrument = Instrument(waveTop, wave, timerTop, stateTop)
            val dock = railDock(area.start, area.endInclusive, statusTop, navigationTop, rtl, this)
            val circle = DockMetrics.circle.toPx()
            val textTop = statusTop + FocalLayout.topFade.toPx()
            SceneSlots(
                width = width, height = height, statusTop = statusTop,
                columnLeft = columnLeft, columnRight = columnRight,
                start = instrument, live = instrument,
                timerLine = timerLine, stateHeight = state,
                // The hint's centre line: level with the instrument's middle, over the text's half.
                hintTop = waveTop + block(wave) / 2,
                textTop = textTop,
                textBottom = baseline,
                chevronTop = chevronTop,
                dockTop = dock.top, dockBottom = dock.bottom,
                pill = Rect(dock.left, dock.bottom - circle, dock.right, dock.bottom),
                corner = FocalShapes.sheetRadius.toPx(),
                stageMask = StageLiveMetrics.mask.toPx() * lineScale(FocalType.stageText), sceneMask = SceneMetrics.textMask.toPx(),
                stageBadge = PauseBadgeMetrics.stage.toPx(), sceneBadge = PauseBadgeMetrics.scene.toPx(),
                instrumentX = instrumentX,
                textLeft = textLeft, textRight = textRight,
                hintX = (textLeft + textRight) / 2,
                latestBottom = baseline,
                railEdge = if (rtl) area.start else area.endInclusive,
                railAtLeft = rtl,
                twoColumn = true,
                instrumentWidth = ((area.endInclusive - area.start) / 2 - 2 * gutter).coerceAtLeast(0f),
            )
        }
    }
}

/**
 * Where the morph starts (spec 6.4, "Inputs"): the box A it grows out of and what sits in it at p = 0. For the live
 * stage these are its live slots (the wave at card (20, 20), 56 dp; the timer after it at 48/76 of the scene's size;
 * the text region under them); for the empty library (T1′e) the resting instrument's box, whose bars keep their
 * centre while "0:00" is born in its scene slot.
 */
@Immutable
data class MorphOrigin(
    val bounds: Rect,
    val waveCentre: Offset,
    val waveHeight: Float,
    /** The timer's centre and scale at p = 0. */
    val timerCentre: Offset,
    val timerScale: Float,
    /** The stage typesetting's region, card-relative: its top and the clearance under its bottom. */
    val textTop: Float,
    val textBottomInset: Float,
    val textInset: Float,
    /** A card (the stage) rather than the resting instrument: it has a Dark ring and a typesetting. */
    val card: Boolean,
) {
    companion object {
        /**
         * The live stage at [bounds]: [headHeight] is its head row (the wave and the timer, at least 56 dp tall),
         * [timerWidth] the timer's reserved width at the stage's size, [timerScale] stage size / scene size; the
         * row runs from the card's start edge ([rtl] mirrors it).
         */
        fun stage(bounds: Rect, headHeight: Float, timerWidth: Float, timerScale: Float, rtl: Boolean, density: Density): MorphOrigin = with(density) {
            val inset = StageLiveMetrics.inset.toPx()
            val wave = StageLiveMetrics.wave.toPx() * Mark.BOX_WIDTH / Mark.BOX_HEIGHT
            val gap = StageLiveMetrics.gap.toPx()
            val rowCentre = bounds.top + inset + headHeight / 2
            // The head row starts at the card's start edge: mirrored in RTL.
            fun x(fromStart: Float) = if (rtl) bounds.right - fromStart else bounds.left + fromStart
            MorphOrigin(
                bounds = bounds,
                waveCentre = Offset(x(inset + wave / 2), rowCentre),
                waveHeight = StageLiveMetrics.wave.toPx(),
                timerCentre = Offset(x(inset + wave + gap + timerWidth / 2), rowCentre),
                timerScale = timerScale,
                textTop = inset + headHeight + gap,
                textBottomInset = inset,
                textInset = inset,
                card = true,
            )
        }

        /** The empty library's resting instrument at [box] (T1′e): "0:00" is born at [timerCentre] at 0.92. */
        fun rest(box: Rect, timerCentre: Offset): MorphOrigin = MorphOrigin(
            bounds = box, waveCentre = box.center, waveHeight = box.height,
            timerCentre = timerCentre, timerScale = EMPTY_TIMER_SCALE,
            textTop = 0f, textBottomInset = 0f, textInset = 0f, card = false,
        )

        /** T1′e: "0:00" grows from this scale in its slot (spec 6.1). */
        const val EMPTY_TIMER_SCALE = 0.92f
    }
}

/** Every element of the morph at one p (spec 6.4), in window pixels. */
@Immutable
data class MorphFrame(
    val p: Float,
    val clip: Rect,
    val radius: Float,
    val wave: Rect,
    val badgeCentre: Offset,
    val badgeDiameter: Float,
    val timerCentre: Offset,
    val timerScale: Float,
    val textTop: Float,
    val textBottom: Float,
    val textMask: Float,
    /** Where the stage's typesetting starts: 20 dp inside the clip, so it moves with the card. */
    val stageTextLeft: Float,
    val stageTextAlpha: Float,
    val sceneTextAlpha: Float,
    /** The chevron, the hint and the model condition arrive late (smoothstep(0.7, 1, p)). */
    val lateAlpha: Float,
    val ringAlpha: Float,
    /** Where the material's grain is anchored: the origin's top-left, so it never swims while the clip grows. */
    val grainAnchor: Offset,
) {
    /** The material's bottom edge, which wipes the dock's dress (2.8.6). */
    val edge: Float get() = clip.bottom
}

/** The morph's functions of p (spec 6.4) and its gesture decisions (6.5), as pure functions. */
object Morph {
    fun easeOut(p: Float): Float = 1 - (1 - p) * (1 - p)

    /** The stage's typesetting leaves early, the scene's arrives late: never both at once (Q3). */
    fun stageTextAlpha(p: Float): Float = 1 - smoothstep(STAGE_TEXT_FROM, STAGE_TEXT_TO, p)
    fun sceneTextAlpha(p: Float): Float = smoothstep(SCENE_TEXT_FROM, SCENE_TEXT_TO, p)
    fun lateAlpha(p: Float): Float = smoothstep(LATE_FROM, 1f, p)

    /** Each clip edge is linear in p, so the bottom edge follows the finger exactly. */
    fun clip(p: Float, from: Rect, to: Rect): Rect = Rect(
        lerp(from.left, to.left, p), lerp(from.top, to.top, p), lerp(from.right, to.right, p), lerp(from.bottom, to.bottom, p),
    )

    /**
     * The frame at [p] from [origin] to the scene [slots], the scene's layout being [lift] of the way from before
     * text to with text. The wave's x eases in (smoothstep(0.1, 1, p)) so the timer steps down out of its way first;
     * the timer's y and the text region's top use the ease-out 1 - (1 - p)^2, so neither meets the digits.
     */
    fun frame(p: Float, lift: Float, origin: MorphOrigin, slots: SceneSlots): MorphFrame {
        val clip = clip(p, origin.bounds, slots.window)
        val e = easeOut(p)
        val instrument = slots.instrument(lift)
        val waveHeight = lerp(origin.waveHeight, instrument.waveHeight, p)
        val waveCx = lerp(origin.waveCentre.x, slots.instrumentX, smoothstep(WAVE_X_FROM, 1f, p))
        val waveCy = lerp(origin.waveCentre.y, instrument.waveCentreY(), p)
        val waveWidth = waveHeight * Mark.BOX_WIDTH / Mark.BOX_HEIGHT
        val wave = Rect(waveCx - waveWidth / 2, waveCy - waveHeight / 2, waveCx + waveWidth / 2, waveCy + waveHeight / 2)
        val badgeK = waveHeight / Mark.BADGE_REFERENCE_HEIGHT
        val timerCy = lerp(origin.timerCentre.y, instrument.timerTop + slots.timerLine / 2, e)
        val textTop = lerp(origin.bounds.top + origin.textTop, slots.textTop, e)
        val textBottom = clip.bottom - lerp(origin.textBottomInset, slots.height - slots.textBottom, p)
        return MorphFrame(
            p = p,
            clip = clip,
            radius = slots.corner * (1 - p),
            wave = wave,
            badgeCentre = Offset(wave.left + Mark.BADGE_X * badgeK, wave.top + Mark.BADGE_Y * badgeK),
            badgeDiameter = lerp(slots.stageBadge, slots.sceneBadge, p),
            timerCentre = Offset(lerp(origin.timerCentre.x, slots.instrumentX, p), timerCy),
            timerScale = lerp(origin.timerScale, 1f, p),
            textTop = textTop,
            textBottom = max(textTop, textBottom),
            textMask = lerp(slots.stageMask, slots.sceneMask, p),
            stageTextLeft = clip.left + origin.textInset,
            stageTextAlpha = stageTextAlpha(p),
            sceneTextAlpha = sceneTextAlpha(p),
            lateAlpha = lateAlpha(p),
            ringAlpha = if (origin.card) 1 - p else 0f,
            grainAnchor = origin.bounds.topLeft,
        )
    }

    /** The scene at rest, or crossfading in place under reduced motion: every element at its scene slot. */
    fun rest(lift: Float, slots: SceneSlots): MorphFrame {
        val instrument = slots.instrument(lift)
        val waveWidth = instrument.waveHeight * Mark.BOX_WIDTH / Mark.BOX_HEIGHT
        val wave = Rect(slots.instrumentX - waveWidth / 2, instrument.waveTop, slots.instrumentX + waveWidth / 2, instrument.waveTop + instrument.waveHeight)
        val badgeK = instrument.waveHeight / Mark.BADGE_REFERENCE_HEIGHT
        return MorphFrame(
            p = 1f, clip = slots.window, radius = 0f, wave = wave,
            badgeCentre = Offset(wave.left + Mark.BADGE_X * badgeK, wave.top + Mark.BADGE_Y * badgeK),
            badgeDiameter = slots.sceneBadge,
            timerCentre = Offset(slots.instrumentX, instrument.timerTop + slots.timerLine / 2), timerScale = 1f,
            textTop = slots.textTop, textBottom = slots.textBottom, textMask = slots.sceneMask,
            stageTextLeft = 0f, stageTextAlpha = 0f, sceneTextAlpha = 1f, lateAlpha = 1f, ringAlpha = 0f,
            grainAnchor = Offset.Zero,
        )
    }

    /** T7: the scene's clip contracts from the window into the dock pill's bounds, its corners growing to 28 dp. */
    fun landing(q: Float, slots: SceneSlots): Pair<Rect, Float> = clip(q, slots.window, slots.pill) to slots.corner * q

    /**
     * Where a released drag settles (spec 6.5), from [p0] (p when the drag was claimed) and [p] now, with the finger's
     * [velocity] in dp/s, positive downward: a fling completes in its direction; otherwise a net travel of 40 % commits
     * in its direction (expand from 0: p >= 0.4; fold from 1: p <= 0.6); otherwise the surface returns to the end
     * nearer to [p0].
     */
    fun release(p0: Float, p: Float, velocity: Float): Float = when {
        velocity >= FocalGestures.FLING_DP_PER_S -> 1f
        velocity <= -FocalGestures.FLING_DP_PER_S -> 0f
        p - p0 >= FocalGestures.EXPAND_THRESHOLD - EPSILON -> 1f
        p0 - p >= FocalGestures.EXPAND_THRESHOLD - EPSILON -> 0f
        else -> if (p0 >= HALF) 1f else 0f
    }

    /**
     * Predictive back on the scene (T29): a full back swipe previews exactly the release threshold. The gesture scales
     * p from [from], where it was when the gesture began (1 at rest; less while an expand is still settling), so a back
     * never jumps the surface open.
     */
    fun backPreview(progress: Float, from: Float = 1f, ease: (Float) -> Float): Float =
        from * (1 - FocalGestures.BACK_PREVIEW * ease(progress))

    private const val STAGE_TEXT_FROM = 0.1f
    private const val STAGE_TEXT_TO = 0.35f
    private const val SCENE_TEXT_FROM = 0.37f
    private const val SCENE_TEXT_TO = 0.62f
    private const val LATE_FROM = 0.7f
    private const val WAVE_X_FROM = 0.1f
    private const val HALF = 0.5f
    private const val EPSILON = 1e-4f
}

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
