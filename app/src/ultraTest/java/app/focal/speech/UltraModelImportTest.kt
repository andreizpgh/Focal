package app.focal.speech

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.FocalApplication
import app.focal.FocalViewModel
import app.focal.recording.SessionOperations
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UltraModelImportTest {
    @Test fun importBlocksPreviewAndCaptureUntilValidationFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { withTimeout(10_000) { app.storageReady.await(); app.initialized.await() } }
        assertTrue(app.models.ready())
        val originalHash = sha256(app.models.model)
        val invalid = File.createTempFile("invalid-whisper-", ".bin", app.cacheDir)
        val owner = ViewModelStore()
        var importJob: Job? = null
        try {
            invalid.writeBytes(ByteArray(128))
            lateinit var model: FocalViewModel
            instrumentation.runOnMainSync {
                val provider = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))
                model = provider[FocalViewModel::class.java]
            }
            runBlocking {
                withTimeout(10_000) { model.preparing.first { !it } }
                val failure = async(start = CoroutineStart.UNDISPATCHED) { model.errors.first() }
                val request = async(Dispatchers.Main, start = CoroutineStart.LAZY) {
                    model.importModel(Uri.fromFile(invalid)).also { importJob = it }
                }
                try {
                    synchronized(app.models) {
                        // The IO worker cannot enter the synchronized installer yet.
                        request.start()
                        runBlocking { withTimeout(10_000) { request.await(); model.preparing.first { it } } }
                        assertBlocked(capture = false, noteId = "ultra-import-test")
                        assertBlocked(capture = true, noteId = null)
                    }
                    withTimeout(10_000) {
                        assertEquals("This file is not a complete speech model.", failure.await())
                        request.await().join()
                    }
                } finally {
                    failure.cancel()
                    request.cancel()
                }
            }
            checkNotNull(SessionOperations.reserveSession(capture = false, noteId = null)).release()
            assertFalse(model.preparing.value)
            assertEquals(originalHash, sha256(app.models.model))
        } finally {
            try {
                instrumentation.runOnMainSync { owner.clear() }
                runBlocking { withTimeout(10_000) { importJob?.join() } }
            } finally {
                invalid.delete()
            }
        }
    }

    private fun assertBlocked(capture: Boolean, noteId: String?) {
        val claim = SessionOperations.reserveSession(capture, noteId)
        try {
            assertNull("Model import must hold the shared session claim.", claim)
        } finally {
            claim?.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
