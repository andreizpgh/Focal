package app.focal.ui.components

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.focal.core.LevelHistory
import app.focal.core.MeterMath
import app.focal.ui.design.FocalColors
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.Scene
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/** The mark's geometry (spec 3.1, docs/mark.svg), in units of its 54 x 42 box. */
object Mark {
    const val BOX_WIDTH = 54f
    const val BOX_HEIGHT = 42f
    const val BAR_WIDTH = 3.2f
    const val PITCH = 6.2f
    const val FIRST_X = 0.6f
    const val BARS = 9
    const val CENTRE = 4
    /** The glow behind the centre bar (scene only): wider, a unit taller at each end, blurred. */
    const val GLOW_WIDTH = 5.6f
    const val GLOW_EXTEND = 1f
    const val GLOW_SIGMA = 2.6f
    /** Live height = profile x (SILENCE + (1 - SILENCE) x level): silence draws the mark at 22 %. */
    const val SILENCE = 0.22f
    /** The empty library's instrument at rest: 42 % of the profile, still. */
    const val REST = 0.42f
    const val PAUSED_ALPHA = 0.4f
    /** The writing head under reduced motion: the static mark at 60 %. */
    const val WORKING_STATIC_ALPHA = 0.6f
    /** The working sweep: bar i's height = profile x (SWEEP_FLOOR + (1 - SWEEP_FLOOR) x wave(t, i)). */
    const val SWEEP_FLOOR = 0.35f
    const val SWEEP_PERIOD_MS = 1200f
    const val SWEEP_PHASE_STEP = 0.55f
    /** The pause badge's centre in a 56-tall wave box, scaled with the box height (spec 4.22.3). */
    const val BADGE_X = 64f
    const val BADGE_Y = 47f
    const val BADGE_REFERENCE_HEIGHT = 56f

    private val heights = floatArrayOf(7f, 12f, 19f, 30f, 40f, 30f, 19f, 12f, 7f)
    private val alphas = floatArrayOf(.18f, .30f, .50f, .75f, 1f, .75f, .50f, .30f, .18f)
    private val sigmaByDistance = floatArrayOf(0f, .45f, 1f, 1.6f, 2.2f)

    fun height(bar: Int): Float = heights[bar]
    fun alpha(bar: Int): Float = alphas[bar]
    fun distance(bar: Int): Int = abs(bar - CENTRE)
    fun sigma(distance: Int): Float = sigmaByDistance[distance]

    /** The box width for a box [height]. */
    fun width(height: Dp): Dp = height * (BOX_WIDTH / BOX_HEIGHT)
}

/** Sizes the wave's box: [height] tall, 54/42 of it wide. */
fun Modifier.markBox(height: Dp): Modifier = size(Mark.width(height), height)

/** How the bars are coloured: on paper (ink, the centre bar's paper gradient, no glow) or on the material. */
@Immutable
data class WaveDress(val bar: Color, val centreTop: Color, val centreBottom: Color, val glow: Color?) {
    companion object {
        val Material = WaveDress(Scene.waveBar, Scene.waveCentreTop, Scene.waveCentreBottom, Scene.glow)
        fun paper(colors: FocalColors) = WaveDress(colors.ink, colors.centreBarTop, colors.centreBarBottom, glow = null)
    }
}

/** The bar levels a live wave draws (spec 3.3). [advance] runs once per frame; [level] reads bar distance d. */
@Stable
interface WaveMeter {
    /** False for a still meter (previews): the wave draws once and requests no frames. */
    val animated: Boolean
    fun advance(nowMs: Long)
    /** The level (0..1) of the bars at [distance] from the centre; without [history] every bar shows the present. */
    fun level(distance: Int, nowMs: Long, history: Boolean): Float
}

/**
 * The microphone meter: [input] is the latest level (0..1), read once per frame, smoothed with a fast attack and a
 * slow release, kept with its times so the outer bars show the recent past. Allocation-free per frame.
 */
