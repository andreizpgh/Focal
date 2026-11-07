package app.focal.speech

import app.focal.BuildConfig

/**
 * JNI entry points of libfocal_speech.so (src/main/cpp/focal_speech.cpp), Focal's bridge over the
 * sherpa-onnx C API. Use [OfflineRecognizer] and [SileroVad] instead of calling these directly.
 *
 * Failures arrive as IllegalArgumentException, IllegalStateException or OutOfMemoryError. A missing
 * or incompatible native library surfaces as a [LinkageError] on first use.
 */
internal object SherpaBridge {
    init {
        System.loadLibrary("focal_speech")
    }

    /** sherpa-onnx version, git revision, git date and ONNX Runtime version. */
    @JvmStatic external fun runtimeInfo(): Array<String>

    /**
     * Parakeet TDT (sherpa-onnx "nemo_transducer"), greedy search, on the CPU: the bridge fixes those, because
     * sherpa-onnx exits the process on an unknown decoding method or a mismatched model type.
     * [providerConfig] names an ONNX Runtime session options file, or null.
     */
    @JvmStatic external fun createRecognizer(
        encoder: String, decoder: String, joiner: String, tokens: String,
        numThreads: Int, providerConfig: String?,
    ): Long

    @JvmStatic external fun destroyRecognizer(handle: Long)

    /** See [RESULT_FIELDS] for the layout of the returned array. */
    @JvmStatic external fun decode(handle: Long, samples: FloatArray, offset: Int, length: Int): Array<Any?>

    @JvmStatic external fun createVad(
        model: String, threshold: Float, minSilenceSeconds: Float, minSpeechSeconds: Float,
        windowSize: Int, maxSpeechSeconds: Float, numThreads: Int,
    ): Long

    @JvmStatic external fun vadAcceptWindow(handle: Long, window: FloatArray): Boolean

    @JvmStatic external fun vadReset(handle: Long)

    @JvmStatic external fun destroyVad(handle: Long)

    /**
     * Opens the engine guard's crash record at [path] for appending and installs handlers for the fatal signals
     * (SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT) that append one line to it before the previous handler runs. False
     * when the record could not be opened or a handler not installed.
     */
    @JvmStatic external fun installCrashHandlers(path: String): Boolean

    // Decode result layout, shared with focal_speech.cpp.
    const val RESULT_TEXT = 0
    const val RESULT_TOKEN_BYTES = 1
    const val RESULT_TOKEN_ENDS = 2
    const val RESULT_TIMESTAMPS = 3
    const val RESULT_DURATIONS = 4
    const val RESULT_LOG_PROBS = 5
    const val RESULT_FIELDS = 6
}

/** The loaded sherpa-onnx runtime, checked against the lock the build verified. */
internal object SpeechRuntime {
    data class Info(val version: String, val revision: String, val date: String, val onnxruntime: String)

    val info: Info by lazy {
        val values = SherpaBridge.runtimeInfo()
        check(values.size == 4) { "Unexpected speech runtime." }
        Info(values[0], values[1], values[2], values[3])
    }

    /** Throws unless the loaded libraries are the ones models/sherpa-onnx.json pins. */
    fun requirePinned() {
        val loaded = info
        // The library reports an abbreviated revision; the lock holds the full commit.
        check(loaded.version == BuildConfig.SPEECH_RUNTIME_VERSION && loaded.revision.length >= 8 &&
            BuildConfig.SPEECH_RUNTIME_REVISION.startsWith(loaded.revision) &&
            loaded.onnxruntime == BuildConfig.ONNXRUNTIME_VERSION) { "Unexpected speech runtime: $loaded" }
    }
}
