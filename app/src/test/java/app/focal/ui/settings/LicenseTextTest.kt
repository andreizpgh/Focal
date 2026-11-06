package app.focal.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseTextTest {
    private val file = "Focal\n\nMIT License\nline two\n\nApache License 2.0: Kotlin, AndroidX\n\nApache text\n\nInter 4.1 (OFL)\n\nOFL text"

    @Test fun sectionsRunFromTheirHeadingToTheNext() {
        assertEquals("Focal\n\nMIT License\nline two", LicenseText.section(file, null, "Apache License 2.0:"))
        assertEquals("Inter 4.1 (OFL)\n\nOFL text", LicenseText.section(file, "Inter ", null))
        assertEquals(listOf("Focal", "MIT License line two"), LicenseText.paragraphs(LicenseText.section(file, null, "Apache")))
    }

    /** Column wraps of prose join; headings, labelled URLs, lists and rules keep their own lines (spec 5.10). */
    @Test fun reflowJoinsOnlyWrappedProse() {
        val attribution = "Parakeet Ultra, INT8 ONNX (Marmalade release v1)\n" +
            "Moondream (M87 Labs, Inc.) created Parakeet Ultra by post-training NVIDIA's Parakeet TDT 0.6B v3.\n" +
            "Moondream model: https://huggingface.co/moondream/parakeet-ultra/tree/73175eb7\n" +
            "NVIDIA base model: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99"
        assertEquals(
            "Parakeet Ultra, INT8 ONNX (Marmalade release v1)\n" +
                "Moondream (M87 Labs, Inc.) created Parakeet Ultra by post-training NVIDIA's Parakeet TDT 0.6B v3.\n" +
                "Moondream model:\nhttps://huggingface.co/moondream/parakeet-ultra/tree/73175eb7\n" +
                "NVIDIA base model:\nhttps://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99",
            LicenseText.reflow(attribution),
        )
        assertTrue(LicenseText.isUrl("https://huggingface.co/moondream") && !LicenseText.isUrl("Moondream model:"))
        val prose = "Creative Commons Corporation (\"Creative Commons\") is not a law firm and\n" +
            "does not provide legal services or legal advice. Distribution of\n" +
            "Creative Commons public licenses does not create a lawyer-client or\n" +
            "other relationship.\n" +
            "Using Creative Commons Public Licenses"
        assertEquals(
            "Creative Commons Corporation (\"Creative Commons\") is not a law firm and does not provide legal services or " +
                "legal advice. Distribution of Creative Commons public licenses does not create a lawyer-client or other " +
                "relationship.\nUsing Creative Commons Public Licenses",
            LicenseText.reflow(prose),
        )
        assertEquals("=====\nCreative Commons", LicenseText.reflow("=====\nCreative Commons"))
        val hanging = "      (a) You must give any other recipients of the Work or\n          Derivative Works a copy of this License; and"
        assertEquals("(a) You must give any other recipients of the Work or Derivative Works a copy of this License; and", LicenseText.reflow(hanging))
        val release = "Focal distributes the four v1 files as published.\nMarmalade release: https://github.com/maxwhipw/releases/tag/v1"
        assertEquals(
            "Focal distributes the four v1 files as published.\nMarmalade release:\nhttps://github.com/maxwhipw/releases/tag/v1",
            LicenseText.reflow(release),
        )
    }

    /** Every entry on the page opens its own text in the notices the APK ships, the model's attribution included. */
    @Test fun everyEntryFindsItsTextInTheShippedNotices() {
        val files = listOf(LicenseText.APP, LicenseText.ULTRA).associateWith { File("src/main/assets/$it").readText() }
        val sections = LicenseEntries.map { LicenseText.section(files.getValue(it.asset), it.start, it.end) }
        sections.forEachIndexed { index, text -> assertTrue("Entry $index has no text", text.isNotBlank()) }
        val (parakeet, silero, sherpa, onnx, inter) = sections
        assertTrue(parakeet.contains("Moondream") && parakeet.contains("NVIDIA") && parakeet.contains("Attribution 4.0 International"))
        assertTrue(silero.startsWith("Silero VAD") && !silero.contains("sherpa-onnx 2"))
        assertTrue(sherpa.contains("Apache License"))
        assertTrue(onnx.startsWith("ONNX Runtime") && onnx.contains("MIT License"))
        assertTrue(inter.contains("SIL OPEN FONT LICENSE"))
        sections.drop(5).dropLast(1).forEach { apache ->
            assertTrue(apache.contains("Apache License") && apache.contains("Kotlin") && apache.contains("Material Symbols"))
            assertFalse("The shared Apache text stops before Inter", apache.contains("SIL OPEN FONT"))
        }
        assertTrue(sections.last().startsWith("Focal") && !sections.last().contains("Apache License"))
        // The page's precomputed paragraphs (each file split once) are exactly each section's own.
        assertEquals(sections.map(LicenseText::paragraphs), LicenseText.entries(files))
    }
}
