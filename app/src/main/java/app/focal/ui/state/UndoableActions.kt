package app.focal.ui.state

import app.focal.FocalApplication
import app.focal.data.NoteState
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Delete and Remove audio with Undo ("Note deleted · Undo", "Audio removed · Undo"). Delete moves the note
 * to the trash at once (it leaves the library and search) and purges it after [UNDO_WINDOW_MS]; a trashed
 * note left by process death is purged at the next start. Remove audio is only scheduled: the audio is
 * deleted after the window unless undone, so an interrupted removal keeps the audio (the safe side).
 * Shared by the library and note screens; process-wide, because the snackbar outlives a screen.
 */
object UndoableActions {
    const val UNDO_WINDOW_MS = 6_000L

    private val removals = mutableMapOf<String, Job>()
    private val deletions = mutableMapOf<String, Job>()
    private val pendingRemovals = MutableStateFlow<Set<String>>(emptySet())

    /** Notes whose audio removal waits for its undo window: show them as "Text only" already. */
    val audioRemovalsPending: StateFlow<Set<String>> = pendingRemovals.asStateFlow()

    /** Moves [noteId] to the trash. False (with [onError]) when it is busy or not deletable now. */
    suspend fun delete(app: FocalApplication, noteId: String, onError: (String) -> Unit): Boolean {
        val claim = SessionOperations.tryDestructive(noteId)
            ?: return false.also { onError("This note is busy. Try again when its current operation finishes.") }
        val deleted = try { app.store.deleteWithUndo(noteId) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            onError("Could not delete this note."); false
        } finally { claim.release() }
        if (!deleted) { onError("Finish this recording or transcription before deleting it."); return false }
        synchronized(this) {
            deletions.remove(noteId)?.cancel()
            deletions[noteId] = app.scope.launch {
                delay(UNDO_WINDOW_MS)
                synchronized(this@UndoableActions) { deletions.remove(noteId) }
                runCatching { app.store.purgeDeleted(noteId) }
            }
        }
        return true
    }

    suspend fun undoDelete(app: FocalApplication, noteId: String): Boolean {
        synchronized(this) { deletions.remove(noteId)?.cancel() }
        return app.store.undoDelete(noteId)
    }

    /**
     * Schedules the removal of [noteId]'s audio (the text stays). Refused (false, with [onError]) unless the
     * note is ready with nothing left to transcribe and no failed part that Retry would still need it for.
     */
    suspend fun removeAudio(app: FocalApplication, noteId: String, onError: (String) -> Unit): Boolean {
        val refusal = try { withContext(Dispatchers.IO) { removalRefusal(app, noteId) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            error.message ?: "Could not remove the audio."
        }
        if (refusal != null) { onError(refusal); return false }
        synchronized(this) {
            removals.remove(noteId)?.cancel()
            pendingRemovals.update { it + noteId }
            removals[noteId] = app.scope.launch {
                delay(UNDO_WINDOW_MS)
                synchronized(this@UndoableActions) { removals.remove(noteId) }
                try { commitRemoval(app, noteId) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onError(error.message ?: "Could not remove the audio.")
                } finally { pendingRemovals.update { it - noteId } }
            }
        }
        return true
    }

    fun undoRemoveAudio(noteId: String): Boolean = synchronized(this) {
        val job = removals.remove(noteId) ?: return false
        job.cancel()
        pendingRemovals.update { it - noteId }
        true
    }

    /** Why the audio of [noteId] cannot be removed now, or null. */
    private fun removalRefusal(app: FocalApplication, noteId: String): String? {
        val note = app.store.note(noteId) ?: return "This note is no longer available."
        if (!note.audioPresent) return "This note has no audio."
        if (RecordingState.live.value.noteId == noteId || note.state != NoteState.READY || app.store.hasUntranscribedAudio(note))
            return "Finish transcription before removing its source audio."
        if (note.failedWindows > 0) return FAILED_PARTS_MESSAGE
        return null
    }

    private fun commitRemoval(app: FocalApplication, noteId: String) {
        val claim = checkNotNull(SessionOperations.tryDestructive(noteId)) { "This note is busy. Its audio was kept." }
        try {
            val note = checkNotNull(app.store.note(noteId)) { "This note is no longer available." }
            check(note.state == NoteState.READY) { "Finish transcription before removing its source audio." }
            app.store.removeAudio(noteId)
        } finally { claim.release() }
    }

    internal const val FAILED_PARTS_MESSAGE =
        "Part of this note couldn't be transcribed. Retry it before removing the audio."
}
