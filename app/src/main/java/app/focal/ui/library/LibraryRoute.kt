package app.focal.ui.library

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
 * The library page's saved state: its list's place, search (open, its query, its results' place), an inline rename and
 * the settings page's place beside it. The app keeps it above the shell's layout ([rememberLibraryPageStates]): one
 * pane composes the library in the back stack's display, two panes in a pane of its own, and a rememberSaveable finds
 * a value again only at the place in the tree that saved it. State remembered inside the page would not survive a
 * turn between the two, and a turn back would find the value the other layout saved before (spec 5.14).
 */
@Stable
class LibraryPageStates internal constructor(
    val list: LazyListState,
    val searchList: LazyListState,
    val search: LibrarySearchState,
    val query: TextFieldState,
    val rename: LibraryRenameState,
    val settingsScroll: ScrollState,
)

/** [query] gives search's query to start with (the library view model's), read once. */
@Composable
fun rememberLibraryPageStates(query: () -> String = { "" }): LibraryPageStates {
    val list = rememberLazyListState()
    val searchList = rememberLazyListState()
    val search = rememberLibrarySearchState()
    val text = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(query()) }
    val rename = rememberLibraryRenameState()
    val settingsScroll = rememberScrollState()
    return remember(list, searchList, search, text, rename, settingsScroll) {
        LibraryPageStates(list, searchList, search, text, rename, settingsScroll)
    }
}

/**
 * The Library destination: the library page and the settings page beside it, from the activity's view models.
 * [record] runs Record's permission flow; [sheetOpen] (the rationale sheet) keeps the settings hint away; [states] is the
 * page's saved state.
 */
@Composable
fun LibraryRoute(record: () -> Unit, sheetOpen: Boolean, meter: WaveMeter, states: LibraryPageStates = rememberLibraryPageStates()) {
    val shell = LocalShell.current
    val context = LocalContext.current
    val application = context.applicationContext as FocalApplication
    val library: LibraryViewModel = viewModel()
    val recording: RecordingViewModel = viewModel()
    val libraryState by library.state.collectAsStateWithLifecycle()
    // The recording without its timer: the library is rebuilt when the session changes, not once a second. The time
    // reaches only the live stage's timer and its spoken description.
    val recordingState by recording.stateWithoutTime.collectAsStateWithLifecycle()
    val liveSeconds = recording.elapsedSeconds.collectAsStateWithLifecycle()
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
    val transfers by rememberUpdatedState(rememberTransferWords())
    val say: (String?) -> Unit = { message -> if (message != null) scope.launch { shell.bottom.feedback.show(message) } }

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
            copy = { row ->
                val result = copyNote(context, row, library.copyText(row.id))
                say(transfers.copy(result))
                result == Transfer.Done
            },
            copyText = { row ->
                scope.launch {
                    val result = copyNote(context, row, library.copyText(row.id))
                    if (result == Transfer.Done) {
                        haptics.copy()
                        if (confirmsCopyInApp) shell.bottom.feedback.show(textCopied)
                    } else say(transfers.copy(result))
                }
            },
            share = { row -> scope.launch { say(transfers.share(shareNote(context, row, library.copyText(row.id)))) } },
            rename = { row, title -> library.rename(row.id, title) },
            removeAudio = { row -> library.removeAudio(row.id) },
            delete = { row -> library.delete(row.id) },
            deleteAll = { rows -> library.deleteAll(rows.map { it.id }) },
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
        hasNotes = model.loaded && !model.empty,
        capturing = model.live != null,
        blocked = sheetOpen,
        layer = layer,
        library = {
            LibraryPage(
                model, search, meter, dates, today, actions, listState = states.list, searchState = states.search, query = states.query,
                stageFocus = stageFocus, liveSeconds = { liveSeconds.value }, rename = states.rename, searchList = states.searchList,
            )
        },
        settings = {
            SettingsRoute(
                onBack = { scope.launch { shell.settings.close(reduced) } },
                onLicenses = { shell.open(Destination.Licenses) },
                headingFocus = headingFocus,
                visible = { shell.settings.progress.value > 0f },
                scroll = states.settingsScroll,
            )
        },
    )
}

/** How a copy or a share went: [Empty] had nothing to send; [TooLong] was not sent (see [MAX_TRANSFER_CHARS]). */
enum class Transfer { Done, Empty, TooLong, Failed }

/**
 * The longest text handed to the clipboard or the share sheet in one go. Both cross a Binder transaction, which fails
 * (and, unhandled, crashes) near 1 MB with the text in UTF-16 beside the clip's other fields; the store budgets single
 * paragraphs, not whole notes.
 */
const val MAX_TRANSFER_CHARS = 200_000

/** What the feedback pill says when a copy or a share did not happen (null when it did, or there was nothing). */
class TransferWords(private val copyTooLong: String, private val copyFailed: String, private val shareTooLong: String, private val shareFailed: String) {
    fun copy(result: Transfer): String? = when (result) {
        Transfer.TooLong -> copyTooLong
        Transfer.Failed -> copyFailed
        Transfer.Done, Transfer.Empty -> null
    }

    fun share(result: Transfer): String? = when (result) {
        Transfer.TooLong -> shareTooLong
        Transfer.Failed -> shareFailed
        Transfer.Done, Transfer.Empty -> null
    }
}

@Composable
fun rememberTransferWords(): TransferWords {
    val copyTooLong = stringResource(R.string.error_copy_too_long)
    val copyFailed = stringResource(R.string.error_copy)
    val shareTooLong = stringResource(R.string.error_share_too_long)
    val shareFailed = stringResource(R.string.error_share)
    return remember(copyTooLong, copyFailed, shareTooLong, shareFailed) { TransferWords(copyTooLong, copyFailed, shareTooLong, shareFailed) }
}

/**
 * Copies a note (spec 5.5): its visible paragraphs joined by a blank line, labelled with its title, marked
 * sensitive on API 33+ so the system preview hides it. Clears the stage's fresh mark once the clipboard took it.
 */
fun copyNote(context: Context, row: NoteRow, text: String): Transfer = copyNoteText(context, row.id, row.title, text)

/** [copyNote] by id: the note page and the stage share it. */
fun copyNoteText(context: Context, noteId: String, title: String, text: String): Transfer {
    if (text.isBlank()) return Transfer.Empty
    if (text.length > MAX_TRANSFER_CHARS) return Transfer.TooLong
    return try {
        val clip = ClipData.newPlainText(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) ?: return Transfer.Failed
        FreshNotes.copied(noteId)
        Transfer.Done
    } catch (_: RuntimeException) {
        // A refusal (SecurityException, IllegalStateException) or a transaction the system would not take.
        Transfer.Failed
    }
}

/** Shares a note through the system's share sheet (spec 4.15). */
private fun shareNote(context: Context, row: NoteRow, text: String): Transfer = shareNoteText(context, row.title, text)

/** [shareNote] by title: the note page's menu shares the same way. */
fun shareNoteText(context: Context, title: String, text: String): Transfer {
    if (text.isBlank()) return Transfer.Empty
    if (text.length > MAX_TRANSFER_CHARS) return Transfer.TooLong
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .apply { if (title.isNotBlank()) putExtra(Intent.EXTRA_TITLE, title) }
    return try {
        context.startActivity(Intent.createChooser(send, null))
        Transfer.Done
    } catch (_: RuntimeException) {
        // No chooser (ActivityNotFoundException), or a transaction the system would not take.
        Transfer.Failed
    }
}
