package app.focal.tools

import app.focal.core.Transcript
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64

/** Transport and CLI integration checks against production Transcript behavior. */
object TranscriptReplayTest {
    @JvmStatic fun main(args: Array<String>) {
        val unusual = "«Привет»\t\"quoted\"\nnext\rline\u0000🙂 e\u0301"
        val original = listOf(Transcript.Segment(3_000_000_000L, 3_000_000_100L, unusual, true),
            Transcript.Segment(3_000_000_100L, 3_000_000_200L, "", true))
        val wire = serialized(original)
        check(wire.lines().count { it.isNotEmpty() } == 3) { "Control characters escaped the base64 text field." }
        check(parsed(wire) == original)
        check(parsed(wire.replace("\n", "\r\n")) == original)

        val folder = Files.createTempDirectory("focal-transcript-replay-test-").toFile()
        val previousFile = folder.resolve("previous.tsv")
        val decodedFile = folder.resolve("decoded.tsv")
        try {
            val tail = "Последние «слова»\t🙂"
            previousFile.writeText(serialized(listOf(Transcript.Segment(0, 1000, "Older trusted words"),
                Transcript.Segment(1000, 2000, "Uncertain words cannot enter context", true),
                Transcript.Segment(2000, 3000, tail),
                Transcript.Segment(3000, 3300, "Partly overlapping audio must stay out"))))
            val context = execute("context", previousFile.path, "3100")
            check(decodedContext(context) == tail) { "Context crossed uncertainty or included overlapping audio." }
            previousFile.writeText(serialized(listOf(Transcript.Segment(0, 1000, "Earlier words"),
                Transcript.Segment(1000, 2000, "A manual\u0000correction"))))
            check(decodedContext(execute("context", previousFile.path, "2100")).isEmpty())

            previousFile.writeText(serialized(listOf(Transcript.Segment(0, 16000, "Мы идём в парк."))))
            decodedFile.writeText(serialized(listOf(Transcript.Segment(15500, 15950, "В парк."))))
            val missing = parsed(execute("reconcile", previousFile.path, decodedFile.path, "15500", "17000", "16000"))
            check(missing == listOf(Transcript.Segment(16000, 17000, "", true))) { "Removed overlap hid undecoded new audio." }
            val straddling = Transcript.Segment(15500, 17000, "В парк, \"а затем\" домой.\t🙂")
            decodedFile.writeText(serialized(listOf(straddling)))
            check(parsed(execute("reconcile", previousFile.path, decodedFile.path, "15500", "17000", "16000")) ==
                listOf(straddling.copy(uncertain = true))) { "Boundary reconciliation changed new speech or lost its review cue." }
            decodedFile.writeText(TranscriptReplay.HEADER + "\n")
            check(parsed(execute("reconcile", previousFile.path, decodedFile.path, "17000", "18000")) ==
                listOf(Transcript.Segment(17000, 18000, "", true)))
        } finally { folder.deleteRecursively() }

        rejected("0\t1\tTRUE\t\n")
        rejected("0\t1\tfalse\t!\n")
        rejected("0\t1\tfalse\t" + Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28)) + "\n")
        rejected(serialized(List(101) { Transcript.Segment(0, 1, "") }))
        rejected(serialized(listOf(Transcript.Segment(0, 1, "x".repeat(65_537)))))
        rejected("x".repeat(100_000))
        check(parsed("").isEmpty())
        println("TranscriptReplay integration checks passed.")
    }

    private fun serialized(segments: List<Transcript.Segment>) = StringBuilder().also {
        TranscriptReplay.writeSegments(segments, it)
    }.toString()

    private fun parsed(text: String) = TranscriptReplay.readSegments(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    private fun execute(vararg args: String) = StringBuilder().also {
        TranscriptReplay.execute(arrayOf(*args), it)
    }.toString()

    private fun decodedContext(encoded: String) = Base64.getDecoder().decode(encoded.trimEnd('\n')).decodeToString(throwOnInvalidSequence = true)

    private fun rejected(text: String) {
        try { parsed(text) }
        catch (_: IllegalArgumentException) { return }
        catch (_: IllegalStateException) { return }
        error("Malformed or oversized transport was accepted.")
    }
}

/** Run separately with -Xmx32m to guard legacy-input memory behavior. */
object TranscriptBoundsTest {
    @JvmStatic fun main(args: Array<String>) {
        val legacy = "old ".repeat(1_000_000) + "matching boundary"
        val next = Transcript.Segment(16020, 17000, "New words remain.")
        val result = Transcript.reconcileBoundary(listOf(Transcript.Segment(0, 16000, legacy)),
            listOf(Transcript.Segment(15500, 15950, "Matching boundary."), next), 15500, 17000, 16000)
        check(result == listOf(next))
        val hugeWord = "Ж".repeat(2_000_000)
        val mixed = Transcript.Segment(15500, 17000, hugeWord)
        check(Transcript.reconcileBoundary(listOf(Transcript.Segment(0, 16000, hugeWord)),
            listOf(mixed), 15500, 17000, 16000) == listOf(mixed.copy(uncertain = true)))
        println("TranscriptBoundsTest passed with a 32 MiB heap.")
    }
}