@Stable
class LiveMeter(private val input: () -> Float) : WaveMeter {
    private val history = LevelHistory(HISTORY_SIZE)
    private var smoothed = 0f
    private var lastMs = -1L
    override val animated: Boolean get() = true

    override fun advance(nowMs: Long) {
        // Several waves may share one meter (the stage and the morph): the first advance of a frame counts.
        if (nowMs == lastMs) return
        val target = input()
        smoothed = if (lastMs < 0) target else MeterMath.smooth(smoothed, target, (nowMs - lastMs).toDouble())
        lastMs = nowMs
        history.add(nowMs, smoothed)
    }

    override fun level(distance: Int, nowMs: Long, history: Boolean): Float =
        if (history) this.history.bar(distance, nowMs) else smoothed

    /** The light-up (T1'): the wave starts as the full logo and relaxes to the live level with the release. */
    fun seed(level: Float, nowMs: Long) {
        smoothed = level
        lastMs = nowMs
        history.clear()
        history.add(nowMs, level)
    }

    private companion object { const val HISTORY_SIZE = 64 }
}

/** Fixed levels by bar distance (0 = centre): previews, screenshots and tests. */
@Immutable
class FixedMeter(private val levels: FloatArray) : WaveMeter {
    init { require(levels.size == MeterMath.SIDE_BARS + 1) }
    override val animated: Boolean get() = false
    override fun advance(nowMs: Long) = Unit
    override fun level(distance: Int, nowMs: Long, history: Boolean): Float = levels[if (history) distance else 0]
}

/**
 * How a live wave arrives (spec 3.2 "entering", T1′, T1′e): pair d of bars arrives with [pair] (0..1, read in the
 * draw phase); the glow with [glow]. With [restLevel] null the bars arrive by alpha (the light-up, and on API 31+ the
 * outer bars' blur grows with it); otherwise they rise from that share of the profile to the live level (the resting
 * instrument waking up).
 */
@Stable
interface WaveEntry {
    fun pair(distance: Int): Float
    val glow: Float
    val restLevel: Float?
}

/** What the bars show (spec 3.2). */
@Immutable
sealed interface WaveMode {
    /** The full mark profile, still (footer, rationale sheet, gap rows). */
    data object Static : WaveMode
    /** The empty library's instrument at rest: 42 % of the profile, still, never the full mark. */
    data object Rest : WaveMode
    /** The microphone level. Paused eases to the full profile at 40 % with the glow off. */
    data class Live(val meter: WaveMeter, val paused: Boolean = false) : WaveMode
    /** The writing head's sweep. [frozenAtMs] pins the phase (previews). */
    data class Working(val paused: Boolean = false, val frozenAtMs: Long? = null) : WaveMode
}

/**
 * How the outer bars blur (spec 3.4), chosen once per process by API level. In flight (the morph) API 28+ draws from
 * sprites, so a drag never creates a RenderEffect per frame; both look identical at rest.
 */
enum class WaveRenderer {
    /** API 31+: each distance pair in its own RenderEffect layer, the glow in a fifth. */
    Layers,
    /** API 28+: blurred capsules rendered once in software with BlurMaskFilter and drawn as tinted slices. */
    Sprites,
    /** API 26-27: alpha only, no glow. */
    AlphaOnly;

    companion object {
        val live: WaveRenderer = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Layers
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Sprites
            else -> AlphaOnly
        }
        val inFlight: WaveRenderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Sprites else AlphaOnly
    }
}

/**
 * The nine bars. The caller sizes the box (`Modifier.markBox`). Live and working waves read their level in the draw
 * phase from a frame loop that runs only while the lifecycle is STARTED, so nothing recomposes per frame. Every wave
 * is hidden from accessibility: the merged node next to it carries the meaning.
 */
