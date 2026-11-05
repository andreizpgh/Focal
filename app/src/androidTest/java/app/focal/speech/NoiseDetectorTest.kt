package app.focal.speech

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Actual bundled VAD; synthetic PCM and the public-domain speech fixture only. */
class NoiseDetectorTest {
    @Test fun realModelDistinguishesSpeechFromConstantAndSeededNoiseAndResets() {
        val detector = NoiseDetector(ApplicationProvider.getApplicationContext())
        try {
            val random = Random(0xF0CA1)
            val noise = FloatArray(3 * 16_000) { (random.nextInt(257) - 128) / 32768f }
            val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
            val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val speech = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
            assertFalse(detector.hasSpeech(FloatArray(16_000)))
            assertFalse(detector.hasSpeech(noise))
            assertTrue(detector.hasSpeech(speech))
            assertFalse(detector.hasSpeech(FloatArray(16_000)))
        } finally { detector.close() }
    }

    @Test fun invalidAndCancelledCallsFailRatherThanReturnAbsence() {
        val detector = NoiseDetector(ApplicationProvider.getApplicationContext())
        try {
            assertThrows(IllegalArgumentException::class.java) { detector.hasSpeech(FloatArray(0)) }
            assertThrows(IllegalArgumentException::class.java) { detector.hasSpeech(FloatArray(480_001)) }
            assertThrows(IllegalArgumentException::class.java) { detector.hasSpeech(floatArrayOf(Float.NaN)) }
            assertThrows(IllegalArgumentException::class.java) { detector.hasSpeech(floatArrayOf(2f)) }
            var checks = 0
            assertThrows(CancellationException::class.java) {
                detector.hasSpeech(FloatArray(16_000)) { if (++checks == 2) throw CancellationException() }
            }
            assertFalse(detector.hasSpeech(FloatArray(16_000)))
        } finally { detector.close() }
        assertThrows(IllegalStateException::class.java) { detector.hasSpeech(FloatArray(1)) }
    }

    @Test fun graphAbortIsAnErrorClearsStaleProbabilitiesAndContextCanBeReused() {
        val detector = NoiseDetector(ApplicationProvider.getApplicationContext())
        try {
            val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
            val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val speech = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
            // Populate the context first so stale successful probabilities cannot masquerade as absence.
            assertTrue(detector.hasSpeech(speech))
            val field = NoiseDetector::class.java.getDeclaredField("handle").apply { isAccessible = true }
            NoiseDetectorFaultProbe.arm(field.getLong(detector))
            val error = assertThrows(IllegalStateException::class.java) { detector.hasSpeech(speech) }
            assertTrue(error.message == "Injected graph abort confirmed.")
            assertTrue(detector.hasSpeech(speech))
        } finally { detector.close() }
    }

    @Test fun detectorRejectsCallsFromAnotherThreadAndCloseIsIdempotent() {
        val detector = NoiseDetector(ApplicationProvider.getApplicationContext())
        try {
            val failure = AtomicReference<Throwable>()
            val thread = Thread {
                try { detector.hasSpeech(FloatArray(1)) } catch (error: Throwable) { failure.set(error) }
            }
            thread.start()
            thread.join()
            assertTrue(failure.get() is IllegalStateException)
        } finally { detector.close() }
        detector.close()
    }
}
