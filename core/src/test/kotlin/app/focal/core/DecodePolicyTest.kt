package app.focal.core

import kotlin.test.*
import org.junit.Test

class DecodePolicyTest {
    @Test fun `healthy phones use up to four performance cores and do not wait between windows`() {
        assertEquals(0, DecodePolicy.cooldownMs(40_000, false, false))
        assertEquals(4, DecodePolicy.threads(6, false, false))
        assertEquals(3, DecodePolicy.threads(3, false, false))
        assertEquals(1, DecodePolicy.threads(1, false, false))
        assertEquals(1, DecodePolicy.threads(0, false, false))
    }

    @Test fun `power and thermal pressure limit work to two threads`() {
        assertEquals(2, DecodePolicy.threads(8, true, false))
        assertEquals(2, DecodePolicy.threads(8, false, true))
        assertEquals(1, DecodePolicy.threads(1, true, true))
        assertEquals(3000, DecodePolicy.cooldownMs(40_000, false, true))
        assertEquals(150, DecodePolicy.cooldownMs(20, true, false))
    }

    @Test fun `performance cores exclude the slowest cluster`() {
        // Tensor-like 2 + 5 + 1 layout and a 6 + 2 mid-range layout.
        val tensor = listOf(1_800_000L, 1_800_000L, 2_400_000L, 2_400_000L, 2_400_000L, 2_400_000L, 2_400_000L, 3_100_000L)
        assertEquals(6, DecodePolicy.performanceCores(tensor, 8))
        val midRange = List(6) { 1_800_000L } + List(2) { 2_200_000L }
        assertEquals(2, DecodePolicy.performanceCores(midRange, 8))
    }

    @Test fun `uniform or unknown frequencies fall back safely`() {
        assertEquals(4, DecodePolicy.performanceCores(List(4) { 2_000_000L }, 4))
        assertEquals(4, DecodePolicy.performanceCores(listOf(1L, null, 2L, 3L, 4L, 5L, 6L, 7L), 8))
        assertEquals(1, DecodePolicy.performanceCores(emptyList(), 1))
    }
}