@Composable
fun NineBars(
    mode: WaveMode,
    dress: WaveDress,
    modifier: Modifier = Modifier,
    renderer: WaveRenderer = WaveRenderer.live,
    entry: WaveEntry? = null,
) {
    // Drawing only, with no semantics of its own: accessibility services never see it, and nothing leaks into the
    // merged stage or timer node that speaks the state (a hidden child would hide that whole node).
    when (mode) {
        WaveMode.Static -> StillBars(1f, dress, modifier)
        WaveMode.Rest -> StillBars(Mark.REST, dress, modifier)
        is WaveMode.Live -> MovingBars(mode, mode.paused, dress, modifier, renderer, entry)
        is WaveMode.Working -> MovingBars(mode, mode.paused, dress, modifier, renderer, null)
    }
}

/**
 * Per-frame values of a moving wave: heights in units of the mark's box, the alpha factor, each distance pair's
 * arrival alpha and the glow's alpha.
 */
private class WaveFrame {
    val heights = FloatArray(Mark.BARS)
    val pairs = FloatArray(Mark.CENTRE + 1) { 1f }
    var alpha = 1f
    var glow = 1f

    /** The inputs these values were computed from: every layer of a frame asks, and only the first one computes. */
    private var tick = Long.MIN_VALUE
    private var profile = Float.NaN
    private var dim = Float.NaN
    private val entry = FloatArray(Mark.CENTRE + ENTRY_EXTRAS) { Float.NaN }

    /** True when these values were already computed for the same inputs; otherwise records them and returns false. */
    fun current(tick: Long, profile: Float, dim: Float, wave: WaveEntry?): Boolean {
        var same = tick == this.tick && profile == this.profile && dim == this.dim
        this.tick = tick
        this.profile = profile
        this.dim = dim
        for (distance in 0..Mark.CENTRE) same = record(distance, wave?.pair(distance) ?: 1f) && same
        same = record(Mark.CENTRE + 1, wave?.glow ?: 1f) && same
        return record(Mark.CENTRE + 2, wave?.restLevel ?: -1f) && same
    }

    private fun record(slot: Int, value: Float): Boolean = (entry[slot] == value).also { entry[slot] = value }

    private companion object {
        /** Besides each pair's arrival: the entry's glow and its resting level. */
        const val ENTRY_EXTRAS = 3
    }
}

@Composable
private fun MovingBars(mode: WaveMode, paused: Boolean, dress: WaveDress, modifier: Modifier, renderer: WaveRenderer, entry: WaveEntry?) {
    val reduced = LocalReducedMotion.current
    val frame = remember { WaveFrame() }
    val tick = remember { mutableLongStateOf(0L) }
    // Heights ease to the full profile on `move`; alpha and glow follow on `fade`. Read in draw only.
    val toProfile = remember { Animatable(if (paused) 1f else 0f) }
    val dim = remember { Animatable(if (paused) 1f else 0f) }
    LaunchedEffect(paused) {
        launch { toProfile.animateTo(if (paused) 1f else 0f, FocalMotion.move()) }
        dim.animateTo(if (paused) 1f else 0f, FocalMotion.fade())
    }
    val animated = when (mode) {
        is WaveMode.Live -> mode.meter.animated
        is WaveMode.Working -> mode.frozenAtMs == null && !reduced
        else -> false
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (animated) LaunchedEffect(mode, reduced, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // A paused wave is still once it has eased to the full profile: no frames until it resumes.
            while (!paused || toProfile.isRunning || toProfile.value < 1f) withFrameNanos { nanos ->
                val nowMs = nanos / NANOS_PER_MS
                if (mode is WaveMode.Live) mode.meter.advance(nowMs)
                tick.longValue = nowMs
            }
        }
    }
    val update: () -> Unit = update@{
        val nowMs = tick.longValue
        val profile = toProfile.value
        // Each layer of a frame runs this (so each one redraws with the tick); the values are computed once.
        if (frame.current(nowMs, profile, dim.value, entry)) return@update
        val rest = entry?.restLevel
        for (bar in 0 until Mark.BARS) {
            val full = Mark.height(bar)
            val moving = when (mode) {
                is WaveMode.Live -> full * (Mark.SILENCE + (1 - Mark.SILENCE) * mode.meter.level(Mark.distance(bar), nowMs, history = !reduced))
                is WaveMode.Working -> if (reduced) full else full * sweep(mode.frozenAtMs ?: nowMs, bar)
                else -> full
            }
            val shown = moving + (full - moving) * profile
            // Waking from rest: pair d rises from the resting height to the live one as it arrives.
            frame.heights[bar] = if (rest == null) shown else {
                val from = full * rest
                from + (shown - from) * entry.pair(Mark.distance(bar))
            }
        }
        for (distance in 0..Mark.CENTRE) frame.pairs[distance] = if (entry == null || rest != null) 1f else entry.pair(distance)
        val still = mode is WaveMode.Working && reduced
        frame.alpha = (1 - (1 - Mark.PAUSED_ALPHA) * dim.value) * (if (still) Mark.WORKING_STATIC_ALPHA else 1f)
        frame.glow = (if (mode is WaveMode.Live) 1 - dim.value else 0f) * (entry?.glow ?: 1f)
    }
    val painter = remember(dress) { BarPainter(dress) }
    val framed = modifier.preferredFrameRate(FrameRateCategory.Normal)
    when (renderer) {
        WaveRenderer.Layers -> LayeredBars(frame, update, painter, framed, entry)
        WaveRenderer.Sprites -> Spacer(framed.drawBehind { update(); painter.drawSprites(this, frame) })
        WaveRenderer.AlphaOnly -> Spacer(framed.drawBehind { update(); painter.drawPlain(this, frame) })
    }
}

