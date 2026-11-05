package app.focal.speech

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

internal object UltraModelFiles {
    const val directoryName = "parakeet-ultra-v1-7ef3ca4f"

    data class ModelFile(val name: String, val bytes: Long, val sha256: String)

    // Marmalade Parakeet Ultra INT8 v1. The tokenizer is unchanged from v3.
    private val models = listOf(
        ModelFile("encoder.int8.onnx", 652_282_526, "7ef3ca4ffb49d60d4faa757b2930c8cec9927e73c24f6155d5b23a00229a7ed6"),
        ModelFile("decoder.int8.onnx", 11_845_274, "1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922"),
        ModelFile("joiner.int8.onnx", 6_355_277, "8ba94c6919c17a6bd27368fb89533628a72ffc81d4acedebf3cdcb2cae331dbb"),
        ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    /** The staged research path uses the same model identity as the bundled path. */
    fun verify(directory: File, checkCancelled: () -> Unit): Map<String, File> =
        checkNotNull(verified(directory, models, checkCancelled)) {
            "The staged Ultra model is missing or damaged."
        }

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
        check(parent.isDirectory) { "The private Ultra model storage is unavailable." }
        val partial = File(parent, "${directory.name}.partial")
        removeOwnedDirectory(partial, expected)
        verified(active, expected, checkCancelled)?.let { return it }

        // Free space occupied by a corrupt copy of this exact version before checking capacity.
        checkCancelled()
        removeOwnedDirectory(active, expected)
        val required = expected.sumOf { it.bytes } + 32L * 1024 * 1024
        check(usableSpace(parent) >= required) { "Not enough free storage to prepare the Ultra model." }

        checkCancelled()
        check(partial.mkdir()) { "Could not prepare private storage for the Ultra model." }
        try {
            for (model in expected) {
                checkCancelled()
                openAsset(model.name).use { input ->
                    File(partial, model.name).outputStream().use { output ->
                        check(matches(input, model, checkCancelled, output)) {
                            "The bundled Ultra model is damaged. Reinstall the Ultra APK."
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
        check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Unexpected file in the Ultra model cache." }
        val entries = checkNotNull(directory.listFiles()) { "Could not read the Ultra model cache." }
        val names = expected.mapTo(mutableSetOf()) { it.name }
        check(entries.all { it.name in names && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) {
            "Unexpected file in the Ultra model cache."
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
