package app.focal

import android.app.Application
import app.focal.data.NoteStore
import app.focal.data.Preferences
import app.focal.speech.ModelFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class FocalApplication : Application() {
    lateinit var store: NoteStore
        private set
    lateinit var preferences: Preferences
        private set
    lateinit var models: ModelFiles
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val startupError = MutableStateFlow<String?>(null)
    @Volatile lateinit var storageReady: Deferred<Unit>
        private set
    lateinit var initialized: Deferred<Unit>
        private set
    override fun onCreate() {
        super.onCreate()
        store = NoteStore(this); preferences = Preferences(this); models = ModelFiles(this)
        storageReady = scope.async { store.recover() }
        initialized = scope.async {
            // Model setup remains available if recovering the library failed. The
            // library exposes that failure separately and can retry recovery.
            try { storageReady.await() }
            catch (e: Exception) { if (e is CancellationException) throw e }
            try { models.installBundled() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                startupError.value = e.message ?: "Could not prepare the speech model."
            }
        }
    }

    /** Retry only a failed recovery; never run recovery over an active recording. */
    @Synchronized fun retryStorage() {
        val previous = storageReady
        storageReady = scope.async {
            try { previous.await() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                store.recover()
            }
        }
    }
}