private const val NANOS_PER_MS = 1_000_000L

private fun sweep(timeMs: Long, bar: Int): Float {
    val wave = (sin(2 * PI * timeMs / Mark.SWEEP_PERIOD_MS - Mark.SWEEP_PHASE_STEP * bar).toFloat() + 1) / 2
    return Mark.SWEEP_FLOOR + (1 - Mark.SWEEP_FLOOR) * wave
}

/**
 * API 31+: pairs d = 4..1, the glow and the sharp centre bar, each in its own layer. Blurred layers are enlarged by
 * the blur's reach so the RenderEffect never clips. Every layer runs [update], so each one subscribes to the frame
 * tick and redraws on its own; the first one in a frame computes the values, the others find them current.
 */
@Composable
private fun LayeredBars(frame: WaveFrame, update: () -> Unit, painter: BarPainter, modifier: Modifier, entry: WaveEntry?) {
    Box(modifier) {
        for (distance in Mark.CENTRE downTo 1) {
            // During a light-up the outer bars' blur grows with their arrival (spec 3.4, API 31+): only then does the
            // layer read a changing value, so a wave at rest never re-creates its RenderEffect.
            val growth: (() -> Float)? = if (entry == null || entry.restLevel != null) null else { { entry.pair(distance) } }
            BlurredLayer(sigmaUnits = Mark.sigma(distance), growth = growth) { margin -> update(); painter.drawPair(this, frame, distance, margin) }
        }
        if (painter.dress.glow != null) {
            BlurredLayer(sigmaUnits = Mark.GLOW_SIGMA) { margin -> update(); painter.drawGlow(this, frame, margin) }
        }
        Spacer(Modifier.matchParentSize().drawBehind { update(); painter.drawCentre(this, frame, 0f) })
    }
}

/** The extra border of a blurred layer, in pixels, measured once per layout and read by its layer and draw. */
private class LayerMargin { var px = 0 }

