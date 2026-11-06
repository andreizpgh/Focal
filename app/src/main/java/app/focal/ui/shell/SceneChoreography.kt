package app.focal.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import app.focal.ui.components.Mark
import app.focal.ui.components.WaveEntry
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTiming
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What plays on top of the morph for one opening of the scene (spec 6.1), started in the same frame as p so the
 * offsets hold from the trigger: the wave lights up pair by pair (T1′, the shortcut: pair d from +24·d ms on `fade`,
 * the glow from +60 on `fadeSlow`) or rises from rest (T1′e); "0:00" arrives (+40, T1′e +160); the stage's note
 * leaves on `fadeFast` (T1′); the chrome fades in (the shortcut). A plain expand (T6′, T28) has none of it. Read in
 * the draw phase.
 */
@Stable
class SceneChoreography internal constructor(val kind: SceneEntry, private val reduced: Boolean) {
    private val lights = kind != SceneEntry.Stage && !reduced
    private val pairs = Array(Mark.CENTRE + 1) { Animatable(if (lights) 0f else 1f) }
    private val glow = Animatable(if (lights) 0f else 1f)
    val timer = Animatable(if (lights) 0f else 1f)
    val note = Animatable(if (kind == SceneEntry.Record && !reduced) 1f else 0f)
    val chrome = Animatable(if (kind == SceneEntry.Direct && !reduced) 0f else 1f)

    /** The meter starts from the full logo and relaxes to the live level (spec 3.2 "entering"). */
    val seedsMeter: Boolean get() = lights && kind != SceneEntry.RecordEmpty

    /** How the bars arrive, or null: a plain expand keeps the wave as it is. */
    val wave: WaveEntry? = if (!lights) null else object : WaveEntry {
        override fun pair(distance: Int): Float = pairs[distance].value
        override val glow: Float get() = this@SceneChoreography.glow.value
        override val restLevel: Float? = if (kind == SceneEntry.RecordEmpty) Mark.REST else null
    }

    /** Plays it; [wake] is the material's alpha, which only T1′e animates. */
    internal suspend fun play(wake: Animatable<Float, *>) = coroutineScope {
        if (kind == SceneEntry.RecordEmpty && !reduced) {
            wake.snapTo(0f)
            launch { wake.animateTo(1f, FocalMotion.fadeFast()) }
        } else wake.snapTo(1f)
        launch { note.animateTo(0f, FocalMotion.fadeFast()) }
        launch { chrome.animateTo(1f, FocalMotion.fade()) }
        if (!lights) return@coroutineScope
        pairs.forEachIndexed { distance, pair ->
            launch {
                delay(FocalTiming.LIGHT_UP_STAGGER_MS * distance)
                pair.animateTo(1f, FocalMotion.fade())
            }
        }
        launch {
            delay(FocalTiming.LIGHT_UP_GLOW_DELAY_MS)
            glow.animateTo(1f, FocalMotion.fadeSlow())
        }
        delay(if (kind == SceneEntry.RecordEmpty) FocalTiming.EMPTY_TIMER_DELAY_MS else FocalTiming.LIGHT_UP_TIMER_DELAY_MS)
        timer.animateTo(1f, FocalMotion.fade())
    }
}
