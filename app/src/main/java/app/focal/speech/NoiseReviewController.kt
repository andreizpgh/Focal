package app.focal.speech

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal interface SpeechPresenceDetector : AutoCloseable {
    fun hasSpeech(samples: FloatArray, checkCancelled: () -> Unit = {}): Boolean
}

internal enum class NoiseReviewPhase { Checking, Stopping, Ready, Failed }
internal data class NoiseReviewState(
    val noteId: String,
    val phase: NoiseReviewPhase = NoiseReviewPhase.Checking,
    val checked: Int = 0,
    val total: Int = 0,
    val proposals: List<Passage> = emptyList(),
    val error: String? = null,
)

/** Read-only foreground analysis; only an explicit Keep/Exclude writes a decision. */
internal class NoiseReviewController(
    private val store: NoteStore,
    private val scope: CoroutineScope,
    private val openDetector: (checkCancelled: () -> Unit) -> SpeechPresenceDetector,
) : AutoCloseable {
    private class Run(val claim: SessionOperations.SessionClaim, val noteId: String) {
        val stopped = AtomicBoolean()
    }
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<NoiseReviewState?>(null)
    val state = mutableState.asStateFlow()
    private var active: Run? = null
    private var closed = false

    @MainThread fun start(noteId: String): Boolean {
        checkMain()
        if (closed || active != null || scope.coroutineContext[Job]?.isActive == false) return false
        val claim = SessionOperations.reserveSession(capture = false, noteId = noteId) ?: return false
        val run = Run(claim, noteId)
        active = run
        mutableState.value = NoiseReviewState(noteId)
        scope.launch(Dispatchers.Main.immediate) {
            var result: NoiseReviewState? = null
            try {
                result = withContext(Dispatchers.IO) { checkRecording(run) }
            } catch (_: CancellationException) {
                // The blocking detector has returned and closed before IO completes.
            } catch (error: Exception) {
                result = NoiseReviewState(noteId, NoiseReviewPhase.Failed,
                    error = error.message ?: "Could not check background sound. Your text is unchanged.")
            } finally {
                finish(run, result.takeIf { isActive })
            }
        }.invokeOnCompletion {
            // Also covers a scope cancelled before its coroutine body starts.
            claim.release()
            if (Looper.myLooper() == Looper.getMainLooper()) finish(run, null)
            else main.post { finish(run, null) }
        }
        return true
    }

    @MainThread fun stop() {
        checkMain()
        val run = active
        if (run == null) mutableState.value = null
        else if (run.stopped.compareAndSet(false, true)) {
            mutableState.value = mutableState.value?.copy(phase = NoiseReviewPhase.Stopping)
        }
    }

    /** Removes a proposal only after its explicit decision has been persisted. */
    @MainThread fun resolved(passageId: Long) {
        checkMain()
        mutableState.value = mutableState.value?.let { current ->
            current.copy(proposals = current.proposals.filterNot { it.id == passageId })
        }
    }

    @MainThread override fun close() { checkMain(); closed = true; stop() }

    private suspend fun checkRecording(run: Run): NoiseReviewState {
        val context = currentCoroutineContext()
        fun checkCancelled() {
            context.ensureActive()
            if (run.stopped.get()) throw CancellationException("Background sound check stopped.")
        }
        checkCancelled()
        val note = checkNotNull(store.note(run.noteId)) { "This recording is no longer available." }
        check(note.state == "READY" && store.pendingCount(note.id) == 0 && !store.hasUnpublishedTail(note)) {
            "Finish transcription before checking background sound."
        }
        val audio = store.audioFile(note.id)
        val audioBytes = audio.length()
        check(audio.isFile && audioBytes > 0) { "Original audio is unavailable." }
        check(audioBytes <= MAX_DURATION_MS * 32 && note.duration <= MAX_DURATION_MS) {
            "Background sound checks are available for recordings up to 15 minutes."
        }
        val chunks = store.completedChunks(note.id)
        check(chunks.size <= MAX_CHUNKS) { "This recording has too many passages to check at once." }
        val proposals = ArrayList<Passage>()
        var detector: SpeechPresenceDetector? = null
        try {
            for ((index, chunk) in chunks.withIndex()) {
                checkCancelled()
                val candidates = store.noiseCandidatePassages(note.id, chunk.id)
                if (candidates.isNotEmpty()) {
                    val pcm = store.readChunk(chunk)
                    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = FloatArray(buffer.remaining()) { buffer.get() / 32768f }
                    val current = detector ?: openDetector(::checkCancelled).also { detector = it }
                    checkCancelled()
                    if (!current.hasSpeech(samples, ::checkCancelled)) {
                        check(proposals.size + candidates.size <= MAX_PROPOSALS) { "Too many passages to check at once." }
                        proposals.addAll(candidates)
                    }
                    checkCancelled()
                    check(audio.length() == audioBytes) { "The original audio changed during this check." }
                }
                // Do not suspend the IO block: the native detector stays on its owner thread.
                main.post {
                    if (active === run && context.isActive && !run.stopped.get()) mutableState.value =
                        NoiseReviewState(note.id, checked = index + 1, total = chunks.size)
                }
            }
        } finally {
            detector?.close()
        }
        checkCancelled()
        return NoiseReviewState(note.id, NoiseReviewPhase.Ready, chunks.size, chunks.size, proposals)
    }

    private fun finish(run: Run, result: NoiseReviewState?) {
        checkMain()
        if (active !== run) return
        run.claim.release()
        active = null
        mutableState.value = result.takeUnless { closed || run.stopped.get() }
    }

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Noise review commands must run on Main." } }

    companion object {
        const val MAX_DURATION_MS = 15 * 60 * 1000L
        private const val MAX_CHUNKS = 256
        private const val MAX_PROPOSALS = 512
    }
}
