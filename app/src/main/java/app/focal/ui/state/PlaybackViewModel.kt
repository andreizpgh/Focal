package app.focal.ui.state

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.FocalApplication
import app.focal.R
import app.focal.recording.RecordingState
import app.focal.speech.SpeechPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Playback of a note's original audio. [session] changes only when playback starts, pauses, stops or moves to
 * another note; the position is read with [positionMs] inside draw, layout or frame callbacks (the player
 * strip, the playing-word lens, follow-scroll), so it never recomposes a screen root. Keep one per activity.
 */
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val player = SpeechPlayer(app, app.store, viewModelScope)
    private val errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Playback failures, as the app's own sentence (the player's reasons are internal). */
    val errors: SharedFlow<String> = errorFlow.asSharedFlow()
    private val failed: (String) -> Unit = { report(R.string.error_playback) }

    val session: StateFlow<PlaybackSession> = player.playback
        .map { PlaybackSession(it.noteId, it.isPlaying, it.durationMs) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSession())

    /** The position now, in ms of the original recording. Thread-safe; call per frame, not in composition. */
    fun positionMs(): Long = player.positionMs()

    /** Tap on a word: plays from [PREROLL_MS] before it (or before the paragraph when its time is unknown). */
    fun playWord(noteId: String, paragraph: ParagraphUi, charOffset: Int) {
        val at = paragraph.seekMs(charOffset) ?: paragraph.startMs
        playFrom(noteId, (at - PREROLL_MS).coerceAtLeast(0))
    }

    fun playFrom(noteId: String, positionMs: Long) {
        if (blocked()) return
        player.playFrom(noteId, positionMs, failed)
    }

    /** Plays exactly [startMs, endMs), for example a gap that could not be transcribed. */
    fun playRange(noteId: String, startMs: Long, endMs: Long) {
        if (blocked()) return
        player.playRange(noteId, startMs, endMs, failed)
    }

    /** ▶ / ‖: pauses this note's playback, or resumes it where it stopped. */
    fun toggle(noteId: String) {
        if (blocked()) return
        player.toggle(noteId, failed)
    }

    fun pause() = player.pause()

    /** Stops playback of [noteId] only (its audio is being removed, or it was deleted). */
    fun stopNote(noteId: String) = player.stopNote(noteId)

    private fun blocked(): Boolean {
        // The speaker must never leak into a recording.
        if (RecordingState.live.value.capturing) { report(R.string.error_playback_recording); return true }
        return false
    }

    private fun report(@StringRes message: Int) { errorFlow.tryEmit(app.getString(message)) }

    override fun onCleared() = player.stop()

    companion object {
        /** Playback starts this long before the tapped word. */
        const val PREROLL_MS = 500L
    }
}

/** The word under the playhead: paragraph [paragraphIndex] and word [wordIndex] of a note's [ParagraphUi] list. */
data class WordLocation(val paragraphIndex: Int, val wordIndex: Int?)

object PlayingWord {
    /**
     * Locates [positionMs] in [paragraphs] (ordered by time): the paragraph containing it (or the last one
     * that started before it) and, when that paragraph has [ParagraphUi.exactTimes], the word being spoken
     * (or the last one that started). Binary searches: cheap enough to call every frame.
     */
    fun locate(paragraphs: List<ParagraphUi>, positionMs: Long): WordLocation? {
        var low = 0
        var high = paragraphs.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (paragraphs[mid].startMs <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1
        }
        if (found < 0) return null
        val paragraph = paragraphs[found]
        if (!paragraph.exactTimes) return WordLocation(found, null)
        val words = paragraph.words
        var lo = 0
        var hi = words.lastIndex
        var word = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (checkNotNull(words[mid].startMs) <= positionMs) { word = mid; lo = mid + 1 } else hi = mid - 1
        }
        return WordLocation(found, word.takeIf { it >= 0 })
    }
}
