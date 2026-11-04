package app.focal.speech

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BaseModelValidatorTest {
    @Test fun vocabularyBoundsAreCheckedBeforeTensorParsing() {
        // A trailing invalid tensor invokes one more cancellation check only if
        // the complete vocabulary was accepted and parsing resumed correctly.
        for ((count, firstTokens, expectedChecks) in listOf(
            Triple(50257, intArrayOf(512), 51),
            Triple(51865, intArrayOf(0), 52),
            Triple(50257, IntArray(3907) { if (it == 3906) 128 else 512 }, 51),
            Triple(50257, IntArray(3907) { if (it == 3906) 129 else 512 }, 4),
            Triple(50257, intArrayOf(513), 1),
            Triple(50257, intArrayOf(-1), 1),
        )) {
            fixture { out ->
                header(out, count)
                tokens(out, count, firstTokens)
                int(out, 4)
            }.useFile { file ->
                var checks = 0
                assertThrows(IllegalArgumentException::class.java) {
                    BaseModelValidator.validate(file) { checks++ }
                }
                assertEquals(expectedChecks, checks)
            }
        }
    }

    @Test fun truncatedTokenLengthAndPayloadAreRejected() {
        for (payload in listOf(byteArrayOf(1, 0), byteArrayOf(0, 2, 0, 0) + ByteArray(511))) {
            fixture { out -> header(out, 50257); out.write(payload) }.useFile { file ->
                assertThrows(IllegalArgumentException::class.java) { BaseModelValidator.validate(file) }
            }
        }
    }

    @Test fun cancellationRemainsCheckedDuringVocabularyParsing() {
        fixture { out -> header(out, 50257); tokens(out, 50257, intArrayOf()) }.useFile { file ->
            for (cancelAt in listOf(1, 2, 25, 50)) {
                var checks = 0
                assertThrows(CancellationException::class.java) {
                    BaseModelValidator.validate(file) {
                        if (++checks == cancelAt) throw CancellationException()
                    }
                }
                assertEquals(cancelAt, checks)
            }
        }
    }

    private fun fixture(write: (DataOutputStream) -> Unit): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File.createTempFile("validator-", ".bin", context.cacheDir).also { file ->
            DataOutputStream(file.outputStream()).use(write)
        }
    }

    private inline fun File.useFile(block: (File) -> Unit) {
        try { block(this) } finally { delete() }
    }

    private fun int(out: DataOutputStream, value: Int) = out.writeInt(Integer.reverseBytes(value))

    private fun header(out: DataOutputStream, count: Int) {
        listOf(0x67676d6c, 51865, 1500, 512, 8, 6, 448, 512, 8, 6, 80, 1009, 80, 201).forEach { int(out, it) }
        out.write(ByteArray(80 * 201 * 4))
        int(out, count)
    }

    private fun tokens(out: DataOutputStream, count: Int, first: IntArray) {
        val zeroes = ByteArray(513)
        repeat(count) { i ->
            val length = if (i < first.size) first[i] else 0
            int(out, length)
            if (length > 0) out.write(zeroes, 0, length)
        }
    }
}
