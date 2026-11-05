package app.focal.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.focal.MainActivity
import app.focal.R
import app.focal.core.Transcript
import app.focal.data.CaptureReason
import java.text.DateFormat
import java.util.Date

/**
 * Focal's notifications. Transcript text never appears in one: only a note's title, and only on an unlocked
 * screen (private visibility with public versions). Channels follow the design: recording controls and
 * transcription progress are silent; an automatic pause and a finished note may alert.
 */
internal class ServiceNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        fun channel(id: String, name: Int, description: Int, importance: Int, silent: Boolean, badge: Boolean) =
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                if (silent) setSound(null, null)
                enableVibration(!silent)
                setShowBadge(badge)
            }
        manager.createNotificationChannels(listOf(
            channel(RECORDING, R.string.ch_recording, R.string.ch_recording_desc, NotificationManager.IMPORTANCE_LOW, true, false),
            channel(PAUSED, R.string.ch_paused, R.string.ch_paused_desc, NotificationManager.IMPORTANCE_DEFAULT, false, false),
            channel(TRANSCRIPTION, R.string.ch_transcription, R.string.ch_transcription_desc, NotificationManager.IMPORTANCE_LOW, true, false),
            channel(READY, R.string.ch_ready, R.string.ch_ready_desc, NotificationManager.IMPORTANCE_DEFAULT, false, true),
        ))
    }

    /** The foreground notification while the service starts, before it knows what it records. */
    fun starting(): Notification = base(RECORDING, openApp(null, recording = true))
        .setContentTitle(context.getString(R.string.n_starting)).setOngoing(true).build()

    /**
     * The microphone session: a running chronometer with Pause and Finish, or "Paused · 12:04" with Resume
     * and Finish (and the reason's sentence when the pause was automatic).
     */
    fun recording(noteId: String?, elapsedMs: Long, paused: Boolean, pauseReason: String?): Notification {
        val builder = base(RECORDING, openApp(noteId, recording = true)).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) {
            builder.setContentTitle(context.getString(R.string.n_paused, Transcript.time(elapsedMs)))
            reasonText(pauseReason)?.let { builder.setContentText(it) }
            builder.setPublicVersion(publicVersion(RECORDING, R.string.n_public_paused))
            builder.addAction(R.drawable.ic_action_resume, context.getString(R.string.action_resume), command(RecordingService.RESUME, 1))
        } else {
            builder.setContentTitle(context.getString(R.string.n_recording))
                .setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)
            builder.setPublicVersion(publicVersion(RECORDING, R.string.n_public_recording))
            builder.addAction(R.drawable.ic_action_pause, context.getString(R.string.action_pause), command(RecordingService.PAUSE, 1))
        }
        builder.addAction(R.drawable.ic_action_finish, context.getString(R.string.action_finish), command(RecordingService.STOP, 2))
        return builder.build()
    }

    /** Transcription in the background: determinate when [progress] is known; "Preparing" while the model installs. */
    fun transcribing(noteId: String?, title: String, progress: Float?, preparing: Boolean): Notification {
        val builder = base(TRANSCRIPTION, openApp(noteId, recording = false)).setOngoing(true)
            .setContentTitle(context.getString(if (preparing) R.string.n_preparing else R.string.n_transcribing))
            .setPublicVersion(publicVersion(TRANSCRIPTION, R.string.n_public_transcribing))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (title.isNotBlank()) builder.setContentText(title)
        if (progress != null && !preparing) builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    /** One alert when capture paused on its own while Focal is not visible; cancelled on Resume. */
    fun alertAutoPause(noteId: String, elapsedMs: Long, reason: String) {
        if (AppPresence.visible) return
        val builder = base(PAUSED, openApp(noteId, recording = true)).setAutoCancel(true)
            .setContentTitle(context.getString(R.string.n_paused, Transcript.time(elapsedMs)))
            .setContentText(listOfNotNull(reasonText(reason), context.getString(R.string.n_saved)).joinToString(" "))
            .setPublicVersion(publicVersion(PAUSED, R.string.n_public_paused))
        post(PAUSE_ALERT_ID, null, builder.build())
    }

    fun cancelAutoPause() = runCatching { manager.cancel(PAUSE_ALERT_ID) }

    /** "Note ready" (or the partial-failure line) unless the note is on screen. */
    fun ready(noteId: String, title: String, created: Long, partial: Boolean) {
        if (AppPresence.isShowing(noteId)) return
        val builder = base(READY, openApp(noteId, recording = false)).setAutoCancel(true)
            .setContentTitle(context.getString(if (partial) R.string.n_partial else R.string.n_ready))
            .setContentText(title.ifBlank { fallbackTitle(created) })
            .setGroup(READY_GROUP)
            .setPublicVersion(publicVersion(READY, if (partial) R.string.n_partial else R.string.n_public_ready))
        post(READY_ID, noteId, builder.build())
    }

    /** Android stopped background transcription (onTimeout): it continues when Focal is opened. */
    fun timedOut(noteId: String?) {
        val builder = base(TRANSCRIPTION, openApp(noteId, recording = false)).setAutoCancel(true)
            .setContentTitle(context.getString(R.string.n_timeout_title))
            .setContentText(context.getString(R.string.n_timeout_text))
        post(TIMEOUT_ID, null, builder.build())
    }

    fun cancelTimedOut() = runCatching { manager.cancel(TIMEOUT_ID) }

    /** Posts the foreground notification's update; a removed notification permission is not an error. */
    fun update(notification: Notification) = post(FOREGROUND_ID, null, notification)

    fun cancelForeground() = manager.cancel(FOREGROUND_ID)

    private fun post(id: Int, tag: String?, notification: Notification) {
        try { manager.notify(tag, id, notification) }
        catch (_: SecurityException) { /* Notifications are off; the app shows the same state. */ }
    }

    private fun base(channel: String, contentIntent: PendingIntent) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setColorized(false)
        .setContentIntent(contentIntent).setOnlyAlertOnce(true).setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun publicVersion(channel: String, title: Int): Notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(title)).build()

    private fun reasonText(reason: String?): String? = when (reason) {
        CaptureReason.CALL -> context.getString(R.string.pause_call)
        CaptureReason.MICROPHONE -> context.getString(R.string.pause_silenced)
        CaptureReason.STORAGE -> context.getString(R.string.pause_storage)
        else -> null
    }

    private fun fallbackTitle(created: Long) =
        context.getString(R.string.n_note_fallback, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(created)))

    /** Opens Focal on [noteId]; [recording] asks for the recording screen (the note's live view). */
    private fun openApp(noteId: String?, recording: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (recording) MainActivity.ACTION_SHOW_RECORDING else Intent.ACTION_VIEW)
            .putExtra(RecordingService.NOTE, noteId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, if (recording) 0 else (noteId?.hashCode() ?: 0) or 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun command(action: String, code: Int): PendingIntent = PendingIntent.getService(context, code,
        Intent(context, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val RECORDING = "recording"
        const val PAUSED = "recording_paused"
        const val TRANSCRIPTION = "transcription"
        const val READY = "ready"
        const val FOREGROUND_ID = 1
        const val PAUSE_ALERT_ID = 2
        const val TIMEOUT_ID = 3
        const val READY_ID = 4
        private const val READY_GROUP = "app.focal.READY"
        private const val PROGRESS_MAX = 100
        /** Channels of notifications that outlive the service on purpose. */
        val LASTING_CHANNELS = setOf(READY, PAUSED)

        /** A deleted note's "Note ready" would open nothing: it goes with the note. */
        fun cancelReady(context: Context, noteId: String) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(noteId, READY_ID) }
        }
    }
}
