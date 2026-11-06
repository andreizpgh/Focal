package app.focal.ui.design

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One voice: critically or near-critically damped springs with no visible bounce (spec 2.8). They match
 * `MotionScheme.standard()` value for value (press/move/stage = fast/default/slow spatial, fadeFast/fade/fadeSlow =
 * fast/default/slow effects).
 */
object FocalMotion {
    const val SPATIAL_DAMPING = 0.9f
    const val EFFECTS_DAMPING = 1f
    const val PRESS_STIFFNESS = 1400f
    const val MOVE_STIFFNESS = 700f
    const val STAGE_STIFFNESS = 300f
    const val FADE_FAST_STIFFNESS = 3800f
    const val FADE_STIFFNESS = 1600f
    const val FADE_SLOW_STIFFNESS = 800f
    const val DEVELOP_STIFFNESS = 300f

    /** Press scale 1 to 0.97, lens glide within a line, playhead height, menu scale-in, the Copied check. */
    fun <T> press(): SpringSpec<T> = spring(SPATIAL_DAMPING, PRESS_STIFFNESS)
    /** Bounds and sizes: dock morphs, the feedback pill, sheets, the settings swipe and the peek. */
    fun <T> move(): SpringSpec<T> = spring(SPATIAL_DAMPING, MOVE_STIFFNESS)
    /** The recording surface only: expand, fold, the drag's settle, the settle into the note, the lift. */
    fun <T> stage(): SpringSpec<T> = spring(SPATIAL_DAMPING, STAGE_STIFFNESS)
    /** Icon and label swaps, menu exit, feedback exit, press plates. */
    fun <T> fadeFast(): SpringSpec<T> = spring(EFFECTS_DAMPING, FADE_FAST_STIFFNESS)
    /** Layers, menus, notices, the theme switch. */
    fun <T> fade(): SpringSpec<T> = spring(EFFECTS_DAMPING, FADE_STIFFNESS)
    /** Alpha of arriving text; the glow's arrival. */
    fun <T> fadeSlow(): SpringSpec<T> = spring(EFFECTS_DAMPING, FADE_SLOW_STIFFNESS)
    /** Text colour only: faint to ink, ink to muted, muted to ink. */
    fun <T> develop(): SpringSpec<T> = spring(EFFECTS_DAMPING, DEVELOP_STIFFNESS)

    /** The only tweens in the product (2.8). Expand, fold, settle and stage-page swaps under reduced motion. */
    val reducedCrossfade: TweenSpec<Float> = tween(durationMillis = 150, easing = LinearEasing)
    /** The writing head's working sweep phase. */
    val sweep: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(1200, easing = LinearEasing))
    /** The readiness shimmer's travel; its alpha is sin(pi * u) of the linear progress u. */
    val readyShimmer: TweenSpec<Float> = tween(720, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))

    /**
     * [spec] counted from its trigger (spec 6.1: every "+n" is a time after the trigger). A tap or a key lands between
     * frames, and an animation started by it begins at its own time 0 on the next frame, so that frame shows nothing
     * moved and every later one lags the trigger by a frame. Run from the trigger, the animation is ahead by
     * [FocalTiming.TRIGGER_LEAD_MS] (one frame at 60 Hz): the first frame after the trigger already shows that much of
     * it. Use it for motion a trigger starts, not for motion that follows a value already on screen.
     */
    fun <T> fromTrigger(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = TriggerLead(spec, FocalTiming.TRIGGER_LEAD_MS * NANOS_PER_MS)

    private const val NANOS_PER_MS = 1_000_000L

    /**
     * The spring's step response x(t) from 0 to 1 at rest, for View animations that must speak the same voice (the
     * splash exit). [timeMs] is the time since the start.
     */
    fun springFraction(dampingRatio: Float, stiffness: Float, timeMs: Float): Float {
        if (timeMs <= 0f) return 0f
        val w = sqrt(stiffness)
        val t = timeMs / 1000f
        if (dampingRatio >= 1f) return (1f - exp(-w * t) * (1f + w * t))
        val wd = w * sqrt(1f - dampingRatio * dampingRatio)
        return 1f - exp(-dampingRatio * w * t) * (cos(wd * t) + (dampingRatio * w / wd) * sin(wd * t))
    }

    /** A [TimeInterpolator] that follows a spring over [durationMs] (its settle time to 0.1 %). */
    fun springInterpolator(dampingRatio: Float, stiffness: Float, durationMs: Long): TimeInterpolator =
        TimeInterpolator { input -> springFraction(dampingRatio, stiffness, input * durationMs) }
}