/** A child layer as large as the box plus three blur sigmas each side, blurred by a RenderEffect. */
@Composable
private fun BlurredLayer(sigmaUnits: Float, growth: (() -> Float)? = null, draw: DrawScope.(marginPx: Float) -> Unit) {
    val margin = remember { LayerMargin() }
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val unit = constraints.maxHeight / Mark.BOX_HEIGHT
                margin.px = ceil(BLUR_REACH * sigmaUnits * unit).toInt()
                val placeable = measurable.measure(
                    Constraints.fixed(constraints.maxWidth + 2 * margin.px, constraints.maxHeight + 2 * margin.px),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-margin.px, -margin.px) }
            }
            .graphicsLayer {
                val unit = (size.height - 2 * margin.px) / Mark.BOX_HEIGHT
                val radius = blurRadius(sigmaUnits * unit * (growth?.invoke() ?: 1f))
                renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
            }
            .drawBehind { draw(margin.px.toFloat()) },
    )
}

/** A Gaussian reaches about three sigmas. */
private const val BLUR_REACH = 3f

/** Skia turns a blur radius into sigma = 0.57735 x radius + 0.5; this is its inverse (spec 3.4). */
internal fun blurRadius(sigmaPx: Float): Float = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0f)

/**
 * Draws bars with reusable native objects: a gradient shader moved by its local matrix for the centre bar, tint
 * filters for sprites. Nothing is allocated per frame.
 */
private class BarPainter(val dress: WaveDress) {
    /** Filtered, so sprites drawn smaller than they were rendered stay smooth. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, 1f, dress.centreTop.toArgb(), dress.centreBottom.toArgb(), Shader.TileMode.CLAMP)
    }
    private val matrix = Matrix()
    private val rect = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val barTint = PorterDuffColorFilter(dress.bar.copy(alpha = 1f).toArgb(), PorterDuff.Mode.SRC_IN)
    private val glowTint = dress.glow?.let { PorterDuffColorFilter(it.copy(alpha = 1f).toArgb(), PorterDuff.Mode.SRC_IN) }

    /** The two bars at [distance] from the centre, shifted by [margin] (the blur layer's extra border). */
    fun drawPair(scope: DrawScope, frame: WaveFrame, distance: Int, margin: Float) = with(scope) {
        val unit = (size.height - 2 * margin) / Mark.BOX_HEIGHT
        drawIntoCanvas { canvas ->
            plainBar(canvas.nativeCanvas, Mark.CENTRE - distance, frame, unit, margin)
            plainBar(canvas.nativeCanvas, Mark.CENTRE + distance, frame, unit, margin)
        }
    }

    fun drawCentre(scope: DrawScope, frame: WaveFrame, margin: Float) = with(scope) {
        val unit = (size.height - 2 * margin) / Mark.BOX_HEIGHT
        drawIntoCanvas { canvas -> centreBar(canvas.nativeCanvas, frame, unit, margin) }
    }

