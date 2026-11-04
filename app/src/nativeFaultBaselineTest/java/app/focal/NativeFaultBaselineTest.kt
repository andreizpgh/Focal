package app.focal

import android.util.Log
import app.focal.speech.NativeFaultHooks
import app.focal.speech.NativeWhisper
import org.junit.Assert.*
import org.junit.Test

/** Run only in the isolated baseline artifact; its native close is expected to fail. */
class NativeFaultBaselineTest {
    @Test fun allocationFailureThenCloseReproducesNativeDefect() {
        NativeFaultFixtures.ready(NativeFaultFixtures.BASELINE)
        val samples = NativeFaultFixtures.samples(NativeFaultFixtures.pcm())
        val handle = NativeWhisper.open(NativeFaultFixtures.app.models.model.absolutePath)
        val token = NativeFaultHooks.arm(NativeFaultHooks.FAIL_ALLOCATION)
        try {
            assertThrows(IllegalStateException::class.java) { NativeWhisper.decode(handle, samples, "en", 1) }
            val observed = NativeFaultHooks.snapshot(token)
            assertEquals(1L, observed.encoderCompletions)
            assertEquals(1L, observed.allocationEntries)
            assertEquals(1L, observed.refusals)
            assertTrue(observed.hasResult)
            assertEquals(-7L, observed.resultCode)
            assertFalse(observed.timedOut || observed.invalidTarget)
            Log.i("FocalNativeFault", "VERIFIED_NATIVE_FAILURE result=-7 encoder=1 refusals=1; closing baseline")
        } finally {
            NativeFaultHooks.disarm()
            NativeWhisper.close(handle)
        }
        // A surviving baseline still fails; arbitrary instrumentation failure is not proof.
        fail("The pinned baseline unexpectedly survived the targeted native error/close path")
    }
}
