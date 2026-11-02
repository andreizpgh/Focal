package app.focal

import androidx.test.core.app.ApplicationProvider
import app.focal.speech.NativeWhisper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeWhisperTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()

    @Test fun invalidHandlesFailWithoutDereferencingNativeMemory() {
        for (handle in listOf(0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            NativeWhisper.cancel(handle)
            NativeWhisper.close(handle)
            NativeWhisper.close(handle)
            assertThrows(IllegalStateException::class.java) {
                NativeWhisper.decode(handle, FloatArray(16000), "en", 1)
            }
        }
    }

    @Test fun damagedModelFailsWithoutPublishingAnEngine() {
        val model = File.createTempFile("invalid-speech-model-", ".bin", app.cacheDir)
        try {
            model.writeBytes(ByteArray(64))
            assertThrows(IllegalStateException::class.java) { NativeWhisper.open(model.absolutePath) }
        } finally { model.delete() }
    }

    @Test fun cancellationIsTerminalAndCloseIsIdempotent() {
        val handle = realEngine()
        try {
            NativeWhisper.cancel(handle)
            assertThrows(CancellationException::class.java) {
                NativeWhisper.decode(handle, FloatArray(16000), "ru", 1)
            }
        } finally { NativeWhisper.close(handle) }
        NativeWhisper.cancel(handle)
        NativeWhisper.close(handle)
        assertThrows(IllegalStateException::class.java) {
            NativeWhisper.decode(handle, FloatArray(16000), "en", 1)
        }
    }

    @Test fun invalidLanguageAndPcmAreRejectedBeforeInference() {
        val handle = realEngine()
        try {
            for (language in listOf("auto", "zh", "", "EN")) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeWhisper.decode(handle, FloatArray(16000), language, 1)
                }
            }
            for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.1f)) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeWhisper.decode(handle, FloatArray(16000).also { it[0] = sample }, "en", 1)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeWhisper.decode(handle, FloatArray(16000), "en", 1, "x".repeat(241))
            }
        } finally { NativeWhisper.close(handle) }
    }

    @Test fun closingAndCancellingOneEngineFromDifferentThreadsIsSafe() {
        val handle = realEngine()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val cancel = executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(100) { NativeWhisper.cancel(handle) }
            }
            val close = executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(100) { NativeWhisper.close(handle) }
            }
            start.countDown()
            cancel.get(10, TimeUnit.SECONDS)
            close.get(10, TimeUnit.SECONDS)
        } finally {
            start.countDown()
            executor.shutdownNow()
            NativeWhisper.close(handle)
        }
        assertThrows(IllegalStateException::class.java) {
            NativeWhisper.decode(handle, FloatArray(16000), "en", 1)
        }
    }

    private fun realEngine(): Long {
        runBlocking { app.initialized.await() }
        assumeTrue("A real bundled model is required for this JNI lifecycle check", app.models.ready())
        return NativeWhisper.open(app.models.model.absolutePath)
    }
}
