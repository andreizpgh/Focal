package app.focal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.focal.speech.ModelFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Failed or cancelled imports must never replace the previously installed bytes. */
class ModelFilesTest {
    @Test fun incompleteImportKeepsThePreviousModelAndRemovesTemporaryFile() = isolated { models ->
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(128)).use { models.install(it) }
        }
    }

    @Test fun checksumFailureKeepsThePreviousModelAndRemovesTemporaryFile() = isolated { models ->
        zeroes(10_000_001).use { input ->
            assertThrows(IllegalStateException::class.java) { models.install(input, "f".repeat(64)) }
        }
    }

    @Test fun cancelledImportKeepsThePreviousModelAndRemovesTemporaryFile() = isolated { models ->
        var checks = 0
        zeroes(10_000_001).use { input ->
            assertThrows(CancellationException::class.java) {
                models.install(input, checkCancelled = { if (++checks == 3) throw CancellationException() })
            }
        }
        assertEquals(3, checks)
    }

    private fun isolated(block: (ModelFiles) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "model-import-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val models = ModelFiles(context)
        val original = "Previously installed model bytes; isolated synthetic fixture".toByteArray()
        models.model.writeBytes(original)
        try {
            block(models)
            assertArrayEquals(original, models.model.readBytes())
            assertFalse(File(directory, "${ModelFiles.NAME}.partial").exists())
        } finally { directory.deleteRecursively() }
    }

    private fun zeroes(size: Int): InputStream = object : InputStream() {
        var remaining = size
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val count = minOf(length, remaining)
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
