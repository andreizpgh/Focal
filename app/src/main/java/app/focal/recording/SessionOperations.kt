package app.focal.recording

import java.util.UUID

/**
 * Process-wide ownership, reserved before dispatching service or disk work. At most one capture (the
 * microphone session) exists; transcription owns the notes it processes, one claim per note. A capture
 * can start while transcription runs (it preempts the queue); playback waits for capture to end, and
 * destructive operations exclude every owned note.
 */
internal object SessionOperations {
    private val lock = Any()
    private var captureClaim: SessionClaim? = null
    private val processing = LinkedHashMap<String, SessionClaim>()
    private val destructive = mutableMapOf<String, DestructiveClaim>()
    private val playback = mutableMapOf<PlaybackLease, PlaybackOwner>()

    private data class PlaybackOwner(val noteId: String, val stop: () -> Unit)

    class SessionClaim internal constructor(val token: String, val capture: Boolean, internal var noteId: String?) {
        internal var accepted = false
        internal var captureFinished = false

        fun bindNote(id: String) = synchronized(lock) {
            check(held() && (noteId == null || noteId == id)) { "This recording is no longer active." }
            check(id !in destructive) { "This session is being changed. Try again when it finishes." }
            noteId = id
        }

        /** The source is closed; processing may now coexist with playback. */
        fun finishCapture() = synchronized(lock) { if (captureClaim === this) captureFinished = true }

        fun release() = synchronized(lock) {
            if (captureClaim === this) captureClaim = null
            noteId?.let { if (processing[it] === this) processing.remove(it) }
            Unit
        }

        internal fun held(): Boolean = captureClaim === this || (noteId != null && processing[noteId] === this)
    }

    class DestructiveClaim internal constructor(private val noteId: String) {
        fun release() = synchronized(lock) { if (destructive[noteId] === this) destructive.remove(noteId) }
    }

    class PlaybackLease internal constructor() {
        fun release() = synchronized(lock) { playback.remove(this); Unit }
    }

    /**
     * A capture claim ([capture] true; stops registered players before it returns) or a processing claim
     * for [noteId]. Null when that slot is taken or the note is being changed.
     */
    fun reserveSession(capture: Boolean, noteId: String?): SessionClaim? {
        val token = UUID.randomUUID().toString()
        val (claim, stops) = synchronized(lock) {
            if (capture) {
                if (captureClaim != null) return null
                val claim = SessionClaim(token, true, null)
                captureClaim = claim
                claim to playback.values.map { it.stop }
            } else {
                if (noteId == null || noteId in processing || noteId in destructive || captureClaim?.noteId == noteId) return null
                val claim = SessionClaim(token, false, noteId)
                processing[noteId] = claim
                claim to emptyList()
            }
        }
        try { stops.forEach { it() } }
        catch (error: Exception) { claim.release(); throw error }
        return claim
    }

    /** The capture slot is held by a recording whose microphone is already closed (it is finishing). */
    fun captureFinishing(): Boolean = synchronized(lock) { captureClaim?.captureFinished == true }

    /** A second tap is idempotent; a different operation is rejected visibly by its caller. */
    fun matchesSession(capture: Boolean, noteId: String?): Boolean = synchronized(lock) {
        if (capture) captureClaim != null else noteId != null && noteId in processing
    }

    fun releasePending(token: String?) = synchronized(lock) {
        if (token == null) return@synchronized
        captureClaim?.takeIf { it.token == token && !it.accepted }?.let { captureClaim = null }
        processing.entries.removeAll { it.value.token == token && !it.value.accepted }
    }

    /** Raw service intents must take the same ownership path as Activity commands. */
    fun acceptSession(token: String?, capture: Boolean, noteId: String?): SessionClaim? {
        val claim = if (token == null) reserveSession(capture, noteId) else synchronized(lock) {
            if (capture) captureClaim?.takeIf { it.token == token }
            else noteId?.let { processing[it] }?.takeIf { it.token == token }
        }
        return synchronized(lock) {
            claim?.takeIf { it.held() && !it.accepted }?.also { it.accepted = true }
        }
    }

    /**
     * Moves a finished capture's note to transcription: the capture slot is free for the next recording
     * and the note stays owned. Returns the processing claim (accepted), or null if [claim] is not current.
     */
    fun handOver(claim: SessionClaim): SessionClaim? = synchronized(lock) {
        if (captureClaim !== claim) return null
        captureClaim = null
        val id = claim.noteId ?: return null
        processing[id]?.let { return it }
        SessionClaim(UUID.randomUUID().toString(), false, id).also { it.accepted = true; processing[id] = it }
    }

    fun tryDestructive(noteId: String): DestructiveClaim? {
        val (claim, stops) = synchronized(lock) {
            val active = captureClaim
            // Until a new capture has its UUID, its just-created row can already
            // be observed by another controller. Keep that short startup safe.
            if (noteId in destructive || noteId in processing || active?.noteId == noteId ||
                (active != null && active.noteId == null)) return null
            val claim = DestructiveClaim(noteId)
            destructive[noteId] = claim
            claim to playback.values.filter { it.noteId == noteId }.map { it.stop }
        }
        try { stops.forEach { it() } }
        catch (error: Exception) { claim.release(); throw error }
        return claim
    }

    fun tryAcquirePlayback(noteId: String, onStop: () -> Unit): PlaybackLease? = synchronized(lock) {
        val active = captureClaim
        if ((active != null && !active.captureFinished) || noteId in destructive) return null
        PlaybackLease().also { playback[it] = PlaybackOwner(noteId, onStop) }
    }
}
