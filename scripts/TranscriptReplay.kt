package app.focal.tools

import app.focal.core.Transcript
import java.io.File
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.util.Base64
import kotlin.system.exitProcess

/** Bounded transport around the actual production context and boundary functions. */
object TranscriptReplay {
    const val HEADER = "startMs\tendMs\tuncertain\ttextBase64"
    private const val MAX_ROWS = 100
    private const val MAX_TEXT_BYTES = 65_536
    private const val MAX_ENCODED_BYTES = ((MAX_TEXT_BYTES + 2) / 3) * 4
    private const val MAX_ROW_BYTES = MAX_ENCODED_BYTES + 128
    private const val MAX_PROBABILITIES = 1024
    private const val USAGE = "Usage: TranscriptReplay context previous.tsv beforeMs | reconcile previous.tsv decoded.tsv chunkStartMs chunkEndMs [overlapEndMs] | review noSpeech probabilitiesCSV"

    @JvmStatic fun main(args: Array<String>) {
        try {
            execute(args, System.out)
            check(!System.out.checkError()) { "Could not write the transcript replay result." }
        } catch (error: Exception) {
            System.err.println("Transcript replay failed: ${error.message ?: "Could not process the segment input."}")
            exitProcess(2)
        }
    }

    fun execute(args: Array<String>, output: Appendable) {
        when (args.firstOrNull()) {
            "context" -> {
                require(args.size == 3) { USAGE }
                val previous = readFile(args[1])
                val context = Transcript.trustedContext(previous, timestamp(args[2]))
                output.append(encode(context)).append('\n')
            }
            "reconcile" -> {
                require(args.size == 5 || args.size == 6) { USAGE }
                val previous = readFile(args[1])
                val decoded = readFile(args[2])
                val result = Transcript.reconcileBoundary(previous, decoded, timestamp(args[3]), timestamp(args[4]),
                    args.getOrNull(5)?.let(::timestamp))
                writeSegments(result, output)
            }
            "review" -> {
                require(args.size == 3) { USAGE }
                val csv = args[2]
                require(args[1].length <= 64 && csv.length <= 65_536 && csv.count { it == ',' } < MAX_PROBABILITIES) {
                    "Review input is limited to $MAX_PROBABILITIES probabilities and bounded numeric text."
                }
                val probabilities = if (csv.isBlank()) emptyList() else csv.split(',').map(::probability)
                output.append(Transcript.needsReview(probabilities, probability(args[1])).toString()).append('\n')
            }
            else -> error(USAGE)
        }
    }

    private fun readFile(path: String) = File(path).inputStream().use(::readSegments)

    fun readSegments(input: InputStream): List<Transcript.Segment> {
        val source = input.buffered(16_384)
        val rowBytes = ByteArray(MAX_ROW_BYTES)
        val result = mutableListOf<Transcript.Segment>()
        var row = 0
        while (true) {
            val line = readRow(source, rowBytes) ?: break
            row++
            if (row == 1 && line == HEADER) continue
            require(result.size < MAX_ROWS) { "A segment file exceeds $MAX_ROWS rows." }
            val fields = line.split('\t', limit = 4)
            require(fields.size == 4) { "Invalid segment columns at row $row." }
            val start = timestamp(fields[0])
            val end = timestamp(fields[1])
            require(end >= start) { "A segment ends before it starts at row $row." }
            val uncertain = when (fields[2]) {
                "true" -> true
                "false" -> false
                else -> error("Invalid uncertainty flag at row $row.")
            }
            require(fields[3].length <= MAX_ENCODED_BYTES) { "Segment text exceeds $MAX_TEXT_BYTES UTF-8 bytes at row $row." }
            val textBytes = try { Base64.getDecoder().decode(fields[3]) }
            catch (_: IllegalArgumentException) { error("Invalid base64 text at row $row.") }
            require(textBytes.size <= MAX_TEXT_BYTES) { "Segment text exceeds $MAX_TEXT_BYTES UTF-8 bytes at row $row." }
            val text = try { textBytes.decodeToString(throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) { error("Invalid UTF-8 text at row $row.") }
            result += Transcript.Segment(start, end, text, uncertain)
        }
        return result
    }

    private fun readRow(input: InputStream, bytes: ByteArray): String? {
        var count = 0
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (count == 0) return null
                break
            }
            if (next == '\n'.code) break
            require(count < bytes.size) { "A segment row exceeds its bounded input size." }
            require(next < 128) { "TSV transport must be ASCII with base64 UTF-8 text." }
            bytes[count++] = next.toByte()
        }
        if (count > 0 && bytes[count - 1] == '\r'.code.toByte()) count--
        return String(bytes, 0, count, Charsets.US_ASCII)
    }

    fun writeSegments(segments: List<Transcript.Segment>, output: Appendable) {
        output.append(HEADER).append('\n')
        segments.forEach { segment ->
            output.append(segment.start.toString()).append('\t').append(segment.end.toString()).append('\t')
                .append(segment.uncertain.toString()).append('\t').append(encode(segment.text)).append('\n')
        }
    }

    private fun timestamp(value: String): Long = value.toLongOrNull()?.takeIf { it >= 0 }
        ?: error("Choose nonnegative millisecond timestamps within a signed 64-bit integer.")

    private fun probability(value: String): Float = try { java.lang.Float.parseFloat(value) }
        catch (_: NumberFormatException) { Float.NaN }

    private fun encode(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
}
