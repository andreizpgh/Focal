package app.focal.ui.design

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlin.math.min

/**
 * Frame time that never jumps. Every animation in the app is a function of frame time; a frame that takes long (a
 * page composed for the first time, a list filled with rows) would otherwise be followed by one that shows the
 * animation where the wall clock says it should be by now: most of a 300 ms motion gone in one step, on the very
 * frames that matter. Here a frame advances time by at most [maxStepNanos], so a long frame holds the motion still and
 * the next one goes on from where it was. At a steady frame rate above 25 Hz nothing changes.
 */
class SteadyFrameClock(private val frames: MonotonicFrameClock, private val maxStepNanos: Long = MAX_STEP_NANOS) : MonotonicFrameClock {
    private var lastFrame = Long.MIN_VALUE
    private var time = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = frames.withFrameNanos { now -> onFrame(steady(now)) }

    /** The steady time of the frame at [now]; every waiter of one frame gets the same. Main thread only. */
    internal fun steady(now: Long): Long {
        if (lastFrame == Long.MIN_VALUE) time = now
        else if (now != lastFrame) time += min(now - lastFrame, maxStepNanos).coerceAtLeast(0L)
        lastFrame = now
        return time
    }

    companion object {
        /** Two and a half frames at 60 Hz: a dropped frame or two passes unchanged, a stall does not. */
        const val MAX_STEP_NANOS = 40_000_000L

        /**
         * Makes every Compose window of this process run on steady frame time. Call once, before the first activity
         * sets its content. A Compose test rule installs its own recomposer over this one.
         */
        @OptIn(InternalComposeUiApi::class)
        fun install() {
            WindowRecomposerPolicy.setFactory(
                WindowRecomposerFactory { rootView ->
                    val ui = AndroidUiDispatcher.CurrentThread
                    val frames = checkNotNull(ui[MonotonicFrameClock]) { "The UI dispatcher has a frame clock." }
                    rootView.createLifecycleAwareWindowRecomposer(ui + SteadyFrameClock(frames))
                },
            )
        }
    }
}
