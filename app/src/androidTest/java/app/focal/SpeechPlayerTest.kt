package app.focal

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.Note
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.speech.PlaybackState
import app.focal.speech.SpeechPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sin

/** Real AudioTrack checks using small synthetic PCM files; a speech model is not required. */
@RunWith(AndroidJUnit4::class)
class SpeechPlayerTest {
    private lateinit var store: NoteStore
    private lateinit var player: SpeechPlayer
    private lateinit var owner: CoroutineScope
    private lateinit var activity: ActivityScenario<MainActivity>
    private val created = mutableListOf<String>()
    private val errors = CopyOnWriteArrayList<String>()
    private val onError: (String) -> Unit = { errors += it }

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { app.initialized.await() }
        store = app.store
        // Android 15 grants media focus only to a foreground app or eligible service.
        activity = ActivityScenario.launch(MainActivity::class.java)
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        player = SpeechPlayer(app, store, owner)
    }

    @After fun cleanup() {
        if (::player.isInitialized) player.stop()
        if (::owner.isInitialized) runBlocking { owner.coroutineContext[Job]!!.cancelAndJoin() }
        if (::activity.isInitialized) activity.close()
        if (::store.isInitialized) created.forEach { store.delete(it) }
    }

    @Test fun positionAdvancesWithPlayedFramesAndDurationComesFromTheFile() {
        val note = syntheticNote(5000, metadataDuration = 90_000)
        val startedAt = SystemClock.elapsedRealtime()
        player.playNote(note, onError)
        await { player.playback.value.isPlaying && player.playback.value.positionMs >= 160 }
        val first = player.playback.value
        assertEquals(note.id, first.noteId)
        assertNull(first.passageId)
        assertEquals(5000L, first.durationMs)
        assertEquals(-1L, player.playing.value)
        SystemClock.sleep(180)
        val next = player.playback.value
        assertTrue("The hardware playback head did not advance: $first → $next", next.positionMs >= first.positionMs + 80)
        assertTrue("Queued audio must not be reported as already played", next.positionMs <= SystemClock.elapsedRealtime() - startedAt + 100)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun togglingWholeNotePausesAtThePlayedPositionAndResumesThere() {
        val note = syntheticNote(5000)
        player.playNote(note, onError)
        await { player.playback.value.positionMs >= 200 }
        player.playNote(note, onError)
        val paused = player.playback.value
        assertFalse(paused.isPlaying)
        assertNull(player.playing.value)
        assertTrue(paused.positionMs >= 200)
        SystemClock.sleep(250)
        assertEquals("Paused position must not advance with queued frames", paused, player.playback.value)
        player.playNote(note, onError)
        await { player.playback.value.isPlaying && player.playback.value.positionMs >= paused.positionMs + 120 }
        assertEquals(note.id, player.playback.value.noteId)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun seekingLeavesAnExcerptAndClampsToTheOriginalAudio() {
        val note = syntheticNote(5000, metadataDuration = 900)
        val passage = Passage(42, note.id, "synthetic", 1400, 1800, "Synthetic excerpt", true)
        player.play(passage, onError)
        await { player.playback.value.isPlaying && player.playback.value.passageId == 42L }
        assertEquals(42L, player.playing.value)
        assertTrue(player.playback.value.positionMs >= 1150)
        assertEquals(5000L, player.playback.value.durationMs)
        player.pause()
        val pausedExcerpt = player.playback.value
        assertFalse(pausedExcerpt.isPlaying)
        assertEquals(42L, pausedExcerpt.passageId)
        assertNull(player.playing.value)
        SystemClock.sleep(100)
        assertEquals(pausedExcerpt, player.playback.value)
        player.seekTo(note, 3200, onError)
        await { player.playback.value.isPlaying && player.playback.value.passageId == null && player.playback.value.positionMs >= 3200 }
        assertTrue(player.playback.value.positionMs < 3700)
        assertEquals(-1L, player.playing.value)
        player.seekTo(note, -500, onError)
        await { player.playback.value.isPlaying && player.playback.value.positionMs < 500 }
        player.seekTo(note, Long.MAX_VALUE, onError)
        await { !player.playback.value.isPlaying && player.playback.value.positionMs == 5000L }
        assertEquals(5000L, player.playback.value.durationMs)
        assertNull(player.playing.value)
        SystemClock.sleep(100)
        assertFalse(player.playback.value.isPlaying)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun rapidSourceReplacementCannotClearTheNewestSessionAndStopResetsState() {
        val first = syntheticNote(5000)
        val second = syntheticNote(6000)
        val latest = syntheticNote(7000)
        player.playNote(first, onError)
        await { player.playback.value.isPlaying }
        repeat(12) { index -> player.seekTo(if (index % 2 == 0) first else second, 400L + index * 80, onError) }
        player.playNote(latest, onError)
        await { player.playback.value.noteId == latest.id && player.playback.value.isPlaying && player.playback.value.positionMs >= 160 }
        SystemClock.sleep(300)
        assertEquals(latest.id, player.playback.value.noteId)
        assertTrue("A replaced source cleared the latest session", player.playback.value.isPlaying)
        assertEquals(-1L, player.playing.value)
        player.stop()
        assertEquals(PlaybackState(), player.playback.value)
        assertNull(player.playing.value)
        SystemClock.sleep(200)
        assertEquals("Cancelled cleanup republished an old source", PlaybackState(), player.playback.value)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    private fun syntheticNote(durationMs: Long, metadataDuration: Long = durationMs): Note {
        val id = store.create("en").also { created += it }
        // A low-amplitude 220 Hz tone, PCM16 little endian / mono / 16 kHz.
        store.audioFile(id).outputStream().buffered().use { output ->
            val buffer = ByteArray(2048)
            val frames = durationMs * 16
            var frame = 0L
            while (frame < frames) {
                val count = minOf(1024L, frames - frame).toInt()
                repeat(count) { index ->
                    val sample = (sin((frame + index) * 2.0 * Math.PI * 220 / 16000) * 96).toInt()
                    buffer[index * 2] = sample.toByte()
                    buffer[index * 2 + 1] = (sample shr 8).toByte()
                }
                output.write(buffer, 0, count * 2)
                frame += count
            }
        }
        store.duration(id, metadataDuration)
        store.state(id, "READY")
        return store.note(id)!!
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && errors.isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out. ${player.playback.value}; errors=$errors", condition())
    }
}
