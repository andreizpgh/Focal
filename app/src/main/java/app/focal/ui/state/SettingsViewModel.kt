package app.focal.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.BuildConfig
import app.focal.FocalApplication
import app.focal.data.Settings
import app.focal.speech.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Theme, the space kept audio uses, and the version. The settings are there from the first frame (read
 * synchronously), so nothing on the page moves to its value as it slides in. Audio space is read once when the page
 * is first composed (off screen, before the first swipe), then again when the store changes, only while the page is
 * on screen ([setVisible]): settings stays composed beside the library, and a live recording changes the store with
 * every transcribed window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store
    private val visible = MutableStateFlow(false)

    /** Bytes of kept audio and the notes that keep it; a newer read asked for meanwhile stops it between two files. */
    private suspend fun scan(): Pair<Long, Int> = try {
        app.storageReady.await()
        val withAudio = store.library().filter { it.audioPresent }
        var bytes = 0L
        for (note in withAudio) {
            currentCoroutineContext().ensureActive()
            bytes += store.audioBytes(note.id)
        }
        bytes to withAudio.size
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        0L to 0
    }

    /** The last usage read: a restarted [usage] shows it until its new read is in, instead of nothing. */
    @Volatile private var lastUsage: Pair<Long, Int>? = null

    /**
     * Null until the first read: the theme and the switch never wait for it. Read once at the start, then again for
     * each change of the store while the page is on screen (one read when both ask at once: the latest wins).
     */
    private val usage: Flow<Pair<Long, Int>?> = merge(
        flowOf(Unit),
        visible.flatMapLatest { shown -> if (!shown) emptyFlow() else store.changes.map { } },
    ).mapLatest<Unit, Pair<Long, Int>?> { scan() }
        .onEach { lastUsage = it }
        .flowOn(Dispatchers.IO)
        .onStart { emit(lastUsage) }

    private fun stateOf(settings: Settings, usage: Pair<Long, Int>?) = SettingsUiState(
        ThemeMode.of(settings.theme), usage?.first ?: 0L, usage?.second ?: 0, BuildConfig.VERSION_NAME, settings.paragraphTimes,
        usageKnown = usage != null,
    )

    val state: StateFlow<SettingsUiState> = combine(app.preferences.settings, usage, ::stateOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), stateOf(app.preferences.settings.value, null))

    /** The speech model's install state (Settings may show it; recording never waits for it). */
    val model: StateFlow<ModelStatus> = app.models.status

    fun setTheme(mode: ThemeMode) = app.preferences.theme(mode.key)

    fun setParagraphTimes(shown: Boolean) = app.preferences.paragraphTimes(shown)

    /**
     * "Remove all audio", confirmed: the audio of every note that can lose it now goes at once (their text stays). A
     * note whose audio is still needed (it is being recorded or transcribed, or a part of it failed and can be
     * retried) keeps it, as it would from its own menu; the storage row then shows what is left.
     */
    fun removeAllAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            val withAudio = try { store.library().filter { it.audioPresent } } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
            for (note in withAudio) {
                // Scheduled and committed in one go: the confirmation was the chance to keep it.
                if (UndoableActions.removeAudio(app, note.id) { }) UndoableActions.commitRemoveAudio(app, note.id) { }
            }
        }
    }

    /** Whether the page is on screen; while it is not, the last usage stays and nothing is re-read. */
    fun setVisible(shown: Boolean) { visible.value = shown }
}
