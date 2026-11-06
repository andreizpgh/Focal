package app.focal

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.CaptureReason
import app.focal.recording.ServiceNotifications
import app.focal.ui.state.UndoableActions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The notifications' words, extras and actions (design 5.12), and how "Note ready" follows its note. */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    private lateinit var app: FocalApplication
    private lateinit var notifications: ServiceNotifications
    private lateinit var manager: NotificationManager
    private val created = mutableListOf<String>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.storageReady.await() }
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        notifications = ServiceNotifications(app).also { it.createChannels() }
        manager = app.getSystemService(NotificationManager::class.java)
    }

    @After fun cleanup() {
        created.forEach { manager.cancel(it, ServiceNotifications.READY_ID); app.store.delete(it) }
        manager.cancel(ServiceNotifications.READY_SUMMARY_ID)
    }

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val Notification.actionTitles get() = actions.orEmpty().map { it.title.toString() }

    @Test fun recordingRunsAChronometerWithPauseAndFinish() {
        val elapsed = 73_000L
        val before = System.currentTimeMillis()
        val running = notifications.recording("note", elapsed, paused = false, pauseReason = null)
        assertEquals("Recording", running.title)
        assertNull("No text under Recording", running.text)
        assertTrue(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue("The chronometer counts from the capture's start", running.`when` in before - elapsed - 1_000..before - elapsed + 1_000)
        assertEquals(listOf("Pause", "Finish"), running.actionTitles)
        assertEquals(if (Build.VERSION.SDK_INT >= 31) Notification.CATEGORY_STOPWATCH else Notification.CATEGORY_SERVICE, running.category)
        assertTrue(running.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(ServiceNotifications.RECORDING, running.channelId)
        assertPrivate(running, "Recording")
        assertTrue("The locked screen keeps the chronometer", running.publicVersion.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))

        val starting = notifications.starting()
        assertEquals("Recording", starting.title)
        assertTrue(starting.actionTitles.isEmpty())
    }

    @Test fun pausedNamesItsTimeAndOnlyAnAutomaticPauseItsReason() {
        val manual = notifications.recording("note", 724_000, paused = true, pauseReason = null)
        assertEquals("Paused · 12:04", manual.title)
        assertNull(manual.text)
        assertFalse(manual.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Resume", "Finish"), manual.actionTitles)
        assertPrivate(manual, "Paused")
        val reasons = mapOf(
            CaptureReason.CALL to "A call is using the microphone.",
            CaptureReason.MICROPHONE to "Another app is using the microphone.",
            CaptureReason.STORAGE to "Storage is full.",
        )
        reasons.forEach { (reason, sentence) ->
            assertEquals(sentence, notifications.recording("note", 724_000, paused = true, pauseReason = reason).text)
        }
    }

    @Test fun transcribingShowsTheTitleAndDeterminateProgress() {
        val progress = notifications.transcribing("note", "Design review", 0, 0.64f, preparing = false)
        assertEquals("Transcribing", progress.title)
        assertEquals("Design review", progress.text)
        assertEquals(100, progress.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(64, progress.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(progress.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(progress.actionTitles.isEmpty())
        assertPrivate(progress, "Transcribing")
        assertEquals("The locked screen keeps the progress", 64, progress.publicVersion.extras.getInt(Notification.EXTRA_PROGRESS))

        val untitled = notifications.transcribing("note", "", System.currentTimeMillis(), null, preparing = true)
        assertTrue("An untitled note is named by its time", untitled.text!!.startsWith("Note at "))
        assertTrue(untitled.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun timeoutAsksToOpenFocal() {
        notifications.timedOut(null)
        try {
            val posted = awaitNotification(ServiceNotifications.TIMEOUT_ID, null)
            assertEquals("Transcription paused", posted.title)
            assertEquals("Open Focal to continue.", posted.text)
            assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertPrivate(posted, "Transcription paused")
        } finally { notifications.cancelTimedOut() }
    }

    @Test fun severalReadyNotesAreBundledAndEachGoesWithItsNote() {
        val first = app.store.insertNoteForTests(listOf("Ready words."), 1000).also { created += it }
        val second = app.store.insertNoteForTests(listOf("More ready words."), 1000).also { created += it }
        notifications.ready(first, "Ready words", 0, partial = false)
        val one = awaitNotification(ServiceNotifications.READY_ID, first)
        assertEquals("Note ready", one.title)
        assertEquals("Ready words", one.text)
        assertPrivate(one, "Note ready")
        assertFalse("One note stands alone", summaryShown())
        notifications.ready(second, "", System.currentTimeMillis(), partial = true)
        val partial = awaitNotification(ServiceNotifications.READY_ID, second)
        assertEquals("Part of a note couldn’t be transcribed", partial.title)
        assertTrue(partial.text!!.startsWith("Note at "))
        awaitState("the summary of two notes", true, ::summaryShown)

        // Delete with Undo (the library's and the note's path) takes its notification and keeps the other.
        runBlocking { assertTrue(UndoableActions.delete(app, first) { fail(it) }) }
        awaitState("Note ready for the deleted note", false) { posted(first) }
        SystemClock.sleep(SETTLE_MS)
        assertTrue("Another note keeps its notification", posted(second))
        UndoableActions.commitDelete(app, first)
        assertNull(app.store.note(first))
        // The last one takes the summary with it.
        ServiceNotifications.cancelReady(app, second)
        awaitState("Note ready for the last note", false) { posted(second) }
        awaitState("the summary without notes", false, ::summaryShown)
    }

    private fun assertPrivate(notification: Notification, publicTitle: String) {
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = notification.publicVersion
        assertNotNull("A public version for the locked screen", public)
        assertEquals(publicTitle, public.title)
        assertNull("A locked screen never shows a title", public.text)
        assertEquals("The mark is never colourised", Notification.COLOR_DEFAULT, notification.color)
    }

    private companion object {
        /** Time for the system to apply a cancellation that should not have taken another notification. */
        const val SETTLE_MS = 500L
    }

    private fun posted(tag: String) = manager.activeNotifications.any { it.id == ServiceNotifications.READY_ID && it.tag == tag }

    private fun summaryShown() = manager.activeNotifications.any { it.id == ServiceNotifications.READY_SUMMARY_ID }

    private fun awaitState(what: String, expected: Boolean, state: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (state() != expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertEquals(what, expected, state())
    }

    private fun awaitNotification(id: Int, tag: String?): Notification {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == id && it.tag == tag }?.let { return it.notification }
            SystemClock.sleep(20)
        }
        throw AssertionError("Notification $id/$tag was not posted")
    }
}
