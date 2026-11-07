package app.focal.speech

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The sherpa-onnx C API bridge, decoding the public JFK fixture with the bundled model. */
class OfflineRecognizerTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun loadsThePinnedRuntime() {
        SpeechRuntime.requirePinned()
        assertEquals("2.0.1", SpeechRuntime.info.version)
    }

    @Test fun missingModelFilesFailWithoutTakingTheProcessDown() = isolated { dir ->
        val missing = File(dir, "missing.onnx")
        assertThrows(IllegalStateException::class.java) {
            OfflineRecognizer.create(OfflineRecognizer.Config(missing, missing, missing, missing, threads = 1))
        }.also { assertTrue(it.message.orEmpty().contains("missing")) }
        // The lean session config is written before the model check; the failure is still clean.
        assertThrows(IllegalStateException::class.java) {
            OfflineRecognizer.create(
                OfflineRecognizer.Config(missing, missing, missing, missing, threads = 1, cpuMemArena = false, configDirectory = dir),
            )
        }
        assertEquals("# ONNX Runtime session options for the speech recognizer.\nEnableCpuMemArena=0\n",
            File(dir, "speech-session-no-arena.conf").readText())
    }

    @Test fun decodesTheJfkFixtureWithAlignedTokenArrays() {
        val files = installedModel()
        val samples = jfk()
        OfflineRecognizer.create(config(files, cpuMemArena = true)).use { recognizer ->
            val result = recognizer.decode(samples)
            assertJfk(result, samples.size)

            // A sub-range decodes exactly those samples.
            val part = recognizer.decode(samples, 48_000, 64_000)
            val copy = recognizer.decode(samples.copyOfRange(48_000, 112_000))
            assertTrue(part.text.isNotEmpty())
            assertEquals(copy.text, part.text)
            assertEquals(copy.tokens, part.tokens)
            assertTrue(copy.timestamps.contentEquals(part.timestamps))
            assertTrue(copy.logProbs.contentEquals(part.logProbs))

            // Digital silence yields no words and empty, aligned arrays.
            val silent = recognizer.decode(FloatArray(16_000))
            assertEquals("", silent.text)
            assertTrue(silent.tokens.isEmpty() && silent.timestamps.isEmpty() && silent.logProbs!!.isEmpty())

            assertThrows(IllegalArgumentException::class.java) { recognizer.decode(samples, samples.size - 5, 10) }
            assertThrows(IllegalArgumentException::class.java) { recognizer.decode(FloatArray(0)) }
            assertThrows(IllegalArgumentException::class.java) { recognizer.decode(floatArrayOf(0f, Float.NaN)) }
            assertThrows(IllegalArgumentException::class.java) { recognizer.decode(floatArrayOf(1.5f)) }
            recognizer.close()
            assertThrows(IllegalStateException::class.java) { recognizer.decode(samples) }
        }
    }

    @Test fun decodesWithTheCpuArenaOff() = isolated { dir ->
        val files = installedModel()
        val samples = jfk()
        OfflineRecognizer.create(config(files, cpuMemArena = false, configDirectory = dir)).use { recognizer ->
            assertJfk(recognizer.decode(samples), samples.size)
        }
        assertTrue(File(dir, "speech-session-no-arena.conf").readText().contains("EnableCpuMemArena=0"))
    }

    private fun assertJfk(result: AsrResult, sampleCount: Int) {
        val words = Regex("[a-z]+").findAll(result.text.lowercase()).map { it.value }.toList()
        assertTrue("Unexpected JFK transcript: ${result.text}", "country" in words && "fellow" in words)
        val count = result.tokens.size
        assertTrue(count >= 20)
        assertEquals(count, result.timestamps.size)
        assertEquals(count, result.durations.size)
        val logProbs = checkNotNull(result.logProbs) { "The runtime must report token log-probabilities." }
        assertEquals(count, logProbs.size)
        // The tokens spell the text; a word-initial token carries its leading space.
        assertEquals(result.text, result.tokens.joinToString("").trim())
        val seconds = sampleCount / 16_000f
        for (i in 0 until count) {
            assertTrue(result.timestamps[i] in 0f..seconds && result.durations[i] >= 0f)
            assertTrue(logProbs[i] <= 1e-4f)
            if (i > 0) assertTrue(result.timestamps[i] >= result.timestamps[i - 1])
        }
        assertEquals(count, result.asrTokens().size)
    }

    private fun config(files: Map<String, File>, cpuMemArena: Boolean, configDirectory: File? = null) =
        OfflineRecognizer.Config(
            encoder = files.getValue(UltraModel.ENCODER),
            decoder = files.getValue(UltraModel.DECODER),
            joiner = files.getValue(UltraModel.JOINER),
            tokens = files.getValue(UltraModel.TOKENS),
            threads = 2,
            cpuMemArena = cpuMemArena,
            configDirectory = configDirectory,
        )

    /** The bundled model, installed as the app installs it; the test is skipped without it. */
    private fun installedModel(): Map<String, File> {
        assumeTrue("The bundled speech model is required for this check", UltraModel.isBundled(app))
        return UltraModel.install(
            File(app.noBackupFilesDir, UltraModel.directoryName),
            openAsset = { app.assets.open(it, AssetManager.ACCESS_STREAMING) },
            checkCancelled = {},
        )
    }

    private fun isolated(block: (File) -> Unit) {
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "speech-bridge-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The public JFK fixture (16 kHz mono PCM16 with a 44-byte header) as float samples. */
internal fun jfk(): FloatArray {
    val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
    assertEquals(352_044, wav.size)
    val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return FloatArray(pcm.remaining()) { pcm.get() / 32768f }
}
