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

    @Test fun continuousQuietPcmKeepsOverlapAndFiveSecondsOfNewTail() {
        val total = 63 * RATE + 19
        val starts = listOf(0, WINDOW - OVERLAP, total - TAIL - OVERLAP)
        val ends = listOf(WINDOW, total - TAIL, total)
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
        assertEquals(listOf(0L, 29_500L, 57_501L), preview.map { it.start })
        assertEquals(listOf(30_000L, 58_002L, 63_002L), preview.map { it.end })
        assertEquals(ends.map { it to ends.last() }, progress)
        assertEquals(3, calls)
        assertEquals(1, closes)
    }

    @Test fun formerSeamAndThirtySecondLimitBothDecodeTheWholeSourceOnce() {
        for (frames in listOf(17 * RATE + 19, WINDOW)) {
            val file = pcm(frames, ::quietSample)
            var calls = 0
            val passages = SavedAudioPreview.process(file, decoderFactory = {
                object : SavedAudioPreview.Decoder {
                    override fun decode(samples: FloatArray): String {
                        calls++
                        assertEquals(frames, samples.size)
                        assertTrue(samples.indices.all { samples[it] == quietSample(it) / 32768f })
                        return "One complete phrase."
                    }
                    override fun close() = Unit
                }
            })
            assertEquals(1, calls)
            assertEquals(0L, passages.single().start)
            assertEquals((frames + 15) / 16L, passages.single().end)
        }
    }

    @Test fun oneFramePastThirtySecondsStillLeavesFiveSecondsOfNewAudio() {
        val file = pcm(WINDOW + 1, ::quietSample)
        val lengths = mutableListOf<Int>()
        SavedAudioPreview.process(file, decoderFactory = {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    lengths += samples.size
                    return "Phrase."
                }
                override fun close() = Unit
            }
        })
        assertEquals(listOf(WINDOW + 1 - TAIL, TAIL + OVERLAP), lengths)
    }

    @Test fun cutsAtLatestQualifiedLowEnergyMidpointWithoutOverlap() {
        val frame = RATE / 50
        val latest = 28 * RATE
        val later = 29 * RATE
        fun sample(index: Int): Int {
            val amplitude = when (index) {
                in 26 * RATE + RATE / 2 until 26 * RATE + RATE / 2 + 12 * frame -> 0
                in latest until latest + 12 * frame -> 0
                in later + 6 * frame until later + 7 * frame -> 4
                in later until later + 12 * frame -> 0
                else -> 20
            }
            return 741 + if (index % 2 == 0) amplitude else -amplitude
        }
        val total = 38 * RATE + 11
        val cut = latest + 6 * frame
        val starts = listOf(0, cut)
        val ends = listOf(cut, total)
        val file = pcm(total, ::sample)
        var calls = 0
        val progress = mutableListOf<Int>()
        val passages = SavedAudioPreview.process(file, decoderFactory = {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    val start = starts[calls]
                    assertEquals(ends[calls] - start, samples.size)
                    assertTrue(samples.indices.all { samples[it] == sample(start + it) / 32768f })
                    return "Phrase ${++calls}."
                }
                override fun close() = Unit
            }
        }, onProgress = { covered, _ -> progress += covered })
        assertEquals(2, calls)
        assertEquals(ends, progress)
        assertEquals(passages[0].end, passages[1].start)
    }

    @Test fun earlierPauseAvoidsHardCutAndPreservesRepeatedText() {
        val frame = RATE / 50
        val earlier = 39 * RATE + RATE / 2
        val allowed = 44 * RATE
        fun sample(index: Int): Int = when (index) {
            in earlier until earlier + 12 * frame -> 0
            in allowed until allowed + 12 * frame -> 0
            else -> if (index % 2 == 0) 20 else -20
        }
        val total = 61 * RATE
        val cut = allowed + 6 * frame
        val starts = listOf(0, WINDOW - OVERLAP, cut)
        val ends = listOf(WINDOW, cut, total)
        var calls = 0
        val passages = SavedAudioPreview.process(pcm(total, ::sample), decoderFactory = {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    val start = starts[calls]
                    assertEquals(ends[calls] - start, samples.size)
                    assertTrue(samples.indices.all { samples[it] == sample(start + it) / 32768f })
                    calls++
                    return "A deliberately repeated sentence."
                }
                override fun close() = Unit
            }
        })
        assertEquals(3, calls)
        assertEquals(List(3) { "A deliberately repeated sentence." }, passages.map { it.text })
        assertEquals(passages[1].end, passages[2].start)
    }

    @Test fun pauseBeforeTenSecondsOfNewAudioCannotSplitTheWindow() {
        val earlier = 39 * RATE + RATE / 2
        val lengths = mutableListOf<Int>()
        val source = pcm(61 * RATE) { index ->
            if (index in earlier until earlier + 12 * (RATE / 50)) 0
            else if (index % 2 == 0) 20 else -20
        }
        SavedAudioPreview.process(source, decoderFactory = {
            object : SavedAudioPreview.Decoder {
                override fun decode(samples: FloatArray): String {
                    lengths += samples.size
                    return "The recording continues."
                }
                override fun close() = Unit
            }
        })
        // The second window starts at 29.5s, but new audio starts at 30s.
        // The 39.5s pause must not qualify relative to the overlapping start.
        assertEquals(listOf(30 * RATE, 26 * RATE + OVERLAP, TAIL + OVERLAP), lengths)
    }

    @Test fun exactZeroAndConstantOffsetSkipEvenOpeningTheDecoder() {
        for (sample in listOf(0, -151)) {
            val file = pcm(WINDOW + 1) { sample }
            val progress = mutableListOf<Pair<Int, Int>>()
            val preview = SavedAudioPreview.process(file, decoderFactory = {
                error("Exact constant audio should not load the recognizer.")
            }, onProgress = { covered, total -> progress += covered to total })
            assertTrue(preview.isEmpty())
            assertEquals(listOf(WINDOW + 1 - TAIL to WINDOW + 1, WINDOW + 1 to WINDOW + 1), progress)
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
        const val WINDOW = 30 * RATE
        const val OVERLAP = RATE / 2
        const val TAIL = 5 * RATE
    }
}
