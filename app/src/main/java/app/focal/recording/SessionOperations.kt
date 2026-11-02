package app.focal.recording

import java.util.UUID

/** Process-wide ownership is reserved before dispatching service or disk work. */
internal object SessionOperations {
    private val lock = Any()
    private var session: SessionClaim? = null
    private val destructive = mutableMapOf<String, DestructiveClaim>()
    private val playback = mutableMapOf<PlaybackLease, PlaybackOwner>()

    private data class PlaybackOwner(val noteId: String, val stop: () -> Unit)

    class SessionClaim internal constructor(val token: String, val capture: Boolean, internal var noteId: String?) {
        internal var accepted = false
        internal var captureFinished = false

        fun bindNote(id: String) = synchronized(lock) {
            check(session === this && (noteId == null || noteId == id)) { "This recording is no longer active." }
            check(id !in destructive) { "This session is being changed. Try again when it finishes." }
            noteId = id
        }

        /** The source is closed; processing may now coexist with playback. */
        fun finishCapture() = synchronized(lock) { if (session === this) captureFinished = true }
        fun release() = synchronized(lock) { if (session === this) session = null }
    }

    class DestructiveClaim internal constructor(private val noteId: String) {
        fun release() = synchronized(lock) { if (destructive[noteId] === this) destructive.remove(noteId) }
    }

    class PlaybackLease internal constructor() {
        fun release() = synchronized(lock) { playback.remove(this); Unit }
    }

    /** Stops registered players outside the ownership lock, before capture can start. */
    fun reserveSession(capture: Boolean, noteId: String?): SessionClaim? {
        val token = UUID.randomUUID().toString()
        val (claim, stops) = synchronized(lock) {
            if (session != null || (!capture && noteId != null && noteId in destructive)) return null
            val claim = SessionClaim(token, capture, noteId)
            session = claim
            claim to if (capture) playback.values.map { it.stop } else emptyList()
        }
        try { stops.forEach { it() } }
        catch (error: Exception) { claim.release(); throw error }
        return claim
    }

    /** A second tap is idempotent; a different operation is rejected visibly by its caller. */
    fun matchesSession(capture: Boolean, noteId: String?): Boolean = synchronized(lock) {
        session?.let { it.capture == capture && (capture || it.noteId == noteId) } == true
    }

    fun releasePending(token: String?) = synchronized(lock) {
        if (token != null && session?.token == token && session?.accepted == false) session = null
    }

    /** Raw service intents must take the same ownership path as Activity commands. */
    fun acceptSession(token: String?, capture: Boolean, noteId: String?): SessionClaim? {
        val claim = if (token == null) reserveSession(capture, noteId) else synchronized(lock) {
            session?.takeIf { it.token == token && it.capture == capture && (capture || it.noteId == noteId) }
        }
        return synchronized(lock) {
            claim?.takeIf { session === it && !it.accepted }?.also { it.accepted = true }
        }
    }

    fun tryDestructive(noteId: String): DestructiveClaim? {
        val (claim, stops) = synchronized(lock) {
            val active = session
            // Until a new capture has its UUID, its just-created row can already
            // be observed by another controller. Keep that short startup safe.
            if (noteId in destructive || active?.noteId == noteId || (active?.capture == true && active.noteId == null)) return null
            val claim = DestructiveClaim(noteId)
            destructive[noteId] = claim
            claim to playback.values.filter { it.noteId == noteId }.map { it.stop }
        }
        try { stops.forEach { it() } }
        catch (error: Exception) { claim.release(); throw error }
        return claim
    }

    fun tryAcquirePlayback(noteId: String, onStop: () -> Unit): PlaybackLease? = synchronized(lock) {
        val active = session
        if ((active?.capture == true && !active.captureFinished) || noteId in destructive) return null
        PlaybackLease().also { playback[it] = PlaybackOwner(noteId, onStop) }
    }
}
