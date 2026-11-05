package app.focal.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.focal.recording.StorageReserve
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraModelTest {
    @Test fun installsAllFilesTogetherAndReusesVerifiedCopy() = isolated { fixture ->
        val opened = mutableListOf<String>()
        val installed = fixture.install { name ->
            assertFalse(fixture.active.exists())
            opened += name
            fixture.payloads.getValue(name).inputStream()
        }
        assertEquals(fixture.payloads.keys.toList(), opened)
        fixture.assertInstalled(installed)
        assertFalse(fixture.partial.exists())
        assertEquals(installed, fixture.install(space = 0) { error("Cached model should not open an asset.") })
    }

    @Test fun insufficientStorageOpensNoAssetAndLeavesNoPartial() = isolated { fixture ->
        assertThrows(IllegalStateException::class.java) {
            fixture.install(space = 0) { error("Insufficient storage should be detected before copying.") }
        }.also { assertTrue(it.message.orEmpty().contains("Free up space")) }
        assertFalse(fixture.active.exists())
        assertFalse(fixture.partial.exists())
        // The copy leaves room to start a recording: the model's size alone is not enough.
        val model = fixture.payloads.values.sumOf { it.size.toLong() }
        assertThrows(IllegalStateException::class.java) {
            fixture.install(space = model + StorageReserve.START_BYTES - 1) { error("Too little space for a recording after the copy.") }
        }
        fixture.assertInstalled(fixture.install(space = model + StorageReserve.START_BYTES))
    }

    @Test fun shortLongAndWrongChecksumAssetsNeverActivate() = isolated { fixture ->
        val firstName = fixture.payloads.keys.first()
        val bytes = fixture.payloads.getValue(firstName)
        val invalid = listOf(bytes.copyOf(bytes.size - 1), bytes + 1.toByte(), bytes.reversedArray())
        for (body in invalid) {
            assertThrows(IllegalStateException::class.java) {
                fixture.install { name ->
                    assertEquals(firstName, name)
                    body.inputStream()
                }
            }.also {
                assertTrue(it.message.orEmpty().contains("damaged"))
                assertTrue("A damaged APK copy is not retried automatically", it is BundledModelException)
            }
            assertFalse(fixture.active.exists())
            assertFalse(fixture.partial.exists())
        }
    }

    @Test fun cancellationAfterFirstBufferCleansThePartialAndAllowsRetry() = isolated { fixture ->
        var readBytes = 0
        var closed = false
        assertThrows(CancellationException::class.java) {
            fixture.install(checkCancelled = { if (readBytes > 0) throw CancellationException() }) { name ->
                val source = fixture.payloads.getValue(name).inputStream()
                object : InputStream() {
                    override fun read() = source.read()
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        source.read(bytes, offset, length).also { if (it > 0) readBytes += it }
                    override fun close() { closed = true; source.close() }
                }
            }
        }
        assertEquals(65_536, readBytes)
        assertTrue(closed)
        assertFalse(fixture.active.exists())
        assertFalse(fixture.partial.exists())
        fixture.assertInstalled(fixture.install())
    }

    @Test fun retriesAfterInterruptedCopyAndRepairsOnlyItsOwnCorruptVersion() = isolated { fixture ->
        val otherVersion = File(fixture.active.parentFile, "other-model").apply { writeText("preserve") }
        assertTrue(fixture.partial.mkdir())
        val firstName = fixture.payloads.keys.first()
        File(fixture.partial, firstName).writeBytes(byteArrayOf(1, 2))
        val installed = fixture.install()
        fixture.assertInstalled(installed)

        val damaged = installed.getValue(firstName)
        damaged.writeBytes(damaged.readBytes().reversedArray())
        fixture.assertInstalled(fixture.install())
        assertEquals("preserve", otherVersion.readText())
        assertFalse(fixture.partial.exists())

        val unrelated = File(fixture.active, "do-not-delete").apply { writeText("preserve") }
        damaged.writeBytes(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { fixture.install() }
        assertEquals("preserve", unrelated.readText())
        assertArrayEquals(byteArrayOf(1, 2), damaged.readBytes())
    }

    private fun isolated(block: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "ultra-assets-").toFile()
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(root: File) {
        val active = File(root, "ultra-v1")
        val partial = File(root, "${active.name}.partial")
        val payloads = linkedMapOf(
            "encoder.int8.onnx" to ByteArray(70_000) { it.toByte() },
            "decoder.int8.onnx" to byteArrayOf(1, 2, 3),
            "joiner.int8.onnx" to byteArrayOf(2, 3, 4),
            "tokens.txt" to "test tokens".toByteArray(),
        )
        private val expected = payloads.map { (name, bytes) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            UltraModel.ModelFile(name, bytes.size.toLong(), digest.joinToString("") { "%02x".format(it) })
        }

        fun install(
            space: Long = Long.MAX_VALUE,
            checkCancelled: () -> Unit = {},
            openAsset: (String) -> InputStream = { payloads.getValue(it).inputStream() },
        ) = UltraModel.install(active, openAsset, checkCancelled, expected, usableSpace = { space })

        fun assertInstalled(files: Map<String, File>) {
            assertEquals(payloads.keys, files.keys)
            payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, files.getValue(name).readBytes()) }
        }
    }
}
