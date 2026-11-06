package app.focal.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import app.focal.MainActivity
import app.focal.R
import app.focal.core.Transcript
import app.focal.data.CaptureReason
import java.util.Date
import android.text.format.DateFormat as DeviceDateFormat

/**
 * Focal's notifications (design 5.12). Transcript text never appears in one: only a note's title, and only on an
 * unlocked screen (private visibility with public versions). Channels follow the design: recording controls and
 * transcription progress are silent; an automatic pause and a finished note may alert. The small icon is the white
 * mark, never colourised.
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

    /** The foreground notification while the service opens the microphone, before the note exists. */
    fun starting(): Notification = microphone(null, elapsedMs = 0)
        .setContentTitle(context.getString(R.string.n_recording))
        .setPublicVersion(running(publicBuilder(RECORDING, R.string.n_public_recording), 0).build())
        .build()

    /**
     * The microphone session: "Recording" with a running chronometer and Pause · Finish, or "Paused · 12:04" with
     * Resume · Finish and, after an automatic pause, its reason as a sentence. Tapping it shows the scene.
     */
    fun recording(noteId: String?, elapsedMs: Long, paused: Boolean, pauseReason: String?): Notification {
        val builder = microphone(noteId, elapsedMs)
        if (paused) {
            builder.setContentTitle(context.getString(R.string.n_paused, Transcript.time(elapsedMs)))
                .setUsesChronometer(false).setShowWhen(false)
                .setShortCriticalText(context.getString(R.string.n_public_paused))
                .setPublicVersion(publicBuilder(RECORDING, R.string.n_public_paused).build())
                .addAction(R.drawable.ic_action_resume, context.getString(R.string.action_resume), command(RecordingService.RESUME, 1))
            reasonText(pauseReason)?.let(builder::setContentText)
        } else {
            builder.setContentTitle(context.getString(R.string.n_recording))
                .setPublicVersion(running(publicBuilder(RECORDING, R.string.n_public_recording), elapsedMs).build())
                .addAction(R.drawable.ic_action_pause, context.getString(R.string.action_pause), command(RecordingService.PAUSE, 1))
        }
        builder.addAction(R.drawable.ic_action_finish, context.getString(R.string.action_finish), command(RecordingService.STOP, 2))
        return builder.build()
    }

    /**
     * Transcription in the background: "Transcribing" over the note's title, with determinate progress once the share
     * is known (indeterminate while the speech model is still being prepared). Tapping it opens the note.
     */
    fun transcribing(noteId: String?, title: String, created: Long, progress: Float?, preparing: Boolean): Notification {
        val builder = base(TRANSCRIPTION, openApp(noteId, recording = false)).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentTitle(context.getString(R.string.n_transcribing))
        val public = publicBuilder(TRANSCRIPTION, R.string.n_transcribing)
        noteTitle(title, created)?.let(builder::setContentText)
        val percent = if (progress == null || preparing) null else (progress * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
        listOf(builder, public).forEach {
            if (percent != null) it.setProgress(PROGRESS_MAX, percent, false) else it.setProgress(0, 0, true)
        }
        return builder.setPublicVersion(public.build()).build()
    }

    /** One alert when capture paused on its own while Focal is not visible: its reason; cancelled on Resume. */
    fun alertAutoPause(noteId: String, elapsedMs: Long, reason: String) {
        if (AppPresence.visible) return
        val builder = base(PAUSED, openApp(noteId, recording = true)).setAutoCancel(true)
            .setContentTitle(context.getString(R.string.n_paused, Transcript.time(elapsedMs)))
            .setPublicVersion(publicBuilder(PAUSED, R.string.n_public_paused).build())
        reasonText(reason)?.let(builder::setContentText)
        post(PAUSE_ALERT_ID, null, builder.build())
    }

    fun cancelAutoPause() = runCatching { manager.cancel(PAUSE_ALERT_ID) }

    /**
     * "Note ready" (or the partial-failure line) over the note's title, unless the note is on screen. Several are
     * bundled under one summary; a single one stands alone.
     */
    fun ready(noteId: String, title: String, created: Long, partial: Boolean) {
        if (AppPresence.isShowing(noteId)) return
        val headline = if (partial) R.string.n_partial else R.string.n_ready
        val builder = base(READY, openApp(noteId, recording = false)).setAutoCancel(true)
            .setContentTitle(context.getString(headline))
            .setGroup(READY_GROUP)
            .setPublicVersion(publicBuilder(READY, if (partial) R.string.n_partial else R.string.n_public_ready).build())
        noteTitle(title, created)?.let(builder::setContentText)
        post(READY_ID, noteId, builder.build())
        summarizeReady(context)
    }

    /** Android stopped background transcription (onTimeout): it continues when Focal is opened. */
    fun timedOut(noteId: String?) {
        val builder = base(TRANSCRIPTION, openApp(noteId, recording = false)).setAutoCancel(true)
            .setContentTitle(context.getString(R.string.n_timeout_title))
            .setContentText(context.getString(R.string.n_timeout_text))
            .setPublicVersion(publicBuilder(TRANSCRIPTION, R.string.n_timeout_title).build())
        post(TIMEOUT_ID, null, builder.build())
    }

    fun cancelTimedOut() = runCatching { manager.cancel(TIMEOUT_ID) }

    /** Posts the foreground notification's update; a removed notification permission is not an error. */
    fun update(notification: Notification) = post(FOREGROUND_ID, null, notification)

    fun cancelForeground() = manager.cancel(FOREGROUND_ID)

    private fun post(id: Int, tag: String?, notification: Notification) = post(manager, id, tag, notification)

    /**
     * The microphone session's shared shape: ongoing, shown at once, a stopwatch (API 31+), and a Live Update on
     * Android 16 QPR2+ (the system ignores the request elsewhere). The chronometer runs from the capture's start.
     */
    private fun microphone(noteId: String?, elapsedMs: Long) = running(base(RECORDING, openApp(noteId, recording = true)), elapsedMs)
        .setOngoing(true)
        .setCategory(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) NotificationCompat.CATEGORY_STOPWATCH else NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setRequestPromotedOngoing(true)

    private fun running(builder: NotificationCompat.Builder, elapsedMs: Long) =
        builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)

    private fun base(channel: String, contentIntent: PendingIntent) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setColorized(false)
        .setContentIntent(contentIntent).setOnlyAlertOnce(true).setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    /** What a locked screen shows instead: the state's words only, never a title. */
    private fun publicBuilder(channel: String, @StringRes title: Int) = publicBuilder(context, channel, title)

    private fun reasonText(reason: String?): String? = when (reason) {
        CaptureReason.CALL -> context.getString(R.string.n_paused_call)
        CaptureReason.MICROPHONE -> context.getString(R.string.n_paused_other_app)
        CaptureReason.STORAGE -> context.getString(R.string.n_paused_storage)
        else -> null
    }

    /** The note's title, or "Note at 14:32" in the device's 12/24-hour format while it has none yet. */
    private fun noteTitle(title: String, created: Long): String? = title.ifBlank {
        if (created <= 0) return null
        context.getString(R.string.title_fallback, DeviceDateFormat.getTimeFormat(context).format(Date(created)))
    }

    /** Opens Focal on [noteId]; [recording] asks for the recording scene. */
    private fun openApp(noteId: String?, recording: Boolean): PendingIntent = openApp(context, noteId, recording)

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
        /** The bundle of several "Note ready" notifications. */
        const val READY_SUMMARY_ID = 5
        const val READY_GROUP = "app.focal.READY"
        private const val PROGRESS_MAX = 100
        /** Channels of notifications that outlive the service on purpose. */
        val LASTING_CHANNELS = setOf(READY, PAUSED)

        /** A deleted note's "Note ready" would open nothing: it goes with the note. */
        fun cancelReady(context: Context, noteId: String) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.cancel(noteId, READY_ID)
                summarizeReady(context)
            }
        }

        /**
         * Keeps the group summary in step with the finished notes on display: posted (silently, the notes alert)
         * from two on, removed below two. Tapping the summary opens the library.
         */
        private fun summarizeReady(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val shown = runCatching { manager.activeNotifications.count { it.id == READY_ID && it.tag != null } }.getOrDefault(0)
            if (shown < 2) { manager.cancel(READY_SUMMARY_ID); return }
            val summary = NotificationCompat.Builder(context, READY)
                .setSmallIcon(R.drawable.ic_notification).setColorized(false)
                .setContentTitle(context.getString(R.string.n_ready))
                .setContentIntent(openApp(context, null, recording = false))
                .setGroup(READY_GROUP).setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicBuilder(context, READY, R.string.n_public_ready).build())
                .build()
            post(manager, READY_SUMMARY_ID, null, summary)
        }

        private fun publicBuilder(context: Context, channel: String, @StringRes title: Int) = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setColorized(false).setShowWhen(false)
            .setContentTitle(context.getString(title))

        private fun openApp(context: Context, noteId: String?, recording: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(if (recording) MainActivity.ACTION_SHOW_RECORDING else Intent.ACTION_VIEW)
                .putExtra(RecordingService.NOTE, noteId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, if (recording) 0 else (noteId?.hashCode() ?: 0) or 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun post(manager: NotificationManager, id: Int, tag: String?, notification: Notification) {
            try { manager.notify(tag, id, notification) }
            catch (_: SecurityException) { /* Notifications are off; the app shows the same state. */ }
        }
    }
}
