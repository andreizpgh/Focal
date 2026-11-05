package app.focal.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Research source-set implementation; ordinary builds must use an unavailable factory. */
internal object PreviewEngineFactory {
    const val available = true

    /** Opens previously staged private files; does not install or modify them. */
    fun open(directory: File, threads: Int, checkCancelled: () -> Unit = {}): SavedAudioPreview.Decoder {
        try {
            return openChecked(directory, threads, checkCancelled)
        } catch (error: LinkageError) {
            throw IllegalStateException("The experimental speech runtime could not be loaded.", error)
        }
    }

    private fun openChecked(directory: File, threads: Int, checkCancelled: () -> Unit): SavedAudioPreview.Decoder {
        require(threads in 1..2) { "Choose one or two inference threads." }
        val files = verifiedModelFiles(directory, checkCancelled)
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

private data class ModelFile(val name: String, val bytes: Long, val sha256: String)

private fun verifiedModelFiles(directory: File, checkCancelled: () -> Unit): Map<String, File> {
    checkCancelled()
    val root = directory.canonicalFile
    check(root.isDirectory) { "Missing private Parakeet model directory." }
    return MODELS.associate { model ->
        val file = File(root, model.name)
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root &&
            file.length() == model.bytes) { "Missing or wrong-sized model file: ${model.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == model.sha256) { "Model checksum mismatch: ${model.name}" }
        model.name to file
    }
}

private const val SAMPLE_RATE = 16_000
private const val SHERPA_REVISION = "af2d8d81dbfa61c4cea4c568da78f3982edca005"
private val MODELS = listOf(
    ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
    ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
    ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
    ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
)
