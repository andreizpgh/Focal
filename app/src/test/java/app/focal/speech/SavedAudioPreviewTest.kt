package app.focal.speech

import app.focal.core.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SavedAudioPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeQuietPcmUsesOverlappingWindowsAndKeepsTheUnpaddedTail() {
        val starts = listOf(0, WINDOW - OVERLAP, 2 * (WINDOW - OVERLAP))
        val ends = listOf(WINDOW, 2 * WINDOW - OVERLAP, 2 * WINDOW - OVERLAP + 19)
        val expected = listOf("Keep this ending.", "This ending continues.", "Continues here.")
        val file = pcm(ends.last(), ::quietSample)
        var calls = 0
        var closes = 0
        val progress = mutableListOf<Pair<Int, Int>>()

        val preview = SavedAudioPreview.process(file, decoderFactory = {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    val start = starts[calls]
                    assertEquals(ends[calls] - start, samples.size)
                    assertTrue(samples.indices.all { samples[it] == quietSample(start + it) / 32768f })
                    return expected[calls++]
                }

                override fun close() { closes++ }
            }
        }, onProgress = { covered, total -> progress += covered to total })

        assertEquals(expected, preview.map { it.text })
        assertTrue(preview.all { it.uncertain })
        assertEquals(listOf(0L, 15_500L, 31_000L), preview.map { it.start })
        assertEquals(listOf(16_000L, 31_500L, 31_502L), preview.map { it.end })
        assertEquals(ends.map { it to ends.last() }, progress)
        assertEquals(3, calls)
        assertEquals(1, closes)
    }

    @Test fun exactZeroAndConstantOffsetSkipEvenOpeningTheDecoder() {
        for (sample in listOf(0, -151)) {
            val file = pcm(WINDOW + 1) { sample }
            val progress = mutableListOf<Pair<Int, Int>>()
            val preview = SavedAudioPreview.process(file, decoderFactory = {
                error("Exact constant audio should not load the recognizer.")
            }, onProgress = { covered, total -> progress += covered to total })
            assertTrue(preview.isEmpty())
            assertEquals(listOf(WINDOW to WINDOW + 1, WINDOW + 1 to WINDOW + 1), progress)
        }
    }

    @Test fun blankOutputOnVaryingSubMillisecondAudioRemainsReviewable() {
        val file = pcm(2) { if (it == 0) -1 else 1 }
        val preview = SavedAudioPreview.process(file, decoderFactory = {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray) = "  "
                override fun close() = Unit
            }
        })
        assertEquals(listOf(Transcript.Segment(0, 1, "", uncertain = true)), preview)
    }

    @Test fun missingEmptyOddAndOversizedSourcesFailBeforeOpeningDecoder() {
        val odd = temporary.newFile().apply { writeBytes(byteArrayOf(1)) }
        val oversized = temporary.newFile()
        RandomAccessFile(oversized, "rw").use {
            it.setLength((SavedAudioPreview.MAX_DURATION_SECONDS * RATE + 1) * 2L)
        }
        for (file in listOf(File(temporary.root, "missing.pcm"), temporary.newFile(), odd, oversized)) {
            assertThrows(IllegalArgumentException::class.java) {
                SavedAudioPreview.process(file, decoderFactory = { error("Invalid input opened a decoder.") })
            }
        }
    }

    @Test fun textBudgetCountsUtf8AndSeparatorsAcrossWindows() {
        val file = pcm(WINDOW + 1, ::quietSample)
        var closes = 0
        assertThrows(IllegalArgumentException::class.java) {
            SavedAudioPreview.process(file, decoderFactory = {
                object : SavedAudioPreview.Decoder {
                    override fun decode(samples: FloatArray) = "é".repeat(32_768)
                    override fun close() { closes++ }
                }
            })
        }
        assertEquals(1, closes)
    }

    @Test fun cancellationWhileDecodeBlocksClosesAfterReturnWithoutPublishingProgress() {
        val file = pcm(WINDOW + 1, ::quietSample)
        val canceled = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val owner = AtomicReference<Thread>()
        val decodeCalls = AtomicInteger()
        val progressCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()

        try {
            val future = worker.submit<List<Transcript.Segment>> {
                SavedAudioPreview.process(file, decoderFactory = {
                    owner.set(Thread.currentThread())
                    object : SavedAudioPreview.Decoder {
                        override fun decode(samples: FloatArray): String {
                            assertSame(owner.get(), Thread.currentThread())
                            decodeCalls.incrementAndGet()
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release decode." }
                            returned.set(true)
                            return "Canceled text"
                        }

                        override fun close() {
                            assertSame(owner.get(), Thread.currentThread())
                            assertTrue("Close must follow synchronous decode return", returned.get())
                            closeCalls.incrementAndGet()
                        }
                    }
                }, checkCancelled = {
                    if (canceled.get()) throw CancellationException("Canceled preview")
                }, onProgress = { _, _ -> progressCalls.incrementAndGet() })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            canceled.set(true)
            assertEquals(0, closeCalls.get())
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertEquals(1, decodeCalls.get())
            assertEquals(0, progressCalls.get())
            assertEquals(1, closeCalls.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun pcm(frames: Int, sample: (Int) -> Int): File = temporary.newFile().apply {
        outputStream().buffered().use { out ->
            repeat(frames) { index ->
                val value = sample(index)
                out.write(value and 0xff)
                out.write(value shr 8 and 0xff)
            }
        }
    }

    private fun quietSample(index: Int): Int = if (index % 7 < 4) -2 else 3

    private companion object {
        const val RATE = SavedAudioPreview.SAMPLE_RATE
        const val WINDOW = 16 * RATE
        const val OVERLAP = RATE / 2
    }
}
