package app.focal.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.BuildConfig
import app.focal.FocalApplication
import app.focal.R
import app.focal.data.NoteArchive
import app.focal.data.NoteState
import app.focal.data.Settings
import app.focal.speech.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Theme, data management (export, backup/restore, audio removal, delete all) and the version. Settings are read
 * synchronously on the first frame; usage is scanned once initially and then only while the page is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store
    private val visible = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-off status sentences for the Settings feedback pill. */
    val events: SharedFlow<String> = eventFlow.asSharedFlow()

    private data class Usage(val audioBytes: Long, val notesWithAudio: Int, val noteCount: Int)

    /** Bytes of kept audio, notes keeping it, and settled notes count. */
    private suspend fun scan(): Usage = try {
        app.storageReady.await()
        val notes = store.library()
        val settled = notes.count { it.state !in NoteState.ACTIVE }
        val withAudio = notes.filter { it.audioPresent }
        var bytes = 0L
        for (note in withAudio) {
            currentCoroutineContext().ensureActive()
            bytes += store.audioBytes(note.id)
        }
        Usage(bytes, withAudio.size, settled)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Usage(0L, 0, 0)
    }

    /** The last usage read: a restarted [usage] shows it until its new read is in, instead of nothing. */
    @Volatile private var lastUsage: Usage? = null

    /**
     * Null until the first read: the theme and the switch never wait for it. Read once at the start, then again for
     * each change of the store while the page is on screen (one read when both ask at once: the latest wins).
     */
    private val usage: Flow<Usage?> = merge(
        flowOf(Unit),
        visible.flatMapLatest { shown -> if (!shown) emptyFlow() else store.changes.map { } },
    ).mapLatest<Unit, Usage?> { scan() }
        .onEach { lastUsage = it }
        .flowOn(Dispatchers.IO)
        .onStart { emit(lastUsage) }

    private fun stateOf(settings: Settings, usage: Usage?, isBusy: Boolean) = SettingsUiState(
        theme = ThemeMode.of(settings.theme),
        audioBytes = usage?.audioBytes ?: 0L,
        notesWithAudio = usage?.notesWithAudio ?: 0,
        versionName = BuildConfig.VERSION_NAME,
        paragraphTimes = settings.paragraphTimes,
        usageKnown = usage != null,
        noteCount = usage?.noteCount ?: 0,
        busy = isBusy,
    )

    val state: StateFlow<SettingsUiState> = combine(app.preferences.settings, usage, busy, ::stateOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), stateOf(app.preferences.settings.value, null, false))

    /** The speech model's install state (Settings may show it; recording never waits for it). */
    val model: StateFlow<ModelStatus> = app.models.status

    fun setTheme(mode: ThemeMode) = app.preferences.theme(mode.key)

    fun setParagraphTimes(shown: Boolean) = app.preferences.paragraphTimes(shown)

    /** Exports every settled note as a Markdown ZIP to the user-chosen SAF [uri]. */
    fun exportMarkdown(uri: Uri) = runDataTask {
        try {
            app.storageReady.await()
            val out = app.contentResolver.openOutputStream(uri) ?: error("Could not open output stream.")
            val count = NoteArchive.exportMarkdownZip(store, out)
            eventFlow.tryEmit(app.resources.getQuantityString(R.plurals.feedback_exported, count, count))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            eventFlow.tryEmit(app.getString(R.string.feedback_export_failed))
        }
    }

    /** Saves an offline restorable backup ZIP (JSON + optional audio) to [uri]. */
    fun exportBackup(uri: Uri, includeAudio: Boolean) = runDataTask {
        try {
            app.storageReady.await()
            val out = app.contentResolver.openOutputStream(uri) ?: error("Could not open output stream.")
            val count = NoteArchive.exportBackupZip(store, out, includeAudio)
            eventFlow.tryEmit(app.resources.getQuantityString(R.plurals.feedback_backed_up, count, count))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            eventFlow.tryEmit(app.getString(R.string.feedback_backup_failed))
        }
    }

    /** Restores notes from a Focal backup ZIP at [uri] without overwriting existing notes. */
    fun restoreBackup(uri: Uri) = runDataTask {
        try {
            app.storageReady.await()
            val input = app.contentResolver.openInputStream(uri) ?: error("Could not open input stream.")
            val summary = input.use { NoteArchive.importBackupZip(store, it, app.cacheDir) }
            val message = when {
                summary.imported > 0 -> app.resources.getQuantityString(R.plurals.feedback_restored_notes, summary.imported, summary.imported)
                summary.skipped > 0 -> app.getString(R.string.feedback_restore_none)
                else -> app.getString(R.string.feedback_restore_empty)
            }
            eventFlow.tryEmit(message)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            eventFlow.tryEmit(app.getString(R.string.feedback_restore_failed))
        }
    }

    /**
     * "Remove all audio", confirmed: the audio of every note that can lose it now goes at once (their text stays). A
     * note whose audio is still needed keeps it; the row then shows what is left.
     */
    fun removeAllAudio() = runDataTask {
        val withAudio = try { store.library().filter { it.audioPresent } } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }
        var removed = 0
        for (note in withAudio) {
            if (UndoableActions.removeAudio(app, note.id) { }) {
                UndoableActions.commitRemoveAudio(app, note.id) { }
                removed++
            }
        }
        if (removed > 0) {
            eventFlow.tryEmit(app.resources.getQuantityString(R.plurals.feedback_audio_removed_many, removed, removed))
        }
    }

    /**
     * "Delete all notes", confirmed: permanently deletes every settled note and its saved audio. Active recordings
     * or transcriptions are left untouched.
     */
    fun deleteAllNotes() = runDataTask {
        val settled = try { store.library().filter { it.state !in NoteState.ACTIVE } } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }
        var deleted = 0
        for (note in settled) {
            if (UndoableActions.delete(app, note.id) { }) {
                UndoableActions.commitDelete(app, note.id)
                deleted++
            }
        }
        if (deleted > 0) {
            eventFlow.tryEmit(app.resources.getQuantityString(R.plurals.feedback_deleted_many, deleted, deleted))
        }
    }

    private fun runDataTask(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } finally { busy.value = false }
        }
    }

    /** Whether the page is on screen; while it is not, the last usage stays and nothing is re-read. */
    fun setVisible(shown: Boolean) { visible.value = shown }
}
