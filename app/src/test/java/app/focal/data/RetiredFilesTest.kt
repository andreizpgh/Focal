package app.focal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RetiredFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun File.write(path: String, text: String = path) = resolve(path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun removesOnlyWhisperEraModelFiles() {
        val files = temporary.newFolder("files")
        listOf("ggml-base-q5_1.bin", "ggml-base-q5_1.bin.partial", "ggml-silero-v5.1.2.bin",
            ".speech-presence-8231.partial", "parakeet-probe/model/encoder.int8.onnx",
            "parakeet-probe/receipts/run.json").forEach { files.write(it) }
        val kept = listOf("audio/note.pcm", "notes.partial", "ggml-base-q5_1.bin.backup", "speech-presence.partial")
            .map { files.write(it) }

        RetiredFiles.remove(files)

        assertEquals(kept.map { it.relativeTo(files).path }.sorted(),
            files.walkBottomUp().filter { it.isFile }.map { it.relativeTo(files).path }.sorted().toList())
        assertFalse(files.resolve("parakeet-probe").exists())
        assertEquals("audio/note.pcm", kept.first().readText())
    }

    @Test fun neverFollowsALinkOutOfTheResearchDirectory() {
        val files = temporary.newFolder("files")
        val elsewhere = temporary.newFolder("elsewhere").also { it.write("keep.txt", "untouched") }
        files.resolve("parakeet-probe").mkdirs()
        Files.createSymbolicLink(files.resolve("parakeet-probe/model").toPath(), elsewhere.toPath())

        RetiredFiles.remove(files)

        assertFalse(files.resolve("parakeet-probe").exists())
        assertEquals("untouched", elsewhere.resolve("keep.txt").readText())
    }

    @Test fun nothingToRemoveIsNotAnError() {
        val files = temporary.newFolder("files")
        RetiredFiles.remove(files)
        RetiredFiles.remove(files.resolve("missing"))
        assertTrue(files.isDirectory)
    }
}
