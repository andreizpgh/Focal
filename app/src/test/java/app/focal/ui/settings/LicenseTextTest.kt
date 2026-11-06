package app.focal.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseTextTest {
    private val file = "Focal\n\nMIT License\nline two\n\nKotlin, AndroidX (Apache)\n\nApache text\n\nInter 4.1 (OFL)\n\nOFL text"

    @Test fun sectionsRunFromTheirHeadingToTheNext() {
        assertEquals("Focal\n\nMIT License\nline two", LicenseText.section(file, null, "Kotlin, AndroidX"))
        assertEquals("Inter 4.1 (OFL)\n\nOFL text", LicenseText.section(file, "Inter ", null))
        assertEquals(listOf("Focal", "MIT License line two"), LicenseText.paragraphs(LicenseText.section(file, null, "Kotlin")))
    }
}
