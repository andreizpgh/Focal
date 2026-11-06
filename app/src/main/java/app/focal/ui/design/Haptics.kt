package app.focal.ui.design

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The four haptic moments, and no others (spec 2.9). Everything else is silent: Pause, Resume, expand and collapse,
 * the settings swipe, play, edit, delete, Undo, errors. Only `LocalHapticFeedback`: no Vibrator, no permission.
 */
@Stable
class FocalHaptics internal constructor(private val feedback: HapticFeedback) {
    /** The service reports capture running (never on touch-down, never for a failed start). */
    fun recordingStarted() = perform(HapticFeedbackType.ToggleOn)

    /** Finish, after the service accepted the command (scene or library dock; the notification's Finish is silent). */
    fun finish() = perform(HapticFeedbackType.Confirm)

    /** After `setPrimaryClip` returned without throwing. */
    fun copy() = perform(HapticFeedbackType.Confirm)

    /**
     * The first frame of the readiness morph, on screen only and once per note (the caller keeps that rule). Skipped
     * when any Focal haptic fired within [FocalTiming.READY_HAPTIC_GUARD_MS] (a short note ready right after Finish).
     * Returns whether it ticked.
     */
    fun ready(): Boolean {
        if (SystemClock.elapsedRealtime() - lastAt < FocalTiming.READY_HAPTIC_GUARD_MS) return false
        perform(HapticFeedbackType.Confirm)
        return true
    }

    private fun perform(type: HapticFeedbackType) {
        feedback.performHapticFeedback(type)
        lastAt = SystemClock.elapsedRealtime()
    }

    private companion object {
        /** Process-wide: the guard spans screens (Finish on the scene, Ready on the note page). */
        @Volatile var lastAt = Long.MIN_VALUE / 2
    }
}

@Composable
fun rememberFocalHaptics(): FocalHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { FocalHaptics(feedback) }
}
