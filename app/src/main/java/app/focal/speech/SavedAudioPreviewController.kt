package app.focal.speech

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import app.focal.core.Transcript
import app.focal.data.NoteStore
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PreviewPhase { Loading, Running, Stopping, Ready, Failed }

internal data class PreviewState(
    val noteId: String,
    val title: String,
    val phase: PreviewPhase,
    val completedFrames: Int = 0,
    val totalFrames: Int = 0,
    val segments: List<Transcript.Segment> = emptyList(),
    val error: String? = null,
)

/** Owns a preview for one ViewModel. Public state and UI commands are confined to Main. */
internal class SavedAudioPreviewController(
    private val store: NoteStore,
    private val scope: CoroutineScope,
    private val openDecoder: (checkCancelled: () -> Unit) -> SavedAudioPreview.Decoder,
) : AutoCloseable {
    private class Run(
        val claim: SessionOperations.SessionClaim,
        initial: PreviewState,
    ) {
        val stopped = AtomicBoolean()
        val progress = MutableStateFlow(initial)
    }

    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<PreviewState?>(null)
    val state: StateFlow<PreviewState?> = mutableState.asStateFlow()
    private var active: Run? = null
    private var closed = false

    /** Returns false when another preview or a conflicting session already owns the work. */
    @MainThread
    fun start(noteId: String): Boolean {
        checkMain()
        if (closed || active != null || scope.coroutineContext[Job]?.isActive == false) return false
        val claim = SessionOperations.reserveSession(capture = false, noteId = noteId) ?: return false
        val initial = PreviewState(noteId, title = "", phase = PreviewPhase.Loading)
        val run = Run(claim, initial)
        active = run
        mutableState.value = initial

        scope.launch(Dispatchers.Main.immediate) {
            var result: PreviewState? = null
            val progress = launch {
                run.progress.collect { next ->
                    if (active === run && !run.stopped.get()) mutableState.value = next
                }
            }
            try {
                result = withContext(Dispatchers.IO) { preview(run) }
            } catch (_: CancellationException) {
                // The synchronous IO block has returned before this coroutine can finish.
            } catch (failure: Exception) {
                result = run.progress.value.copy(phase = PreviewPhase.Failed,
                    error = failure.message ?: "Could not prepare the alternative preview.")
            } finally {
                progress.cancel()
                finish(run, result.takeIf { isActive })
            }
        }.invokeOnCompletion {
            // A canceled scope may never enter the launch body. Release only
            // after completion, when no child can still own the decoder or file.
            claim.release()
            if (Looper.myLooper() == Looper.getMainLooper()) finish(run, null)
            else main.post { finish(run, null) }
        }
        return true
    }

    @MainThread
    fun stop() {
        checkMain()
        val run = active
        if (run == null) {
            mutableState.value = null
        } else if (run.stopped.compareAndSet(false, true)) {
            mutableState.value = run.progress.value.copy(phase = PreviewPhase.Stopping)
        }
    }

    @MainThread
    override fun close() {
        checkMain()
        closed = true
        stop()
    }

    private suspend fun preview(run: Run): PreviewState {
        val context = currentCoroutineContext()
        fun checkCancelled() {
            context.ensureActive()
            if (run.stopped.get()) throw CancellationException("Alternative preview stopped.")
        }
        checkCancelled()
        val initial = run.progress.value
        val note = checkNotNull(store.note(initial.noteId)) { "This recording is no longer available." }
        val loading = initial.copy(title = note.title)
        run.progress.value = loading
        check(note.state == "READY" && store.pendingCount(note.id) == 0 && !store.hasUnpublishedTail(note)) {
            "Finish the original transcription before opening an alternative preview."
        }
        val file = store.audioFile(note.id)
        check(file.isFile && file.length() > 0) { "The original recording is no longer available." }
        checkCancelled()
        val running = loading.copy(phase = PreviewPhase.Running)
        run.progress.value = running
        val segments = SavedAudioPreview.process(file, openDecoder, ::checkCancelled) { covered, total ->
            run.progress.value = running.copy(completedFrames = covered, totalFrames = total)
        }
        checkCancelled()
        return run.progress.value.copy(phase = PreviewPhase.Ready, segments = segments)
    }

    private fun finish(run: Run, result: PreviewState?) {
        checkMain()
        if (active !== run) return
        run.claim.release()
        active = null
        mutableState.value = result.takeUnless { closed || run.stopped.get() }
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Preview commands must run on Main." }
    }
}
