package app.focal.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageReserveTest {
    @Test fun missingSpaceIsRoundedUpToFiftyMegabytes() {
        assertEquals(0L, StorageReserve.shortfall(StorageReserve.START_BYTES, StorageReserve.START_BYTES))
        assertEquals(50_000_000L, StorageReserve.shortfall(StorageReserve.START_BYTES - 1, StorageReserve.START_BYTES))
        assertEquals(200_000_000L, StorageReserve.shortfall(0, StorageReserve.START_BYTES))
        assertEquals(100_000_000L, StorageReserve.shortfall(60_000_000, StorageReserve.RESUME_BYTES))
    }
}

class WakePolicyTest {
    private val minute = 60_000L

    @Test fun anHourOfCaptureWithoutDecodesKeepsTheLockRenewed() {
        // Capture asks every second; nothing is decoded (long silence, model still installing).
        var held = false
        var acquiredAt = 0L
        var acquisitions = 0
        var now = 0L
        while (now <= 60 * minute) {
            if (held && now - acquiredAt >= WakePolicy.TIMEOUT_MS) held = false // the timeout would release it
            when (WakePolicy.action(wanted = true, held = held, acquiredAtMs = acquiredAt, nowMs = now)) {
                WakePolicy.Action.ACQUIRE -> { held = true; acquiredAt = now; acquisitions++ }
                WakePolicy.Action.RELEASE -> held = false
                WakePolicy.Action.KEEP -> Unit
            }
            assertTrue("Lock lost at ${now / 1000} s", held)
            now += 1_000
        }
        assertEquals(3, acquisitions) // at 0, 30 and 60 minutes
    }

    @Test fun anExpiredLockIsAcquiredAgainAndAnUnwantedOneReleased() {
        assertEquals(WakePolicy.Action.ACQUIRE, WakePolicy.action(true, held = false, acquiredAtMs = 0, nowMs = 5 * minute))
        assertEquals(WakePolicy.Action.KEEP, WakePolicy.action(true, held = true, acquiredAtMs = 0, nowMs = 29 * minute))
        assertEquals(WakePolicy.Action.RELEASE, WakePolicy.action(false, held = true, acquiredAtMs = 0, nowMs = minute))
        assertEquals(WakePolicy.Action.KEEP, WakePolicy.action(false, held = false, acquiredAtMs = 0, nowMs = minute))
    }
}
