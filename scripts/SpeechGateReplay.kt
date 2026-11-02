package app.focal.tools

import app.focal.core.SpeechGate
import java.io.File
import java.io.InputStream
import kotlin.system.exitProcess

/** Streams the same signed PCM16 frames and final partial tail as RecordingService. */
object SpeechGateReplay {
    data class Range(val startSample: Long, val endSample: Long, val overlap: Boolean)

    fun replay(input: InputStream, publish: (Range) -> Unit): Long {
        var start: Long? = null
        var activeOverlap = false
        val gate = SpeechGate(object : SpeechGate.Sink {
            override fun begin(startSample: Long, overlap: Boolean) {
                check(start == null) { "The speech gate opened overlapping active ranges." }
                start = startSample
                activeOverlap = overlap
            }
            override fun audio(samples: ShortArray) = Unit
            override fun end(endSample: Long) {
                publish(Range(checkNotNull(start), endSample, activeOverlap))
                start = null
            }
        })
        val bytes = ByteArray(SpeechGate.FRAME_SAMPLES * 2)
        val frame = ShortArray(SpeechGate.FRAME_SAMPLES)
        var samples = 0L
        while (true) {
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) {
                    val next = input.read()
                    if (next < 0) break
                    bytes[count++] = next.toByte()
                } else count += read
            }
            require(count % 2 == 0) { "PCM16 input ends in an incomplete sample." }
            if (count == 0) break
            val sampleCount = count / 2
            repeat(sampleCount) { index ->
                frame[index] = ((bytes[index * 2].toInt() and 0xff) or
                    ((bytes[index * 2 + 1].toInt() and 0xff) shl 8)).toShort()
            }
            if (sampleCount == frame.size) gate.accept(frame) else gate.acceptPartial(frame, sampleCount)
            samples += sampleCount
            if (count < bytes.size) break
        }
        gate.flush()
        return samples
    }
}

fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("Usage: SpeechGateReplayKt <mono-16000Hz-signed-PCM16LE.pcm>")
        exitProcess(2)
    }
    try {
        val file = File(args.single())
        require(file.isFile && file.length() % 2 == 0L) { "Choose a PCM16 file with complete two-byte samples." }
        println("startSample\tendSample\toverlap")
        val total = file.inputStream().buffered(16_384).use { input ->
            SpeechGateReplay.replay(input) { range ->
                println("${range.startSample}\t${range.endSample}\t${range.overlap}")
            }
        }
        System.err.println("Replayed $total samples at ${SpeechGate.SAMPLE_RATE} Hz.")
    } catch (error: Exception) {
        System.err.println("Speech gate replay failed: ${error.message ?: "Could not read the PCM input."}")
        exitProcess(2)
    }
}
