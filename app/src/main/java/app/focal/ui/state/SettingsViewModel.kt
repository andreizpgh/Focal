package app.focal.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.BuildConfig
import app.focal.FocalApplication
import app.focal.speech.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Theme, the space kept audio uses, and the version. Audio space is re-read when the store changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store

    private val usage = store.changes.mapLatest {
        try {
            app.storageReady.await()
            val withAudio = store.library().filter { it.audioPresent }
            withAudio.sumOf { store.audioBytes(it.id) } to withAudio.size
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            0L to 0
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<SettingsUiState> = combine(app.preferences.settings, usage) { settings, (bytes, notes) ->
        SettingsUiState(ThemeMode.of(settings.theme), bytes, notes, BuildConfig.VERSION_NAME)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(versionName = BuildConfig.VERSION_NAME))

    /** The speech model's install state (Settings may show it; recording never waits for it). */
    val model: StateFlow<ModelStatus> = app.models.status

    fun setTheme(mode: ThemeMode) = app.preferences.theme(mode.key)
}
