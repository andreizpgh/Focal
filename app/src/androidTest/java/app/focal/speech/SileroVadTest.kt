package app.focal.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.focal.core.SpeechTuning
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Silero VAD through the sherpa-onnx C API bridge, on synthetic signals and the JFK fixture. */
class SileroVadTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun silenceAndASteadyToneAreNotSpeech() = withVad { vad ->
        val silence = FloatArray(5 * 16_000)
        assertEquals("", flags(vad, silence).filter { it == '#' })
        vad.reset()
        val tone = FloatArray(5 * 16_000) { (0.3 * sin(2 * PI * 440 * it / 16_000)).toFloat() }
        assertEquals("", flags(vad, tone).filter { it == '#' })
    }

    @Test fun speechInThePublicFixtureMatchesTheHostReference() = withVad { vad ->
        val samples = jfk()
        val first = flags(vad, samples)
        assertEquals(JFK_FLAGS.length, first.length)
        // Same model and code as the host experiments; ONNX Runtime kernels differ per CPU, so a
        // window near the threshold may flip. Demand near-identity, not bit equality.
        val agreement = first.indices.count { first[it] == JFK_FLAGS[it] }
        assertTrue("Flags differ from the host reference:\n$first\n$JFK_FLAGS", agreement >= first.length * 97 / 100)
        // Reset restores the initial state: the same audio gives the same decisions.
        vad.reset()
        assertEquals(first, flags(vad, samples))
    }

    @Test fun rejectsWrongWindowsAndUseAfterClose() = withVad { vad ->
        assertThrows(IllegalArgumentException::class.java) { vad.isSpeech(FloatArray(100)) }
        assertFalse(vad.isSpeech(FloatArray(SpeechTuning.VAD_WINDOW_SAMPLES)))
        vad.close()
        vad.close()
        assertThrows(IllegalStateException::class.java) { vad.isSpeech(FloatArray(SpeechTuning.VAD_WINDOW_SAMPLES)) }
        assertThrows(IllegalStateException::class.java) { vad.reset() }
    }

    @Test fun aMissingModelFailsWithoutTakingTheProcessDown() {
        assertThrows(IllegalStateException::class.java) {
            SileroVad.open(File(app.cacheDir, "no-such-silero_vad.onnx"))
        }
    }

    private fun flags(vad: SileroVad, samples: FloatArray): String {
        val window = FloatArray(SpeechTuning.VAD_WINDOW_SAMPLES)
        return buildString {
            var start = 0
            while (start + window.size <= samples.size) {
                samples.copyInto(window, 0, start, start + window.size)
                append(if (vad.isSpeech(window)) '#' else '.')
                start += window.size
            }
        }
    }

    /** Opens the detector on the bundled model, copied out of the APK; skipped without it. */
    private fun withVad(block: (SileroVad) -> Unit) {
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "silero-").toFile()
        try {
            val model = File(dir, UltraModel.VAD)
            val bundled = try {
                app.assets.open(UltraModel.VAD).use { input -> model.outputStream().use { input.copyTo(it) } }
                true
            } catch (_: FileNotFoundException) {
                false
            }
            assumeTrue("The bundled voice activity model is required for this check", bundled)
            SileroVad.open(model).use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /**
         * Speech decisions per 512-sample window of jfk.wav from sherpa-onnx 2.0.1's Python
         * `VadModel.is_speech` on x86_64 (threshold 0.5, minimum silence 0.05 s, minimum speech
         * 0.1 s), the reference of Focal's segmentation experiments. The bridge reproduced it
         * exactly on the same host.
         */
        val JFK_FLAGS = listOf(14 to '.', 57 to '#', 36 to '.', 16 to '#', 6 to '.', 10 to '#', 34 to '.',
            67 to '#', 20 to '.', 69 to '#', 14 to '.').joinToString("") { (n, c) -> c.toString().repeat(n) }
    }
}
