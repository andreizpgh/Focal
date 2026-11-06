package app.focal.ui.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.FocalApplication
import app.focal.R
import app.focal.ui.components.WaveMeter
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.settings.SettingsRoute
import app.focal.ui.shell.Destination
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.NoteHit
import app.focal.ui.shell.PageMotion
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.NoteRow
import app.focal.ui.state.RecordingUiState
import app.focal.ui.state.RecordingViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The Library destination: the library page and the settings page beside it, from the activity's view models.
 * [record] runs Record's permission flow; [sheetOpen] (the rationale sheet) keeps the peek away.
 */
@Composable
fun LibraryRoute(record: () -> Unit, sheetOpen: Boolean, meter: WaveMeter) {
    val shell = LocalShell.current
    val context = LocalContext.current
    val application = context.applicationContext as FocalApplication
    val library: LibraryViewModel = viewModel()
    val recording: RecordingViewModel = viewModel()
    val libraryState by library.state.collectAsStateWithLifecycle()
    val recordingState by recording.state.collectAsStateWithLifecycle()
    val search by library.search.collectAsStateWithLifecycle()
    val fresh = FreshNotes.current
    // After Record the library keeps its layout until the scene covers it (spec 2.8.2 `liveLayoutSwitch`).
    val shown = if (shell.live.covered) recordingState else RecordingUiState()
    val model = remember(libraryState, shown, fresh) { LibraryModels.of(libraryState, shown, fresh) }
    val dates = rememberLibraryDates()
    val today = rememberToday()
    // Read when an action runs: the remembered actions below outlive a change of the setting.
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val haptics = rememberFocalHaptics()
    val scope = rememberCoroutineScope()
    val layer = remember { SettingsLayerState() }
    val headingFocus = remember { FocusRequester() }
    val stageFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val noteId by rememberUpdatedState(recordingState.noteId)
    val textCopied = stringResource(R.string.feedback_text_copied)

    val openSettings: () -> Unit = {
        layer.focusOnArrival = true
        scope.launch { shell.settings.open(reduced) }
    }
    // Focus hand-off (spec 4.23.6): to the "Settings" heading on arrival by the action or a key, back to the stage.
    LaunchedEffect(shell.settings) {
        snapshotFlow { shell.settings.progress.value }.distinctUntilChanged().collect { s ->
            if (s >= 1f && (layer.focusOnArrival || inputMode.inputMode == InputMode.Keyboard)) {
                runCatching { headingFocus.requestFocus() }
            }
            if (s <= 0f && layer.focusOnArrival) {
                layer.focusOnArrival = false
                runCatching { stageFocus.requestFocus() }
            }
        }
    }
    val actions = remember(library, recording) {
        LibraryActions(
            open = { row -> shell.open(Destination.Note(row.id), PageMotion.Row) },
            openStage = { row -> shell.open(Destination.Note(row.id), PageMotion.Stage) },
            openResult = { result ->
                val hit = result.paragraphId?.let { NoteHit(it, result.hitStart, result.hitEnd) }
                shell.open(Destination.Note(result.row.id, hit))
            },
            expand = { scope.launch { shell.live.expand(reduced) } },
            copy = { row -> copyNote(context, row, library.copyText(row.id)) },
            copyText = { row ->
                scope.launch {
                    if (copyNote(context, row, library.copyText(row.id))) {
                        haptics.copy()
                        if (confirmsCopyInApp) shell.bottom.feedback.show(textCopied)
                    }
                }
            },
            share = { row -> scope.launch { shareNote(context, row, library.copyText(row.id)) } },
            rename = { row, title -> library.rename(row.id, title) },
            removeAudio = { row -> library.removeAudio(row.id) },
            delete = { row -> library.delete(row.id) },
            audioSize = { row -> Formatter.formatShortFileSize(context, library.audioBytes(row.id)) },
            query = library::setQuery,
            record = record,
            pause = recording::pause,
            resume = recording::resume,
            finish = {
                val id = noteId
                recording.finish()
                haptics.finish()
                if (id != null) shell.open(Destination.Note(id), PageMotion.LiveStage)
            },
            openSettings = openSettings,
        )
    }
    LibraryHost(
        strip = shell.settings,
        preferences = application.preferences,
        armed = { shell.settings.armed },
        hasNotes = !model.empty,
        capturing = model.live != null,
        blocked = sheetOpen,
        layer = layer,
        library = { LibraryPage(model, search, meter, dates, today, actions, stageFocus = stageFocus) },
        settings = {
            SettingsRoute(
                onBack = { scope.launch { shell.settings.close(reduced) } },
                onLicenses = { shell.open(Destination.Licenses) },
                headingFocus = headingFocus,
                visible = { shell.settings.progress.value > 0f },
            )
        },
    )
}

/**
 * Copies a note (spec 5.5): its visible paragraphs joined by a blank line, labelled with its title, marked
 * sensitive on API 33+ so the system preview hides it. Clears the stage's fresh mark. True when the clipboard took it.
 */
fun copyNote(context: Context, row: NoteRow, text: String): Boolean = copyNoteText(context, row.id, row.title, text)

/** [copyNote] by id: the note page and the stage share it. */
fun copyNoteText(context: Context, noteId: String, title: String, text: String): Boolean {
    if (text.isBlank()) return false
    return try {
        val clip = ClipData.newPlainText(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) ?: return false
        FreshNotes.copied(noteId)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

/** Shares a note through the system's share sheet (spec 4.15). */
private fun shareNote(context: Context, row: NoteRow, text: String) = shareNoteText(context, row.title, text)

/** [shareNote] by title: the note page's menu shares the same way. */
fun shareNoteText(context: Context, title: String, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .apply { if (title.isNotBlank()) putExtra(Intent.EXTRA_TITLE, title) }
    try { context.startActivity(Intent.createChooser(send, null)) } catch (_: ActivityNotFoundException) { }
}
