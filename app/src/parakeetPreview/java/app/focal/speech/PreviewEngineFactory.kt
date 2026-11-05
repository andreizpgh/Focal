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

/** Research source-set implementation; ordinary builds must use an unavailable factory. */
internal object PreviewEngineFactory {
    const val available = true

    /** Opens verified side-loaded research files without modifying them. */
    fun open(directory: File, threads: Int, checkCancelled: () -> Unit = {}): SavedAudioPreview.Decoder =
        open(threads, checkCancelled) { UltraModelFiles.verify(directory, checkCancelled) }

    fun openBundled(context: Context, threads: Int, checkCancelled: () -> Unit): SavedAudioPreview.Decoder =
        open(threads, checkCancelled) {
            UltraModelFiles.install(
                File(context.noBackupFilesDir, UltraModelFiles.directoryName),
                openAsset = { name ->
                    try {
                        context.assets.open(name, AssetManager.ACCESS_STREAMING)
                    } catch (error: FileNotFoundException) {
                        throw IllegalStateException("The Ultra model is missing from this APK.", error)
                    }
                },
                checkCancelled = checkCancelled,
            )
        }

    private fun open(
        threads: Int, checkCancelled: () -> Unit, modelFiles: () -> Map<String, File>,
    ): SavedAudioPreview.Decoder {
        try {
            require(threads in 1..2) { "Choose one or two inference threads." }
            return openChecked(modelFiles(), threads, checkCancelled)
        } catch (error: LinkageError) {
            throw IllegalStateException("The experimental speech runtime could not be loaded.", error)
        }
    }

    private fun openChecked(
        files: Map<String, File>, threads: Int, checkCancelled: () -> Unit,
    ): SavedAudioPreview.Decoder {
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
            val decoder = ParakeetDecoder(recognizer)
            transferred = true
            return decoder
        } finally {
            if (!transferred) recognizer.release()
        }
    }
}

private class ParakeetDecoder(private val recognizer: OfflineRecognizer) : SavedAudioPreview.Decoder {
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
