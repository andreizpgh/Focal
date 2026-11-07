package app.focal.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SpeechModelsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob())
    private val attempts = AtomicInteger()
    @Volatile private var outcome: () -> ModelStatus = { error("Not enough free storage") }
    private val models = SpeechModels(scope) { publish ->
        attempts.incrementAndGet()
        publish(ModelStatus.Installing(1, 2))
        outcome()
    }

    @After fun cleanup() = scope.cancel()

    private fun settle(models: SpeechModels = this.models): ModelStatus = runBlocking {
        withTimeout(10_000) { models.status.first { it !is ModelStatus.Checking && it !is ModelStatus.Installing } }
    }

    private var pid = 1
    private fun guard() = EngineGuard(File(folder.root, "speech-guard"), ProcessIdentity("process-$pid", pid++), { _, _ -> null })

    @Test fun noInstallStartsWhileTheEngineGuardIsBlockedAndRetryStartsIt() {
        // Two processes died while Silero VAD was created.
        repeat(EngineGuard.MAX_DEATHS) { guard().enter(EngineStage.VAD_OPEN) }
        val guard = guard()
        assertFalse(guard.allows())
        val files = mapOf("tokens.txt" to File("tokens.txt"))
        outcome = { ModelStatus.Ready(files) }
        val guarded = SpeechModels(scope, guard) { publish ->
            attempts.incrementAndGet()
            // The install runs inside its stage: a death here would be counted at the next start.
            assertEquals(EngineStage.INSTALL, guard.markedOnDisk())
            publish(ModelStatus.Installing(1, 2))
            outcome()
        }
        guarded.ensureStarted()
        val stopped = settle(guarded)
        assertTrue(stopped is ModelStatus.Failed && stopped.permanent)
        // A foreground start does not try again; only the person's Retry does, after the guard's reset.
        guarded.retry(automatic = true)
        assertEquals(0, attempts.get())
        guard.reset()
        guarded.retry()
        assertEquals(ModelStatus.Ready(files), settle(guarded))
        assertEquals(1, attempts.get())
        assertNull(guard.markedOnDisk())
    }

    @Test fun deathsAtInstallNeverStopTheInstall() {
        // Two first-launch copies were ended from outside (a swipe-away or a reboot, with no exit record).
        repeat(EngineGuard.MAX_DEATHS) { guard().enter(EngineStage.INSTALL) }
        val guard = guard()
        assertTrue(guard.allows())
        val files = mapOf("tokens.txt" to File("tokens.txt"))
        val guarded = SpeechModels(scope, guard) { ModelStatus.Ready(files) }
        guarded.ensureStarted()
        assertEquals(ModelStatus.Ready(files), settle(guarded))
        assertEquals(emptyMap<EngineStage, Int>(), guard.deaths())
    }

    @Test fun aCompletedInstallForgetsTheDeathsCountedAtInstall() {
        guard().enter(EngineStage.INSTALL)
        val guard = guard()
        assertEquals(mapOf(EngineStage.INSTALL to 1), guard.deaths())
        val files = mapOf("tokens.txt" to File("tokens.txt"))
        val guarded = SpeechModels(scope, guard) { ModelStatus.Ready(files) }
        guarded.ensureStarted()
        assertEquals(ModelStatus.Ready(files), settle(guarded))
        assertEquals(emptyMap<EngineStage, Int>(), guard.deaths())
    }

    @Test fun aFailedInstallIsNotRepeatedByPolling() {
        models.ensureStarted()
        assertEquals(ModelStatus.Failed("Not enough free storage"), settle())
        // The speech worker polls ensureStarted(): the failure stays visible and nothing is re-run.
        repeat(20) { models.ensureStarted() }
        assertEquals(ModelStatus.Failed("Not enough free storage"), models.status.value)
        assertNull(runBlocking { models.awaitFiles() })
        assertEquals(1, attempts.get())
    }

    @Test fun retryRunsTheInstallAgain() {
        models.ensureStarted()
        settle()
        val files = mapOf("tokens.txt" to File("tokens.txt"))
        outcome = { ModelStatus.Ready(files) }
        models.retry(automatic = true)
        assertEquals(ModelStatus.Ready(files), settle())
        assertEquals(2, attempts.get())
        // Nothing to retry once ready.
        models.retry()
        assertEquals(2, attempts.get())
    }

    @Test fun aFailureSaysWhetherStorageRanShort() {
        // Too little free storage: what is missing, and a foreground start tries again once space may have returned.
        outcome = { throw InsufficientStorageException(850_000_000) }
        models.ensureStarted()
        val short = settle()
        assertTrue(short is ModelStatus.Failed && short.failure == ModelFailure.STORAGE && short.shortBytes == 850_000_000L && !short.permanent)
        models.retry(automatic = true)
        settle()
        assertEquals(2, attempts.get())
        // Any other failure is the install's, except a damaged APK copy: its own, which a copy again cannot mend.
        outcome = { error("Could not prepare private storage for the speech model.") }
        models.retry(automatic = true)
        val other = settle()
        assertTrue(other is ModelStatus.Failed && other.failure == ModelFailure.INSTALL && !other.permanent)
        assertEquals(app.focal.data.WaitCause.ModelInstall, (other as ModelStatus.Failed).waitCause())
        outcome = { throw BundledModelException("The bundled speech model is damaged. Reinstall Focal.") }
        models.retry(automatic = true)
        val damaged = settle()
        assertTrue(damaged is ModelStatus.Failed && damaged.failure == ModelFailure.DAMAGED && damaged.permanent)
        assertEquals(app.focal.data.WaitCause.ModelDamaged, (damaged as ModelStatus.Failed).waitCause())
    }

    @Test fun storageThatFillsDuringTheCopyIsAStorageFailure() {
        val write = java.io.IOException("write failed: ENOSPC (No space left on device)")
        assertTrue(UltraModel.outOfSpace(write))
        assertTrue(UltraModel.outOfSpace(IllegalStateException("Copy failed", write)))
        assertFalse(UltraModel.outOfSpace(java.io.IOException("write failed: EIO (I/O error)")))
    }

    @Test fun aDamagedApkCopyIsRetriedOnlyByTheUser() {
        outcome = { throw BundledModelException("The bundled speech model is damaged. Reinstall Focal.") }
        models.ensureStarted()
        val failed = settle()
        assertTrue(failed is ModelStatus.Failed && failed.permanent)
        models.retry(automatic = true) // a foreground start
        assertEquals(1, attempts.get())
        models.retry() // the user's Retry
        settle()
        assertEquals(2, attempts.get())
    }
}