    fun drawGlow(scope: DrawScope, frame: WaveFrame, margin: Float) = with(scope) {
        val glow = dress.glow ?: return@with
        if (frame.glow <= 0f) return@with
        val unit = (size.height - 2 * margin) / Mark.BOX_HEIGHT
        val height = (frame.heights[Mark.CENTRE] + 2 * Mark.GLOW_EXTEND) * unit
        val centreX = margin + (Mark.FIRST_X + Mark.PITCH * Mark.CENTRE + Mark.BAR_WIDTH / 2) * unit
        val centreY = size.height / 2
        val width = Mark.GLOW_WIDTH * unit
        rect.set(centreX - width / 2, centreY - height / 2, centreX + width / 2, centreY + height / 2)
        paint.colorFilter = null
        paint.shader = null
        paint.color = glow.toArgb()
        paint.alpha = (glow.alpha * frame.alpha * frame.glow * MAX_ALPHA).roundToInt()
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(rect, width / 2, width / 2, paint) }
    }

    /** API 26-27 and any wave without blur: nine rounded bars at their alphas. */
    fun drawPlain(scope: DrawScope, frame: WaveFrame) = with(scope) {
        val unit = size.height / Mark.BOX_HEIGHT
        drawIntoCanvas { canvas ->
            for (bar in 0 until Mark.BARS) {
                if (bar == Mark.CENTRE) centreBar(canvas.nativeCanvas, frame, unit, 0f) else plainBar(canvas.nativeCanvas, bar, frame, unit, 0f)
            }
        }
    }

    /** API 28+ and every wave in flight: blurred bars and the glow drawn from cached software-blurred sprites. */
    fun drawSprites(scope: DrawScope, frame: WaveFrame) = with(scope) {
        val unit = size.height / Mark.BOX_HEIGHT
        val centreY = size.height / 2
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val glow = dress.glow
            if (glow != null && frame.glow > 0f) {
                val centreX = (Mark.FIRST_X + Mark.PITCH * Mark.CENTRE + Mark.BAR_WIDTH / 2) * unit
                paint.shader = null
                paint.colorFilter = glowTint
                paint.alpha = (glow.alpha * frame.alpha * frame.glow * MAX_ALPHA).roundToInt()
                drawSprite(native, centreX, centreY, unit, Mark.GLOW_WIDTH, frame.heights[Mark.CENTRE] + 2 * Mark.GLOW_EXTEND, Mark.GLOW_SIGMA)
            }
            for (bar in 0 until Mark.BARS) {
                if (bar == Mark.CENTRE) continue
                val centreX = (Mark.FIRST_X + Mark.PITCH * bar + Mark.BAR_WIDTH / 2) * unit
                paint.shader = null
                paint.colorFilter = barTint
                paint.alpha = (dress.bar.alpha * Mark.alpha(bar) * frame.alpha * frame.pairs[Mark.distance(bar)] * MAX_ALPHA).roundToInt()
                drawSprite(native, centreX, centreY, unit, Mark.BAR_WIDTH, frame.heights[bar], Mark.sigma(Mark.distance(bar)))
            }
            centreBar(native, frame, unit, 0f)
        }
    }

    private fun plainBar(canvas: Canvas, bar: Int, frame: WaveFrame, unit: Float, margin: Float) {
        val height = frame.heights[bar] * unit
        val left = margin + (Mark.FIRST_X + Mark.PITCH * bar) * unit
        val width = Mark.BAR_WIDTH * unit
        val centreY = margin + Mark.BOX_HEIGHT * unit / 2
        rect.set(left, centreY - height / 2, left + width, centreY + height / 2)
        paint.shader = null
        paint.colorFilter = null
        paint.color = dress.bar.toArgb()
        paint.alpha = (dress.bar.alpha * Mark.alpha(bar) * frame.alpha * frame.pairs[Mark.distance(bar)] * MAX_ALPHA).roundToInt()
        canvas.drawRoundRect(rect, width / 2, width / 2, paint)
    }

    /** The sharp centre bar with its vertical gradient spanning the bar's own height (the mark's stops). */
    private fun centreBar(canvas: Canvas, frame: WaveFrame, unit: Float, margin: Float) {
        val height = frame.heights[Mark.CENTRE] * unit
        val left = margin + (Mark.FIRST_X + Mark.PITCH * Mark.CENTRE) * unit
        val width = Mark.BAR_WIDTH * unit
        val top = margin + Mark.BOX_HEIGHT * unit / 2 - height / 2
        matrix.setScale(1f, height.coerceAtLeast(1f))
        matrix.postTranslate(0f, top)
        centrePaint.shader.setLocalMatrix(matrix)
        centrePaint.alpha = (frame.alpha * frame.pairs[0] * MAX_ALPHA).roundToInt()
        rect.set(left, top, left + width, top + height)
        canvas.drawRoundRect(rect, width / 2, width / 2, centrePaint)
    }

    /**
     * A blurred capsule, [widthUnits] by [heightUnits] of the mark's units and blurred by [sigmaUnits], centred on
     * ([centreX], [centreY]) at [unit] pixels per unit. Its sprite is rendered once at a reference scale and drawn
     * scaled: a Gaussian blur scales with its shape, so one sprite serves every size a wave passes through and the
     * morph never renders a sprite per frame. Tall capsules are three slices of one sprite (top half, its middle row
     * stretched, bottom half): exact, because a blurred capsule's middle rows are identical. Short ones use a sprite
     * per half unit of height.
     */
    private fun drawSprite(canvas: Canvas, centreX: Float, centreY: Float, unit: Float, widthUnits: Float, heightUnits: Float, sigmaUnits: Float) {
        val sprite = BarSprites.get(widthUnits, heightUnits, sigmaUnits)
        val scale = unit / sprite.unit
        val margin = sprite.margin * scale
        val left = centreX - widthUnits * unit / 2 - margin
        val right = centreX + widthUnits * unit / 2 + margin
        val top = centreY - heightUnits * unit / 2 - margin
        val bottom = centreY + heightUnits * unit / 2 + margin
        val bitmap = sprite.bitmap
        if (sprite.tall) {
            val middle = sprite.middleRow
            val lowerRows = bitmap.height - middle - 1
            // The two joins sit on whole pixels. At a fractional join both slices would antialias the same row with
            // partial coverage, and two partial coverages composite to less than one: a darker hairline across the
            // bright bar at each join (spec 3.4: slicing is exact and invisible).
            val upperEnd = (top + middle * scale).roundToInt().toFloat()
            val lowerStart = (bottom - lowerRows * scale).roundToInt().toFloat().coerceAtLeast(upperEnd)
            src.set(0, 0, bitmap.width, middle)
            dst.set(left, top, right, upperEnd)
            canvas.drawBitmap(bitmap, src, dst, paint)
            src.set(0, middle + 1, bitmap.width, bitmap.height)
            dst.set(left, lowerStart, right, bottom)
            canvas.drawBitmap(bitmap, src, dst, paint)
            src.set(0, middle, bitmap.width, middle + 1)
            dst.set(left, upperEnd, right, lowerStart)
            if (dst.height() > 0f) canvas.drawBitmap(bitmap, src, dst, paint)
        } else {
            src.set(0, 0, bitmap.width, bitmap.height)
            dst.set(left, top, right, bottom)
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
    }

    private companion object { const val MAX_ALPHA = 255f }
}

