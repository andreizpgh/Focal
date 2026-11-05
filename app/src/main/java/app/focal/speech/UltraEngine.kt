package app.focal.speech

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.io.FileNotFoundException

/** Parakeet Ultra INT8 through the pinned sherpa-onnx runtime: Focal's only speech engine. */
internal object UltraEngine {
    /**
     * Installs the bundled model into private storage if needed, then opens one recognizer.
     * Call on the decoder owner thread while holding the session claim.
     */
    fun open(context: Context, threads: Int, checkCancelled: () -> Unit): SpeechDecoder {
        try {
            require(threads in 1..2) { "Choose one or two inference threads." }
            val files = UltraModel.install(
                File(context.noBackupFilesDir, UltraModel.directoryName),
                openAsset = { name ->
                    try {
                        context.assets.open(name, AssetManager.ACCESS_STREAMING)
                    } catch (error: FileNotFoundException) {
                        throw IllegalStateException("The speech model is missing from this build.", error)
                    }
                },
                checkCancelled = checkCancelled,
            )
            return openChecked(files, threads, checkCancelled)
        } catch (error: LinkageError) {
            throw IllegalStateException("The speech runtime could not be loaded.", error)
        }
    }

    private fun openChecked(files: Map<String, File>, threads: Int, checkCancelled: () -> Unit): SpeechDecoder {
        checkCancelled()
        val revision = VersionInfo.gitSha1
        check(VersionInfo.version == "2.0.1" && revision.length >= 8 && SHERPA_REVISION.startsWith(revision) &&
            VersionInfo.onnxruntimeVersion == "1.28.2") { "Unexpected speech runtime." }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = files.getValue("encoder.int8.onnx").absolutePath,
                    decoder = files.getValue("decoder.int8.onnx").absolutePath,
                    joiner = files.getValue("joiner.int8.onnx").absolutePath,
                ),
                tokens = files.getValue("tokens.txt").absolutePath,
                numThreads = threads,
                provider = "cpu",
                modelType = "nemo_transducer",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        checkCancelled()
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        var transferred = false
        try {
            checkCancelled()
            val decoder = UltraDecoder(recognizer)
            transferred = true
            return decoder
        } finally {
            if (!transferred) recognizer.release()
        }
    }
}

private class UltraDecoder(private val recognizer: OfflineRecognizer) : SpeechDecoder {
    private val owner = Thread.currentThread()
    private var closed = false

    override fun decode(samples: FloatArray): String {
        checkOwner()
        check(!closed) { "Speech engine is closed." }
        require(samples.isNotEmpty() && samples.size <= SAMPLE_RATE * 30 &&
            samples.all { it.isFinite() && it in -1f..1f }) { "Invalid audio samples." }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        checkOwner()
        if (!closed) {
            closed = true
            recognizer.release()
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Use the speech engine on its owner thread." }
    }
}

private const val SAMPLE_RATE = 16_000
private const val SHERPA_REVISION = "af2d8d81dbfa61c4cea4c568da78f3982edca005"
