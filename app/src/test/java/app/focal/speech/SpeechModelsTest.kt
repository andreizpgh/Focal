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
        // Two processes died while the model was being installed.
        repeat(EngineGuard.MAX_DEATHS) { guard().enter(EngineStage.INSTALL) }
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
