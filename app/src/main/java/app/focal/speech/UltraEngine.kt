package app.focal.speech

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Parakeet Ultra INT8 through Focal's bridge over the pinned sherpa-onnx C API: Focal's only
 * speech engine.
 */
internal object UltraEngine {
    /**
     * Opens one recognizer on the installed model [files] ([SpeechModels]) with [threads] inference threads,
     * fixed for its lifetime. Call on the speech worker; loading takes seconds and most of a gigabyte.
     */
    fun open(context: Context, files: Map<String, File>, threads: Int): SpeechDecoder {
        try {
            require(threads in 1..OfflineRecognizer.MAX_THREADS) { "Choose 1 to ${OfflineRecognizer.MAX_THREADS} inference threads." }
            // The runtime identity comes from models/sherpa-onnx.json, the lock the build verified.
            SpeechRuntime.requirePinned()
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
