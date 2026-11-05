package app.focal

import android.app.Application
import app.focal.data.NoteStore
import app.focal.data.Preferences
import app.focal.data.RetiredFiles
import kotlinx.coroutines.*

class FocalApplication : Application() {
    lateinit var store: NoteStore
        private set
    lateinit var preferences: Preferences
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile lateinit var storageReady: Deferred<Unit>
        private set
    override fun onCreate() {
        super.onCreate()
        store = NoteStore(this); preferences = Preferences(this)
        storageReady = scope.async { store.recover() }
        // After recovery, whatever its result: drop model files that Whisper-era builds left behind.
        scope.launch { storageReady.join(); RetiredFiles.remove(filesDir) }
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
