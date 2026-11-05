package app.focal.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class UltraWindowPlannerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun liveWaitsForFiveSecondsBeyondTheMaximumAndFinishedKeepsTinyTail() {
        val file = constantPcm(35_000)
        assertNull(UltraWindowPlanner.plan(file, 0, null, 34_999, false))
        assertEquals(UltraWindowPlanner.Window(0, 30_000, 29_500),
            UltraWindowPlanner.plan(file, 0, null, 35_000, false))
        assertEquals(UltraWindowPlanner.Window(0, 1, 1),
            UltraWindowPlanner.plan(file, 0, null, 1, true))
        assertNull(UltraWindowPlanner.plan(file, 1, 1, 1, true))
    }

    @Test fun finishedRecordingJustPastThirtySecondsReservesFiveSecondTail() {
        val file = constantPcm(30_001)
        val first = UltraWindowPlanner.plan(file, 0, null, 30_001, true)!!
        assertEquals(UltraWindowPlanner.Window(0, 25_001, 24_501), first)
        assertEquals(UltraWindowPlanner.Window(24_501, 30_001, 30_001),
            UltraWindowPlanner.plan(file, first.nextStartMs, first.endMs, 30_001, true))
    }

    @Test fun latestQualifyingPauseCutsAtMidpointWithoutOverlap() {
        val file = varyingPcm(38_000, 26_500L until 26_740L, 28_000L until 28_240L)
        val first = UltraWindowPlanner.plan(file, 0, null, 38_000, true)
        assertEquals(UltraWindowPlanner.Window(0, 28_120, 28_120), first)
        assertEquals(UltraWindowPlanner.Window(28_120, 38_000, 38_000),
            UltraWindowPlanner.plan(file, first!!.nextStartMs, first.endMs, 38_000, true))
    }

    @Test fun overlappingWindowMeasuresTenNewSecondsFromPreviousEnd() {
        val file = varyingPcm(61_000, 39_500L until 39_740L, 44_000L until 44_240L)
        val first = UltraWindowPlanner.plan(file, 0, null, 61_000, true)!!
        assertEquals(UltraWindowPlanner.Window(0, 30_000, 29_500), first)
        val second = UltraWindowPlanner.plan(file, first.nextStartMs, first.endMs, 61_000, true)!!
        assertEquals(UltraWindowPlanner.Window(29_500, 44_120, 44_120), second)
        assertEquals(UltraWindowPlanner.Window(44_120, 61_000, 61_000),
            UltraWindowPlanner.plan(file, second.nextStartMs, second.endMs, 61_000, true))
    }

    @Test fun hardCutCoversMoreThanThreeMinutesAndRestartPlansSameWindow() {
        val total = 216_001L
        val file = constantPcm(total)
        val windows = mutableListOf<UltraWindowPlanner.Window>()
        var start = 0L
        var previous: Long? = null
        while (start < total) {
            val next = UltraWindowPlanner.plan(file, start, previous, total, true)
            assertNotNull(next)
            val window = next!!
            assertEquals(window, UltraWindowPlanner.plan(file, start, previous, total, true))
            assertTrue(window.endMs - window.startMs in 1L..30_000L)
            if (previous != null) assertEquals(500L, previous - window.startMs)
            windows += window
            previous = window.endMs
            start = window.nextStartMs
        }
        assertTrue(windows.size > 6)
        assertEquals(0L, windows.first().startMs)
        assertEquals(total, windows.last().endMs)
    }

    @Test fun onlyDurableBytesCanAffectPlanningAndShortFilesAreRejected() {
        val file = varyingPcm(40_000, 28_000L until 28_240L)
        val original = UltraWindowPlanner.plan(file, 0, null, 35_000, false)
        RandomAccessFile(file, "rw").use { output ->
            output.seek(35_000L * 32)
            output.write(ByteArray(5_000 * 32) { 0x7f })
        }
        assertEquals(original, UltraWindowPlanner.plan(file, 0, null, 35_000, false))
        assertEquals(UltraWindowPlanner.Window(0, 28_120, 28_120), original)
        assertThrows(IllegalStateException::class.java) {
            UltraWindowPlanner.plan(file, 0, null, 40_001, true)
        }
    }

    @Test fun invalidWatermarksAndPreviousRangeAreRejected() {
        val file = constantPcm(1_000)
        assertThrows(IllegalArgumentException::class.java) {
            UltraWindowPlanner.plan(file, -1, null, 1_000, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraWindowPlanner.plan(file, 100, 99, 1_000, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraWindowPlanner.plan(file, 0, 501, 1_000, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraWindowPlanner.plan(file, 0, null, Long.MAX_VALUE, true)
        }
    }

    private fun constantPcm(milliseconds: Long): File = temporary.newFile().also { file ->
        RandomAccessFile(file, "rw").use { it.setLength(milliseconds * 32) }
    }

    private fun varyingPcm(milliseconds: Int, vararg pauses: LongRange): File = temporary.newFile().also { file ->
        file.outputStream().buffered().use { output ->
            val buffer = ByteArray(8_192)
            var sample = 0
            val total = milliseconds * 16
            while (sample < total) {
                val count = minOf(buffer.size / 2, total - sample)
                for (index in 0 until count) {
                    val timeMs = (sample + index) / 16L
                    val value = if (pauses.any { timeMs in it }) 0 else if ((sample + index) % 2 == 0) 500 else -500
                    buffer[index * 2] = value.toByte()
                    buffer[index * 2 + 1] = (value shr 8).toByte()
                }
                output.write(buffer, 0, count * 2)
                sample += count
            }
        }
    }
}
