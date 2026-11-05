package app.focal.speech

import android.content.Context
import android.content.res.AssetManager
import android.os.Process
import android.util.Log
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
    /** Not looked at yet (the first moments after process start). */
    data object Checking : ModelStatus

    /** A checks-only build made without the model: recordings are kept but not transcribed. */
    data object NotBundled : ModelStatus

    /** First launch (or after an update that changed the model): copying out of the APK. */
    data class Installing(val copiedBytes: Long, val totalBytes: Long) : ModelStatus {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Ready(val files: Map<String, File>) : ModelStatus

    /** The copy failed (usually storage); [retry] tries again, as does the next foreground start. */
    data class Failed(val message: String) : ModelStatus
}

/**
 * Installs the speech model set once, in the background, from the first launch: a staging directory,
 * SHA-256 while copying, fsync, an atomic rename and a marker keyed by the lock's hashes. Later sessions
 * only check the marker and file sizes. Transcription waits for [awaitFiles]; recording never does.
 */
class SpeechModels internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val status: StateFlow<ModelStatus> = state.asStateFlow()
    private var job: Job? = null

    private val directory get() = File(context.noBackupFilesDir, UltraModel.directoryName)

    /** Starts the install unless it is running or done. Safe to call at every foreground start. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        when (state.value) {
            is ModelStatus.Ready, ModelStatus.NotBundled -> return
            // A waiter must not mistake the previous failure for the retry's result.
            is ModelStatus.Failed -> state.value = ModelStatus.Checking
            else -> Unit
        }
        job = scope.launch(Dispatchers.IO) { install() }
    }

    /** Tries a failed install again. */
    fun retry() = start()

    /** The installed files, waiting while the copy runs; null when there is no usable model. */
    suspend fun awaitFiles(): Map<String, File>? {
        start()
        return when (val settled = state.first { it !is ModelStatus.Checking && it !is ModelStatus.Installing }) {
            is ModelStatus.Ready -> settled.files
            else -> null
        }
    }

    private fun install() {
        val previousPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            UltraModel.installed(directory)?.let { state.value = ModelStatus.Ready(it); return }
            if (!UltraModel.isBundled(context)) { state.value = ModelStatus.NotBundled; return }
            val total = UltraModel.totalBytes
            state.value = ModelStatus.Installing(0, total)
            var published = 0L
            val files = UltraModel.installOnce(
                directory,
                openAsset = { name ->
                    try {
                        context.assets.open(name, AssetManager.ACCESS_STREAMING)
                    } catch (error: FileNotFoundException) {
                        throw IllegalStateException(MODEL_MISSING, error)
                    }
                },
                onProgress = { copied ->
                    // About two hundred updates over the whole copy.
                    if (copied - published >= total / 200 || copied == total) {
                        published = copied
                        state.value = ModelStatus.Installing(copied, total)
                    }
                },
            )
            state.value = ModelStatus.Ready(files)
        } catch (error: Exception) {
            Log.w(TAG, "Speech model install failed", error)
            state.value = ModelStatus.Failed(error.message ?: "The speech model could not be prepared.")
        } finally {
            Process.setThreadPriority(previousPriority)
        }
    }

    private companion object { const val TAG = "FocalModel" }
}
