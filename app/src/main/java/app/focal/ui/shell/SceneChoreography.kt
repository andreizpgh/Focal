package app.focal.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import app.focal.ui.components.Mark
import app.focal.ui.components.WaveEntry
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTiming
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What plays on top of the morph for one opening of the scene (spec 6.1), started in the same frame as p so the
 * offsets hold from the trigger: the wave lights up pair by pair (T1′, the shortcut: pair d from +24·d ms on `fade`,
 * the glow from +60 on `fadeSlow`) or rises from rest (T1′e); "0:00" arrives (+40, T1′e +160); the stage's note
 * leaves on `fadeFast` (T1′); the chrome fades in (the shortcut). A plain expand (T6′, T28) has none of it. Read in
 * the draw phase.
 */
/** The leaving note counts as gone below this alpha (T1′: "0:00" arrives then). */
private const val NOTE_GONE = 0.15f

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

    /**
     * Plays it; [wake] is the material's alpha, which only T1′e animates. Every part counts from the trigger
     * (FocalMotion.fromTrigger), as the morph does: the first frame after Record already shows the material arriving
     * and the note leaving, and each "+n" lands n ms after the tap.
     */
    internal suspend fun play(wake: Animatable<Float, *>) = coroutineScope {
        if (kind == SceneEntry.RecordEmpty && !reduced) {
            wake.snapTo(0f)
            launch(start = CoroutineStart.UNDISPATCHED) { wake.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fadeFast())) }
        } else wake.snapTo(1f)
        // The stage's note leaves from the trigger frame itself (spec 2.8.2 `noteExit`), not a dispatch later.
        launch(start = CoroutineStart.UNDISPATCHED) { note.animateTo(0f, FocalMotion.fromTrigger(FocalMotion.fadeFast())) }
        launch(start = CoroutineStart.UNDISPATCHED) { chrome.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fade())) }
        if (!lights) return@coroutineScope
        pairs.forEachIndexed { distance, pair ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                delay(FocalTiming.LIGHT_UP_STAGGER_MS * distance)
                pair.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fade()))
            }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            delay(FocalTiming.LIGHT_UP_GLOW_DELAY_MS)
            glow.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fadeSlow()))
        }
        delay(if (kind == SceneEntry.RecordEmpty) FocalTiming.EMPTY_TIMER_DELAY_MS else FocalTiming.LIGHT_UP_TIMER_DELAY_MS)
        // "0:00" fades through the leaving note: it waits until the note's footer has nearly gone (T1′), so the two
        // typesettings are never superimposed.
        if (kind == SceneEntry.Record) snapshotFlow { note.value }.first { it <= NOTE_GONE }
        timer.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fade()))
    }
}