/**
 * Software-blurred capsule sprites (ALPHA_8, tinted when drawn), cached for the process. They are rendered at the
 * largest wave's scale (the scene's 176 dp hero) and only ever drawn at that size or smaller, so scaling never
 * softens them; keyed in the mark's units, the same few serve the stage, the morph and the scene.
 */
private object BarSprites {
    class Sprite(val bitmap: Bitmap, val unit: Float, val margin: Int, val tall: Boolean, val middleRow: Int)

    private data class Key(val widthTenths: Int, val sigmaTenths: Int, val heightBucket: Int)

    private val cache = LruCache<Key, Sprite>(MAX_SPRITES)
    /** Short capsules of four distances and the glow, at half-unit heights, fit with room to spare. */
    private const val MAX_SPRITES = 96
    private const val TALL = -1
    private const val BUCKETS_PER_UNIT = 2
    private const val TENTHS = 10f
    /** The hero wave's box height, in dp: the scale sprites are rendered at. */
    private const val REFERENCE_BOX_DP = 176f
    /** Room between the slices of a tall capsule, in units. */
    private const val TALL_SLACK = 0.25f

    private val referenceUnit: Float by lazy { REFERENCE_BOX_DP * Resources.getSystem().displayMetrics.density / Mark.BOX_HEIGHT }

    private fun tallMinimum(widthUnits: Float, sigmaUnits: Float): Float = widthUnits + 2 * BLUR_REACH * sigmaUnits + TALL_SLACK