/** An animation spec run [leadNanos] ahead of its own start (FocalMotion.fromTrigger). */
private data class TriggerLead<T>(private val spec: FiniteAnimationSpec<T>, private val leadNanos: Long) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        VectorizedLead(spec.vectorize(converter), leadNanos)
}

private class VectorizedLead<V : AnimationVector>(
    private val inner: VectorizedFiniteAnimationSpec<V>,
    private val leadNanos: Long,
) : VectorizedFiniteAnimationSpec<V> {
    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        inner.getValueFromNanos(playTimeNanos + leadNanos, initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        inner.getVelocityFromNanos(playTimeNanos + leadNanos, initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        (inner.getDurationNanos(initialValue, targetValue, initialVelocity) - leadNanos).coerceAtLeast(0L)
}

/** smoothstep(e0, e1, x): the cubic Hermite step t^2 (3 - 2t) with t = clamp((x - e0) / (e1 - e0)) (spec, "How to read"). */
fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Settle times to 0.1 %, computed from the spring solutions (2.8 table), for motion drawn from a clock. */
object FocalSettle {
    const val FADE_FAST_MS = 150L
    const val FADE_MS = 231L
    const val FADE_SLOW_MS = 326L
    const val MOVE_MS = 317L
}

/**
 * The spec 2.8 springs as functions of the time since their start (ms), settled exactly at their 0.1 % time: for
 * motion drawn from a clock ([TriggerClock], the page changes) rather than run by an Animatable.
 */
object FocalClock {
    fun move(ms: Float): Float = at(FocalMotion.SPATIAL_DAMPING, FocalMotion.MOVE_STIFFNESS, FocalSettle.MOVE_MS, ms)
    fun fadeFast(ms: Float): Float = at(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_FAST_STIFFNESS, FocalSettle.FADE_FAST_MS, ms)
    fun fade(ms: Float): Float = at(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_STIFFNESS, FocalSettle.FADE_MS, ms)
    fun fadeSlow(ms: Float): Float = at(FocalMotion.EFFECTS_DAMPING, FocalMotion.FADE_SLOW_STIFFNESS, FocalSettle.FADE_SLOW_MS, ms)

    private fun at(damping: Float, stiffness: Float, settleMs: Long, ms: Float): Float = when {
        ms <= 0f -> 0f
        ms >= settleMs -> 1f
        else -> FocalMotion.springFraction(damping, stiffness, ms)
    }
}

/**
 * The time since a trigger (a tap, a key) for motion that counts from it even when what it moves is composed a frame
 * or two later (spec 6.1: every "+n" is a time after the trigger). Like FocalMotion.fromTrigger, its first frame
 * counts one frame already; it follows the animator duration scale. [elapsed] reads infinity once [lengthMs] has
 * passed, before the first trigger and when animations are off; [serial] tells one trigger from the next.
 */
@Stable
class TriggerClock(private val scope: CoroutineScope, private val lengthMs: Long) {
    var elapsed by mutableFloatStateOf(Float.POSITIVE_INFINITY)
        private set
    var serial = 0
        private set
    private var job: Job? = null

    /** Call in the tap's handler: the next frame is the first one counted. */
    fun start() {
        job?.cancel()
        serial++
        elapsed = 0f
        // Undispatched, so it waits for the very next frame: the one the trigger is drawn in.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale > 0f) {
                val lead = (FocalTiming.TRIGGER_LEAD_MS * NANOS_PER_MS * scale).toLong()
                var start = Long.MIN_VALUE
                while (elapsed < lengthMs) {
                    withFrameNanos { now ->
                        if (start == Long.MIN_VALUE) start = now - lead
                        elapsed = (now - start).toFloat() / NANOS_PER_MS / scale
                    }
                }
            }
            elapsed = Float.POSITIVE_INFINITY
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Timing constants of the choreography (spec 2.8.2, 2.8.3, 2.8.6, 2.8.7). */
object FocalTiming {
    const val COPIED_HOLD_MS = 1600L
    const val READY_SHIMMER_DELAY_MS = 160L
    const val READY_HAPTIC_GUARD_MS = 1500L
    const val DEVELOP_DELAY_MS = 60L
    const val RECEDE_DELAY_MS = 140L
    const val SEARCH_DEBOUNCE_MS = 120L
    const val FIND_DEBOUNCE_MS = 120L
    const val AUTOSAVE_DEBOUNCE_MS = 600L
    const val ARRIVAL_HIGHLIGHT_MS = 1500L
    const val NOTIFICATION_PERMISSION_DELAY_MS = 600L
    /**
     * How far ahead of its first frame a triggered motion runs (FocalMotion.fromTrigger): one frame at 60 Hz, the
     * longest wait from a tap to the next frame.
     */
    const val TRIGGER_LEAD_MS = 16L
    const val FEEDBACK_MS = 8000
    const val HINT_RECORDINGS = 3
    const val LIGHT_UP_STAGGER_MS = 24L
    const val LIGHT_UP_GLOW_DELAY_MS = 60L
    const val LIGHT_UP_TIMER_DELAY_MS = 40L
    const val EMPTY_TIMER_DELAY_MS = 160L
    const val RACK_STAGGER_MS = 24L
    const val PEEK_HOLD_MS = 400L
    const val PEEK_IDLE_MS = 1200L
    const val PEEK_MAX = 2
    /** Arriving text renders settled once it is older than this, so scrolling never replays it. */
    const val SETTLED_AFTER_MS = 600L
}

/** Gesture constants of the expand drag and the settings swipe (spec 2.8.6, 2.8.7). */
object FocalGestures {
    /** A release faster than this completes in the fling's direction, in dp per second. */
    const val FLING_DP_PER_S = 1000f
    /** The expand completes past this share of the travel; the fold below 1 minus it. */
    const val EXPAND_THRESHOLD = 0.4f
    /** Predictive back previews the fold down to 1 minus this. */
    const val BACK_PREVIEW = 0.4f
    /** Settings opens past this share of its travel; closes below 1 minus it. */
    const val SETTINGS_THRESHOLD = 0.35f
    /** The settings swipe locks to horizontal when abs(dx) exceeds this times abs(dy). */
    const val DIRECTION_LOCK = 1.5f
}

/**
 * Animations play at their own speed even with the system's animator scale at 0: the reduced-motion crossfades run
 * inside `withContext(FullMotion)`, because an instant cut from paper to the near-black material is a flash.
 */
object FullMotion : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** True when the system asks for reduced motion (animator duration scale 0, or "Remove animations"). */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Follows `ValueAnimator.areAnimatorsEnabled()`: live on API 33+, re-read on every resume below that. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = !ValueAnimator.areAnimatorsEnabled()
        }
        lifecycle.addObserver(observer)
        val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ValueAnimator.DurationScaleChangeListener { scale -> reduced = scale == 0f }
                .also { ValueAnimator.registerDurationScaleChangeListener(it) }
        } else null
        onDispose {
            lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && listener != null) {
                ValueAnimator.unregisterDurationScaleChangeListener(listener)
            }
        }
    }
    return reduced
}
