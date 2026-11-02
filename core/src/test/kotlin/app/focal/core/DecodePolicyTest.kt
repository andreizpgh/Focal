package app.focal.core

import kotlin.test.*
import org.junit.Test

class DecodePolicyTest {
    @Test fun `healthy phones do not wait between chunks`() {
        assertEquals(0, DecodePolicy.cooldownMs(40_000, false, false))
        assertEquals(4, DecodePolicy.threads(8, false, false))
        assertEquals(1, DecodePolicy.threads(1, false, false))
    }
    @Test fun `power and thermal pressure limit work`() {
        assertEquals(1, DecodePolicy.threads(8, true, false))
        assertEquals(2, DecodePolicy.threads(8, false, true))
        assertEquals(3000, DecodePolicy.cooldownMs(40_000, false, true))
        assertEquals(150, DecodePolicy.cooldownMs(20, true, false))
    }
}
