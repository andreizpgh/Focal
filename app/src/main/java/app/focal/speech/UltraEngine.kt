package app.focal.speech

import android.app.ActivityManager
import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException

/**
 * Parakeet Ultra INT8 through Focal's bridge over the pinned sherpa-onnx C API: Focal's only
 * speech engine.
 */
internal object UltraEngine {
    /**
     * Installs the bundled model into private storage if needed, then opens one recognizer.
     * Call on the decoder owner thread while holding the session claim.
     */
    fun open(context: Context, threads: Int, checkCancelled: () -> Unit): SpeechDecoder {
        try {
            require(threads in 1..OfflineRecognizer.MAX_THREADS) { "Choose 1 to ${OfflineRecognizer.MAX_THREADS} inference threads." }
            check(UltraModel.isBundled(context)) { MODEL_MISSING }
            val files = UltraModel.install(
                File(context.noBackupFilesDir, UltraModel.directoryName),
                openAsset = { name ->
                    try {
                        context.assets.open(name, AssetManager.ACCESS_STREAMING)
                    } catch (error: FileNotFoundException) {
                        throw IllegalStateException(MODEL_MISSING, error)
                    }
                },
                checkCancelled = checkCancelled,
            )
            checkCancelled()
            // The runtime identity comes from models/sherpa-onnx.json, the lock the build verified.
            SpeechRuntime.requirePinned()
            checkCancelled()
            val recognizer = OfflineRecognizer.create(
                OfflineRecognizer.Config(
                    encoder = files.getValue(UltraModel.ENCODER),
                    decoder = files.getValue(UltraModel.DECODER),
                    joiner = files.getValue(UltraModel.JOINER),
                    tokens = files.getValue(UltraModel.TOKENS),
                    threads = threads,
                    cpuMemArena = usesCpuMemArena(context),
                    configDirectory = context.noBackupFilesDir,
                ),
            )
            var transferred = false
            try {
                checkCancelled()
                val decoder = UltraDecoder(recognizer)
                transferred = true
                return decoder
            } finally {
                if (!transferred) recognizer.close()
            }
        } catch (error: LinkageError) {
            throw IllegalStateException("The speech runtime could not be loaded.", error)
        }
    }

    /**
     * ONNX Runtime's CPU arena stays on only above 6 GB of RAM. Phones sold as 6 GB report about
     * 5.5 GiB of total memory and 8 GB phones about 7.3 GiB, so the line sits between them.
     */
    fun usesCpuMemArena(context: Context): Boolean {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return memory.totalMem > LEAN_MEMORY_LIMIT_BYTES
    }

    private const val LEAN_MEMORY_LIMIT_BYTES = 6_656L * 1024 * 1024
}

private class UltraDecoder(private val recognizer: OfflineRecognizer) : SpeechDecoder {
    private val owner = Thread.currentThread()
    private var closed = false

    override fun decode(samples: FloatArray): AsrResult {
        checkOwner()
        check(!closed) { "Speech engine is closed." }
        require(samples.isNotEmpty() && samples.size <= SAMPLE_RATE * 30) { "Invalid audio samples." }
        return recognizer.decode(samples)
    }

    override fun close() {
        checkOwner()
        if (!closed) {
            closed = true
            recognizer.close()
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Use the speech engine on its owner thread." }
    }
}

private const val SAMPLE_RATE = OfflineRecognizer.SAMPLE_RATE
internal const val MODEL_MISSING = "The speech model is missing from this build. Recordings are saved; transcribe them with a complete build."
