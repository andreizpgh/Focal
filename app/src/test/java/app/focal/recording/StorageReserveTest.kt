package app.focal.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageReserveTest {
    @Test fun missingSpaceIsRoundedUpToFiftyMegabytes() {
        assertEquals(0L, StorageReserve.shortfall(StorageReserve.START_BYTES, StorageReserve.START_BYTES))
        assertEquals(50_000_000L, StorageReserve.shortfall(StorageReserve.START_BYTES - 1, StorageReserve.START_BYTES))
        assertEquals(200_000_000L, StorageReserve.shortfall(0, StorageReserve.START_BYTES))
        assertEquals(100_000_000L, StorageReserve.shortfall(60_000_000, StorageReserve.RESUME_BYTES))
    }
}
