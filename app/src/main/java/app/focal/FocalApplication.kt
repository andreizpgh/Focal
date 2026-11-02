package app.focal

import android.app.Application
import app.focal.data.NoteStore
import app.focal.data.Preferences
import app.focal.speech.ModelFiles
import kotlinx.coroutines.*

class FocalApplication : Application() {
    lateinit var store: NoteStore
        private set
    lateinit var preferences: Preferences
        private set
    lateinit var models: ModelFiles
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var initialized: Deferred<Unit>
        private set
    override fun onCreate() {
        super.onCreate()
        store = NoteStore(this); preferences = Preferences(this); models = ModelFiles(this)
        initialized = scope.async { store.recover(); models.installBundled() }
    }
}
