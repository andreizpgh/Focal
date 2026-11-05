package app.focal.speech

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import app.focal.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/**
 * The pinned speech model set (four Parakeet Ultra INT8 files and the Silero VAD model), installed
 * once from the APK into no-backup storage.
 */
internal object UltraModel {
    data class ModelFile(val name: String, val bytes: Long, val sha256: String)

    // Marmalade Parakeet Ultra INT8 v1 and Silero VAD, generated from models/parakeet-ultra.json at build time.
    private val models = BuildConfig.ULTRA_MODEL_FILES.split(';').map { entry ->
        val (name, bytes, sha256) = entry.split(':')
        ModelFile(name, bytes.toLong(), sha256)
    }

    // File names in models/parakeet-ultra.json: the transducer and the voice activity model.
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val VAD = "silero_vad.onnx"

    /** Named by the encoder digest: an app update with the same model reuses the installed copy. */
    val directoryName = "parakeet-ultra-v1-" + models.single { it.name == ENCODER }.sha256.take(8)

    /** Whether this APK carries the model files. Release builds always do. */
    fun isBundled(context: Context): Boolean =
        context.assets.list("").orEmpty().toSet().containsAll(models.map { it.name })

    /** Call on the decoder owner thread while holding the existing session claim. */
    @Synchronized
    fun install(
        directory: File,
        openAsset: (String) -> InputStream,
        checkCancelled: () -> Unit,
        expected: List<ModelFile> = models,
        usableSpace: (File) -> Long = { it.usableSpace },
    ): Map<String, File> {
        checkCancelled()
        val parent = checkNotNull(directory.parentFile).canonicalFile
        val active = File(parent, directory.name)
        check(parent.isDirectory) { "Private storage for the speech model is unavailable." }
        val partial = File(parent, "${directory.name}.partial")
        removeOwnedDirectory(partial, expected)
        verified(active, expected, checkCancelled)?.let { return it }

        // Free space occupied by a corrupt copy of this exact version before checking capacity.
        checkCancelled()
        removeOwnedDirectory(active, expected)
        val required = expected.sumOf { it.bytes } + 32L * 1024 * 1024
        check(usableSpace(parent) >= required) { "Not enough free storage to prepare the speech model." }

        checkCancelled()
        check(partial.mkdir()) { "Could not prepare private storage for the speech model." }
        try {
            for (model in expected) {
                checkCancelled()
                openAsset(model.name).use { input ->
                    File(partial, model.name).outputStream().use { output ->
                        check(matches(input, model, checkCancelled, output)) {
                            "The bundled speech model is damaged. Reinstall Focal."
                        }
                        output.fd.sync()
                    }
                }
            }
            syncDirectory(partial)
            checkCancelled()
            Files.move(partial.toPath(), active.toPath(), ATOMIC_MOVE)
            syncDirectory(parent)
            return expected.associate { it.name to File(active, it.name) }
        } catch (failure: Exception) {
            try {
                removeOwnedDirectory(partial, expected)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun verified(
        directory: File, expected: List<ModelFile>, checkCancelled: () -> Unit,
    ): Map<String, File>? {
        checkCancelled()
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return null
        val root = directory.canonicalFile
        val paths = mutableMapOf<String, File>()
        for (model in expected) {
            checkCancelled()
            val file = File(root, model.name)
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() != model.bytes ||
                !file.inputStream().use { matches(it, model, checkCancelled) }) return null
            paths[model.name] = file
        }
        return paths
    }

    private fun matches(
        input: InputStream, model: ModelFile, checkCancelled: () -> Unit, output: OutputStream? = null,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var remaining = model.bytes
        while (remaining > 0) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count <= 0) return false
            output?.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            remaining -= count
        }
        checkCancelled()
        return input.read() == -1 && digest.digest().joinToString("") { "%02x".format(it) } == model.sha256
    }

    private fun removeOwnedDirectory(directory: File, expected: List<ModelFile>) {
        val path = directory.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Unexpected file in the speech model cache." }
        val entries = checkNotNull(directory.listFiles()) { "Could not read the speech model cache." }
        val names = expected.mapTo(mutableSetOf()) { it.name }
        check(entries.all { it.name in names && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) {
            "Unexpected file in the speech model cache."
        }
        entries.forEach { Files.delete(it.toPath()) }
        Files.delete(path)
    }

    private fun syncDirectory(directory: File) {
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or closeOnExec, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
