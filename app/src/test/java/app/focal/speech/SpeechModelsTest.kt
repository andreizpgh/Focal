package app.focal.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SpeechModelsTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val attempts = AtomicInteger()
    @Volatile private var outcome: () -> ModelStatus = { error("Not enough free storage") }
    private val models = SpeechModels(scope) { publish ->
        attempts.incrementAndGet()
        publish(ModelStatus.Installing(1, 2))
        outcome()
    }

    @After fun cleanup() = scope.cancel()

    private fun settle(): ModelStatus = runBlocking {
        withTimeout(10_000) { models.status.first { it !is ModelStatus.Checking && it !is ModelStatus.Installing } }
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
