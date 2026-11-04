package app.focal

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.speech.NativeFaultHooks
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object NativeFaultFixtures {
    const val BASELINE = "02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e"
    const val PATCHED = "8cc010e36be180b2843287562cd69104a2b1f11e4d7a95827fffce7db70ed64b"
    val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()

    fun ready(source: String) {
        runBlocking { app.initialized.await() }
        assertTrue("The real bundled model is mandatory", app.models.ready())
        val digest = MessageDigest.getInstance("SHA-256")
        app.models.model.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals("422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", hex(digest.digest()))
        assertTrue("Wrong native fault artifact: ${NativeFaultHooks.identity()}", NativeFaultHooks.identity().contains(source))
    }

    fun pcm(): ByteArray {
        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        assertEquals("d7d4e74b8a333ed02186008bc109a1b1a19d16da668bd56e785d80d69a16a72f", hex(hash(wav)))
        // The hash binds the public fixture's standard PCM16/mono/16 kHz header.
        return wav.copyOfRange(44, wav.size)
    }

    fun samples(pcm: ByteArray): FloatArray {
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(input.remaining()) { input.get() / 32768f }
    }

    fun assertSpeech(bytes: ByteArray) {
        val segments = JSONArray(bytes.decodeToString())
        val text = (0 until segments.length()).joinToString(" ") { segments.getJSONObject(it).getString("text") }.lowercase()
        assertTrue("Real fixture must be recognized", "ask not" in text && "country" in text)
    }

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
