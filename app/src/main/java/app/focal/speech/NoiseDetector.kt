package app.focal.speech

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Synchronous, owner-thread detector for proposing review candidates only. */
internal class NoiseDetector(context: Context, checkCancelled: () -> Unit = {}) : SpeechPresenceDetector {
    private val owner = Thread.currentThread()
    private var handle: Long

    init {
        val model = bundledModel(context, checkCancelled)
        checkCancelled()
        handle = openNative(model.absolutePath)
        check(handle != 0L) { "Could not open the speech presence model." }
        try { checkCancelled() } catch (error: Throwable) {
            closeNative(handle)
            handle = 0
            throw error
        }
    }

    override fun hasSpeech(samples: FloatArray, checkCancelled: () -> Unit): Boolean {
        checkOwner()
        check(handle != 0L) { "Speech presence detector is closed." }
        require(samples.size in 1..MAX_SAMPLES) { "Speech presence input must be at most 30 seconds." }
        checkCancelled()
        val present = detectNative(handle, samples, checkCancelled)
        checkCancelled()
        return present
    }

    override fun close() {
        checkOwner()
        if (handle != 0L) {
            val previous = handle
            handle = 0
            closeNative(previous)
        }
    }

    private fun checkOwner() { check(Thread.currentThread() === owner) { "Use the detector on its owner thread." } }
    private external fun openNative(path: String): Long
    private external fun detectNative(handle: Long, samples: FloatArray, checkCancelled: () -> Unit): Boolean
    private external fun closeNative(handle: Long)

    companion object {
        private const val NAME = "ggml-silero-v5.1.2.bin"
        private const val BYTES = 885_098L
        private const val SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"
        private const val MAX_SAMPLES = 30 * 16_000

        init { System.loadLibrary("focal") }

        @Synchronized private fun bundledModel(context: Context, checkCancelled: () -> Unit): File {
            val file = File(context.filesDir, NAME)
            if (file.isFile && file.length() == BYTES && checksum(file, checkCancelled) == SHA256) return file
            val partial = File.createTempFile(".speech-presence-", ".partial", context.filesDir)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                context.assets.open(NAME).use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            check(total <= BYTES) { "The bundled speech presence model is damaged." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                check(total == BYTES && digest.digest().hex() == SHA256) { "The bundled speech presence model is damaged." }
                checkCancelled()
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return file
            } finally { partial.delete() }
        }

        private fun checksum(file: File, checkCancelled: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().hex()
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
