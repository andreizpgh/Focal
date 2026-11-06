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
    }
}
