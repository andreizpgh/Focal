package app.focal.gallery

import androidx.compose.runtime.Immutable
import app.focal.core.MeterMath
import app.focal.ui.components.WaveMeter

/** Fixed levels by bar distance (0 = centre), so a wave in a screenshot is the same on every run. */
@Immutable
internal class FixedMeter(private val levels: FloatArray) : WaveMeter {
    init { require(levels.size == MeterMath.SIDE_BARS + 1) }
    override val animated: Boolean get() = false
    override fun advance(nowMs: Long) = Unit
    override fun level(distance: Int, nowMs: Long, history: Boolean): Float = levels[if (history) distance else 0]
}
