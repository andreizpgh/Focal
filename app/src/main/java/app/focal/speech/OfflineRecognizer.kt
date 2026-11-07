package app.focal.speech

import app.focal.core.AsrToken
import java.io.File

/**
 * One decoded window. [timestamps], [durations] and [logProbs] are parallel to [tokens]; times are
 * seconds from the first decoded sample. A token's text keeps the space that starts a word.
 */
internal class AsrResult(
    val text: String,
    val tokens: List<String>,
    val timestamps: FloatArray,
    val durations: FloatArray,
    /** Natural-log probability of each emitted token, or null when the runtime reported none. */
    val logProbs: FloatArray?,
) {
    init {
        require(timestamps.size == tokens.size && durations.size == tokens.size &&
            (logProbs == null || logProbs.size == tokens.size)) { "Token arrays differ in length." }
    }

    /** The tokens as the core formatter reads them. */
    fun asrTokens(): List<AsrToken> = List(tokens.size) { i ->
        AsrToken(tokens[i], timestamps[i].toDouble(), durations[i].toDouble(), logProbs?.get(i)?.toDouble())
    }

    companion object {
        /**
         * Checks the bridge's raw result: token timings are required whenever there are tokens, every
         * per-token array matches the token count, and all values are finite.
         */
        fun fromBridge(raw: Array<Any?>): AsrResult {
            check(raw.size == SherpaBridge.RESULT_FIELDS) { MALFORMED }
            val text = (raw[SherpaBridge.RESULT_TEXT] as? ByteArray ?: error(MALFORMED)).decodeToString()
            val tokenBytes = raw[SherpaBridge.RESULT_TOKEN_BYTES] as? ByteArray ?: error(MALFORMED)
            val ends = raw[SherpaBridge.RESULT_TOKEN_ENDS] as? IntArray ?: error(MALFORMED)
            val tokens = ArrayList<String>(ends.size)
            var start = 0
            for (end in ends) {
                check(end in start..tokenBytes.size) { MALFORMED }
                // Malformed UTF-8 becomes U+FFFD rather than failing the window.
                tokens += tokenBytes.decodeToString(start, end)
                start = end
            }
            check(start == tokenBytes.size) { MALFORMED }
            fun floats(index: Int): FloatArray? = raw[index]?.let { it as? FloatArray ?: error(MALFORMED) }
            val timestamps = floats(SherpaBridge.RESULT_TIMESTAMPS)
            val durations = floats(SherpaBridge.RESULT_DURATIONS)
            val logProbs = floats(SherpaBridge.RESULT_LOG_PROBS)
            // Without tokens the runtime returns no per-token arrays at all.
            if (tokens.isEmpty()) return AsrResult(text, tokens, FloatArray(0), FloatArray(0), FloatArray(0))
            check(timestamps != null && durations != null) { "The speech runtime returned no token timings." }
            check(timestamps.size == tokens.size && durations.size == tokens.size &&
                (logProbs == null || logProbs.size == tokens.size)) { "The speech runtime returned misaligned tokens." }
            check(timestamps.all { it.isFinite() && it >= 0f } && durations.all { it.isFinite() && it >= 0f } &&
                (logProbs == null || logProbs.all { it.isFinite() })) { "The speech runtime returned invalid token values." }
            return AsrResult(text, tokens, timestamps, durations, logProbs)
        }

        private const val MALFORMED = "The speech bridge returned a malformed result."
    }
}

/**
 * A non-streaming transducer recognizer (Parakeet TDT through the sherpa-onnx C API), loaded from
 * model files on storage. Calls are serialized; [close] waits for a running decode and releases
 * the model's memory. Decoding cannot be interrupted once it has started.
 */
internal class OfflineRecognizer private constructor(private var handle: Long) : AutoCloseable {
    data class Config(
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val tokens: File,
        /** ONNX Runtime intra- and inter-op threads, fixed for the recognizer's lifetime. */
        val threads: Int,
        /**
         * ONNX Runtime's CPU memory arena keeps the largest buffers it has served. Turning it off
         * saves memory on small devices and costs some speed on input lengths it has not seen.
         */
        val cpuMemArena: Boolean = true,
        /** Private directory for the session config file that turns the arena off. */
        val configDirectory: File? = null,
    ) {
        init {
            require(threads in 1..MAX_THREADS) { "Choose 1 to $MAX_THREADS inference threads." }
            require(cpuMemArena || configDirectory != null) { "Turning the arena off needs a config directory." }
        }
    }

    /** Decodes [length] samples of 16 kHz mono audio in [-1, 1] starting at [offset]. */
    @Synchronized
    fun decode(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): AsrResult {
        check(handle != 0L) { "The speech recognizer is closed." }
        require(offset >= 0 && length in 1..MAX_SAMPLES && offset <= samples.size - length) { "Invalid sample range." }
        for (i in offset until offset + length) {
            require(samples[i].isFinite() && samples[i] in -1f..1f) { "Invalid audio samples." }
        }
        return AsrResult.fromBridge(SherpaBridge.decode(handle, samples, offset, length))
    }

    @Synchronized
    override fun close() {
        val opened = handle
        if (opened != 0L) {
            handle = 0L
            SherpaBridge.destroyRecognizer(opened)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_THREADS = 8
        /** One minute; Focal's windows are far shorter. */
        const val MAX_SAMPLES = 60 * SAMPLE_RATE

        /** Loads the model (seconds, and most of a gigabyte of memory). Throws IllegalStateException on failure. */
        fun create(config: Config): OfflineRecognizer {
            val sessionConfig = if (config.cpuMemArena) null else leanSessionConfig(checkNotNull(config.configDirectory))
            val handle = SherpaBridge.createRecognizer(
                encoder = config.encoder.path,
                decoder = config.decoder.path,
                joiner = config.joiner.path,
                tokens = config.tokens.path,
                numThreads = config.threads,
                providerConfig = sessionConfig?.path,
            )
            check(handle != 0L) { "The speech recognizer could not be created." }
            return OfflineRecognizer(handle)
        }

        /**
         * sherpa-onnx reads ONNX Runtime session options from a file named in the provider string
         * ("cpu:<file>", one key=value per line). Written once, replaced atomically when it differs.
         */
        private fun leanSessionConfig(directory: File): File {
            val file = File(directory, "speech-session-no-arena.conf")
            val content = "# ONNX Runtime session options for the speech recognizer.\nEnableCpuMemArena=0\n"
            if (file.isFile && runCatching { file.readText() }.getOrNull() == content) return file
            check(directory.isDirectory || directory.mkdirs()) { "Private storage for the speech runtime is unavailable." }
            val partial = File(directory, "${file.name}.partial")
            partial.writeText(content)
            check(partial.renameTo(file)) { "Could not write the speech runtime session config." }
            return file
        }
    }
}
