package app.focal.speech

import android.content.Context
import java.io.File

/** Ordinary builds neither expose the experiment nor include its native runtime. */
internal object PreviewEngineFactory {
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    fun open(directory: File, threads: Int, checkCancelled: () -> Unit = {}): SavedAudioPreview.Decoder {
        checkCancelled()
        error("Alternative transcription is not available in this build.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun openBundled(context: Context, threads: Int, checkCancelled: () -> Unit): SavedAudioPreview.Decoder {
        checkCancelled()
        error("Alternative transcription is not available in this build.")
    }
}
