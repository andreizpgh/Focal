package app.focal.data

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Whisper-era builds (up to Alpha 9) left model files in private storage that nothing reads now:
 * the Whisper Base model (about 57 MiB, extracted even by the Ultra builds), the Silero
 * speech-presence model, interrupted copies of both, and staged Parakeet research files.
 * Notes, audio and the installed Ultra model (in no-backup storage) are never touched.
 */
internal object RetiredFiles {
    private val names = listOf("ggml-base-q5_1.bin", "ggml-base-q5_1.bin.partial", "ggml-silero-v5.1.2.bin")
    private const val RESEARCH_DIRECTORY = "parakeet-probe"

    /** Best effort and idempotent: whatever cannot be removed now is tried again on the next launch. */
    fun remove(filesDir: File) {
        names.forEach { name -> ignoringFailure { Files.deleteIfExists(File(filesDir, name).toPath()) } }
        ignoringFailure {
            filesDir.listFiles { file -> file.name.startsWith(".speech-presence-") && file.name.endsWith(".partial") }
                ?.forEach { file -> ignoringFailure { Files.deleteIfExists(file.toPath()) } }
        }
        ignoringFailure { deleteTree(File(filesDir, RESEARCH_DIRECTORY).toPath()) }
    }

    /** Never follows symbolic links: a link is removed, never what it points to. */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private inline fun ignoringFailure(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* Retried on the next launch. */ }
    }
}
