package app.focal.speech

import android.content.Context
import android.content.res.AssetManager
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.focal.data.WaitCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

/** Where the bundled speech model set stands. Recording never waits for it. */
sealed interface ModelStatus {
    /** Not looked at yet (the first moments after process start), or a retry about to start. */
    data object Checking : ModelStatus

    /** A checks-only build made without the model: recordings are kept but not transcribed. */
    data object NotBundled : ModelStatus

    /** First launch (or after an update that changed the model): copying out of the APK. */
    data class Installing(val copiedBytes: Long, val totalBytes: Long) : ModelStatus {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Ready(val files: Map<String, File>) : ModelStatus

    /**
     * The copy failed, as [failure] says. Usually storage ([shortBytes] more are needed): [SpeechModels.retry] tries
     * again, as does the next foreground start. [permanent] failures (the APK's copy is damaged or missing, or the
     * engine guard stopped the speech work) need the person: only their own Try again tries those again.
     */
    data class Failed(
        val message: String,
        val permanent: Boolean = false,
        val failure: ModelFailure = ModelFailure.INSTALL,
        val shortBytes: Long = 0,
    ) : ModelStatus
}

/** Why the model install failed, as a waiting note names it. */
enum class ModelFailure {
    /** Too little free storage for the copy. */
    STORAGE,
    /** Anything else: a damaged or missing copy in the APK, a write that failed. */
    INSTALL,
    /** Not tried: the engine guard stopped the speech work. */
    ENGINE_STOPPED,
}

/** The cause a waiting note stores, and names, for this failed install. */
fun ModelStatus.Failed.waitCause(): WaitCause = when (failure) {
    ModelFailure.STORAGE -> WaitCause.ModelStorage(shortBytes)
    ModelFailure.INSTALL -> WaitCause.ModelInstall
    ModelFailure.ENGINE_STOPPED -> WaitCause.EngineStopped
}

/** Too little free storage to copy the model: [shortBytes] more are needed (rounded up as the interface says it). */
internal class InsufficientStorageException(val shortBytes: Long) : IllegalStateException("Free up space to prepare the speech model.")

/** The APK's copy of the model cannot be used (missing or damaged): retrying cannot help. */
internal class BundledModelException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Installs the speech model set once, in the background, from the first launch: a staging directory,
 * SHA-256 while copying, fsync, an atomic rename and a marker keyed by the lock's hashes. Later sessions
 * only check the marker and file sizes. Transcription waits for [awaitFiles]; recording never does.
 *
 * A failed install stays failed until something asks for a retry ([retry]): [ensureStarted], which the
 * speech worker may call as often as it likes, never repeats it. Each attempt runs inside [guard]'s install
 * stage, whose deaths are logged but never block; while the guard is blocked (a later stage kept killing the
 * process) no attempt starts by itself, and the status is a failure only the person's Retry, after the guard's reset,
 * tries again.
 */
class SpeechModels internal constructor(
    private val scope: CoroutineScope,
    private val guard: EngineGuard? = null,
    /** Runs one install attempt, publishing progress through its argument, and returns how it ended. */
    private val installer: (publish: (ModelStatus) -> Unit) -> ModelStatus,
) {
    constructor(context: Context, scope: CoroutineScope, guard: EngineGuard) : this(scope, guard, { publish -> install(context, publish) })

    private val state = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val status: StateFlow<ModelStatus> = state.asStateFlow()
    private var job: Job? = null

    /** Starts the first install attempt unless it already ran. Never repeats a failed one. */
    @Synchronized
    fun ensureStarted() {
        if (job != null || state.value != ModelStatus.Checking) return
        launch()
    }

    /**
     * Tries a failed install again: always for the user's Retry, and at a foreground start ([automatic])
     * unless the failure needs a reinstall. Starts the first attempt if none ran yet.
     */
    @Synchronized
    fun retry(automatic: Boolean = false) {
        val current = state.value
        if (current !is ModelStatus.Failed) { ensureStarted(); return }
        if (job?.isActive == true || (automatic && current.permanent)) return
        // A waiter must not mistake the previous failure for the retry's result.
        state.value = ModelStatus.Checking
        launch()
    }

    /**
     * Device tests: the install ends as [status] says (a full or failing storage the device cannot be made to have);
     * [retry] tries it again for real.
     */
    @VisibleForTesting
    @Synchronized
    fun settleForTests(status: ModelStatus.Failed) {
        job?.cancel()
        job = null
        state.value = status
    }

    /** The installed files, waiting while the copy runs; null when there is no usable model. */
    suspend fun awaitFiles(): Map<String, File>? {
        ensureStarted()
        return when (val settled = state.first { it !is ModelStatus.Checking && it !is ModelStatus.Installing }) {
            is ModelStatus.Ready -> settled.files
            else -> null
        }
    }

    private fun launch() {
        job = scope.launch(Dispatchers.IO) {
            if (guard?.allows() == false) {
                state.value = ModelStatus.Failed(ENGINE_STOPPED, permanent = true, failure = ModelFailure.ENGINE_STOPPED)
                return@launch
            }
            state.value = try {
                val installed = guard?.inside(EngineStage.INSTALL) { installer { state.value = it } } ?: installer { state.value = it }
                if (installed is ModelStatus.Ready) guard?.completed(EngineStage.INSTALL)
                installed
            } catch (error: Exception) {
                runCatching { Log.w(TAG, "Speech model install failed", error) } // never decides the outcome
                val storage = error as? InsufficientStorageException
                ModelStatus.Failed(
                    error.message ?: "The speech model could not be prepared.", error is BundledModelException,
                    if (storage != null) ModelFailure.STORAGE else ModelFailure.INSTALL, storage?.shortBytes ?: 0,
                )
            }
        }
    }

    private companion object {
        const val TAG = "FocalModel"

        fun install(context: Context, publish: (ModelStatus) -> Unit): ModelStatus {
            val previousPriority = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val directory = File(context.noBackupFilesDir, UltraModel.directoryName)
                UltraModel.installed(directory)?.let { return ModelStatus.Ready(it) }
                if (!UltraModel.isBundled(context)) return ModelStatus.NotBundled
                val total = UltraModel.totalBytes
                publish(ModelStatus.Installing(0, total))
                var published = 0L
                val files = UltraModel.installOnce(
                    directory,
                    openAsset = { name ->
                        try {
                            context.assets.open(name, AssetManager.ACCESS_STREAMING)
                        } catch (error: FileNotFoundException) {
                            throw BundledModelException(MODEL_MISSING, error)
                        }
                    },
                    onProgress = { copied ->
                        // About two hundred updates over the whole copy.
                        if (copied - published >= total / 200 || copied == total) {
                            published = copied
                            publish(ModelStatus.Installing(copied, total))
                        }
                    },
                )
                return ModelStatus.Ready(files)
            } finally {
                Process.setThreadPriority(previousPriority)
            }
        }
    }
}