    fun get(widthUnits: Float, heightUnits: Float, sigmaUnits: Float): Sprite {
        val tall = heightUnits >= tallMinimum(widthUnits, sigmaUnits)
        val bucket = if (tall) TALL else ceil(heightUnits * BUCKETS_PER_UNIT).toInt().coerceAtLeast(1)
        val key = Key((widthUnits * TENTHS).roundToInt(), (sigmaUnits * TENTHS).roundToInt(), bucket)
        return cache.get(key) ?: render(widthUnits, sigmaUnits, if (tall) tallMinimum(widthUnits, sigmaUnits) else bucket.toFloat() / BUCKETS_PER_UNIT, tall)
            .also { cache.put(key, it) }
    }

    private fun render(widthUnits: Float, sigmaUnits: Float, capsuleUnits: Float, tall: Boolean): Sprite {
        val unit = referenceUnit
        val width = widthUnits * unit
        val sigma = sigmaUnits * unit
        val capsuleHeight = ceil(capsuleUnits * unit).toInt()
        val margin = ceil(BLUR_REACH * sigma).toInt() + 1
        val bitmap = createBitmap(ceil(width).toInt() + 2 * margin, capsuleHeight + 2 * margin, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            val radius = blurRadius(sigma)
            if (radius > 0f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        val left = (bitmap.width - width) / 2
        Canvas(bitmap).drawRoundRect(
            RectF(left, margin.toFloat(), left + width, (margin + capsuleHeight).toFloat()), width / 2, width / 2, paint,
        )
        return Sprite(bitmap, unit, margin, tall, middleRow = margin + capsuleHeight / 2)
    }
}

/**
 * Still instances (the empty library's resting bars, the settings footer, the rationale sheet, gap rows): rasterised
 * once per size, level and dress in software, where BlurMaskFilter blurs on every API level, and cached.
 */
@Composable
private fun StillBars(level: Float, dress: WaveDress, modifier: Modifier) {
    Spacer(
        modifier.drawBehind {
            val heightPx = size.height.roundToInt()
            if (heightPx <= 0) return@drawBehind
            val raster = StillMarks.get(heightPx, level, dress)
            drawImage(raster.image, topLeft = Offset(-raster.margin, -raster.margin))
        },
    )
}

private object StillMarks {
    class Raster(val image: ImageBitmap, val margin: Float)

    private data class Key(val height: Int, val level: Float, val dress: WaveDress)

    private val cache = LruCache<Key, Raster>(MAX_RASTERS)
    private const val MAX_RASTERS = 16

    fun get(heightPx: Int, level: Float, dress: WaveDress): Raster {
        val key = Key(heightPx, level, dress)
        return cache.get(key) ?: render(heightPx, level, dress).also { cache.put(key, it) }
    }

    private fun render(heightPx: Int, level: Float, dress: WaveDress): Raster {
        val unit = heightPx / Mark.BOX_HEIGHT
        val margin = ceil(BLUR_REACH * Mark.sigma(Mark.CENTRE) * unit).toInt() + 1
        val bitmap = createBitmap(ceil(Mark.BOX_WIDTH * unit).toInt() + 2 * margin, heightPx + 2 * margin)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centreY = margin + heightPx / 2f
        for (bar in 0 until Mark.BARS) {
            val height = Mark.height(bar) * level * unit
            val left = margin + (Mark.FIRST_X + Mark.PITCH * bar) * unit
            val width = Mark.BAR_WIDTH * unit
            val rect = RectF(left, centreY - height / 2, left + width, centreY + height / 2)
            paint.reset()
            paint.isAntiAlias = true
            if (bar == Mark.CENTRE) {
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, dress.centreTop.toArgb(), dress.centreBottom.toArgb(), Shader.TileMode.CLAMP)
            } else {
                paint.color = dress.bar.toArgb()
                paint.alpha = (dress.bar.alpha * Mark.alpha(bar) * 255).roundToInt()
                val radius = blurRadius(Mark.sigma(Mark.distance(bar)) * unit)
                if (radius > 0f) paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(rect, width / 2, width / 2, paint)
        }
        return Raster(bitmap.asImageBitmap(), margin.toFloat())
    }
}
