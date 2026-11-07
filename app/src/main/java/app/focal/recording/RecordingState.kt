package app.focal.recording

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Whether recorded speech can be transcribed right now. Recording never depends on it. */
enum class SpeechAvailability {
    READY,
    /** The model is being copied out of the APK (first launch); text follows once it is done. */
    PREPARING,
    /** The model could not be loaded (memory or storage); saved audio is transcribed later. */
    UNAVAILABLE,
}

/**
 * What the recording service is doing, published for the UI. [noteId] is the note it works on: the recording
 * while the microphone session lasts (Recording or Paused), else the note being transcribed ([processing]).
 * Null when the service is idle; failed commands are reported as [ServiceEvent]s.
 *
 * [duration] is the recorded length in ms (the capture clock while recording; the meter reads [AudioLevel]).
 * [pauseReason] is a [app.focal.data.CaptureReason] when capture paused automatically, null for a manual
 * pause. [storageShortBytes] is how much space is still missing after Resume was refused for storage.
 * [progress] is the transcribed share of the processed note's audio (0..1) while [processing].
 */
data class LiveSession(
    val noteId: String? = null,
    val paused: Boolean = false,
    val processing: Boolean = false,
    val duration: Long = 0,
    val pending: Int = 0,
    val pauseReason: String? = null,
    val storageShortBytes: Long = 0,
    val progress: Float? = null,
    val speech: SpeechAvailability = SpeechAvailability.READY,
) {
    /** True while the microphone session lasts (recording or paused). */
    val capturing: Boolean get() = noteId != null && !processing
}

/** One-off outcomes of service commands that have no note to show them on. */
sealed interface ServiceEvent {
    /** Finish found no sound at all (no bytes or exactly constant samples): the note was discarded. */
    data object NothingRecorded : ServiceEvent

    /** Record could not start. [storageShortBytes] is set when free space was the reason. */
    data class StartFailed(val reason: StartFailure, val message: String, val storageShortBytes: Long = 0) : ServiceEvent

    /**
     * Resume was refused because storage is still short by [storageShortBytes] (the pause stays). Sent for every
     * refusal, also when the shortfall (and so the published session) did not change.
     */
    data class ResumeRefused(val storageShortBytes: Long) : ServiceEvent
}

enum class StartFailure { STORAGE, MICROPHONE, PERMISSION, OTHER }

object RecordingState {
    /** The service's session. Only the service changes it, through [publish]. */
    val live = MutableStateFlow(LiveSession())
    val events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)

    private val generations = AtomicLong()
    /** The generation of the service instance that last changed [live]; guarded by this object. */
    private var publisher = 0L

    /** A generation for a new service instance: newer than every instance before it. */
    internal fun newGeneration(): Long = generations.incrementAndGet()

    /**
     * Changes [live] for the service instance of [generation], unless a newer instance has changed it since.
     * A destroyed instance finishes its teardown asynchronously (after onTimeout, for example) while Android may
     * already run a new instance for the next recording: the old one may still clear the session it published,
     * but never the newer instance's. [transform] runs under a short lock and must not block (no storage).
     */
    internal fun publish(generation: Long, transform: (LiveSession) -> LiveSession) {
        synchronized(this) {
            if (generation < publisher) return
            val current = live.value
            val next = transform(current)
            if (next == current) return
            publisher = generation
            live.value = next
        }
    }
}

/**
 * The latest microphone level (0..1 on the meter's dBFS scale, [app.focal.core.MeterMath.normalized]),
 * written by the capture thread for every read and read by the meter inside its draw lambda. Not a flow:
 * the level must never recompose a screen.
 */
object AudioLevel {
    private val bits = AtomicInteger(0)

    val current: Float get() = Float.fromBits(bits.get())

    internal fun publish(level: Float) = bits.set(level.toRawBits())
}

/**
 * Whether one of Focal's activities is started, and which note the UI shows. Notifications skip what the
 * user can already see: the auto-pause alert while Focal is visible, "Note ready" for the note on screen.
 * The UI sets [shownNote] while a note screen is visible and clears it when it leaves.
 */
object AppPresence : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger()
    val shownNote = MutableStateFlow<String?>(null)
    val visible: Boolean get() = started.get() > 0

    fun install(application: Application) = application.registerActivityLifecycleCallbacks(this)

    fun isShowing(noteId: String): Boolean = visible && shownNote.value == noteId

    override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) { started.updateAndGet { maxOf(0, it - 1) } }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Free-space rules for recording. Audio costs about 115 MB per hour. */
object StorageReserve {
    /** Record starts only with this much free space. */
    const val START_BYTES = 200_000_000L
    /** Capture pauses automatically below this. */
    const val PAUSE_BYTES = 64_000_000L
    /** Resume continues only with this much free space, so it does not pause again at once. */
    const val RESUME_BYTES = 128_000_000L
    /** Missing space is reported rounded up to this step ("Free up about 300 MB"). */
    const val STEP_BYTES = 50_000_000L

    /** Bytes missing from [usable] to reach [required], rounded up to [STEP_BYTES]; 0 when enough. */
    fun shortfall(usable: Long, required: Long): Long {
        if (usable >= required) return 0
        val missing = required - usable
        return (missing + STEP_BYTES - 1) / STEP_BYTES * STEP_BYTES
    }
}

/**
 * The service's wake lock rule. The lock is acquired with [TIMEOUT_MS] so that a lost release cannot keep
 * the CPU awake for good, and renewed after [RENEW_MS] while it is still wanted, so long work (an hour of
 * screen-off recording, for example) never runs past the timeout.
 */
internal object WakePolicy {
    const val TIMEOUT_MS = 35 * 60_000L
    const val RENEW_MS = 30 * 60_000L

    enum class Action { ACQUIRE, RELEASE, KEEP }

    fun action(wanted: Boolean, held: Boolean, acquiredAtMs: Long, nowMs: Long): Action = when {
        !wanted -> if (held) Action.RELEASE else Action.KEEP
        !held || nowMs - acquiredAtMs >= RENEW_MS -> Action.ACQUIRE
        else -> Action.KEEP
    }
}
