package app.focal.ui.note

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.R
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FeedbackResult
import app.focal.ui.components.FocalIcon
import app.focal.ui.library.confirmsCopyInApp
import app.focal.ui.library.copyNoteText
import app.focal.ui.library.rememberLibraryDates
import app.focal.ui.library.rememberToday
import app.focal.ui.library.shareNoteText
import app.focal.ui.shell.Destination
import app.focal.ui.shell.LocalShell
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.NoteEvent
import app.focal.ui.state.NoteViewModel
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlaybackSession
import app.focal.ui.state.PlaybackViewModel
import app.focal.ui.state.RecordingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Note destination: the note page from its view model, the activity's player and the recording's state. Deferred
 * deletes and audio removals run their feedback in [appScope], so their Undo or commit outlives the page.
 */
@Composable
fun NoteRoute(destination: Destination.Note, appScope: CoroutineScope, landing: Boolean) {
    val shell = LocalShell.current
    val context = LocalContext.current
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "The note page runs in a ComponentActivity." }
    val id = destination.id
    val note: NoteViewModel = viewModel(key = "note:$id", factory = NoteViewModel.factory(id))
    val player: PlaybackViewModel = viewModel(viewModelStoreOwner = activity)
    val recording: RecordingViewModel = viewModel(viewModelStoreOwner = activity)
    val library: LibraryViewModel = viewModel(viewModelStoreOwner = activity)
    val state by note.state.collectAsStateWithLifecycle()
    val session = player.session.collectAsStateWithLifecycle()
    val capture by recording.state.collectAsStateWithLifecycle()
    val captureElsewhere = capture.live && capture.noteId != id
    val playback = remember(player, id) { RoutePlayback(player, id, session) }
    val feedback = shell.bottom.feedback
    val current by rememberUpdatedState(state)

    val words = RouteWords(
        undo = stringResource(R.string.action_undo), deleted = stringResource(R.string.feedback_deleted),
        audioRemoved = stringResource(R.string.feedback_audio_removed), tooLate = stringResource(R.string.feedback_too_late),
        textCopied = stringResource(R.string.feedback_text_copied),
    )
    // Playback belongs to its page (spec 5.6): leaving it stops the note's audio, whichever way it leaves.
    val leave = {
        player.stopNote(id)
        if (shell.current == destination) shell.back()
    }
    DisposableEffect(player, id) {
        onDispose { if (!activity.isChangingConfigurations) player.stopNote(id) }
    }
    LaunchedEffect(note) {
        note.events.collect { event ->
            when (event) {
                NoteEvent.Deleted -> {
                    leave()
                    appScope.launch {
                        when (feedback.show(words.deleted, FeedbackAction(words.undo, FocalIcon.Undo))) {
                            FeedbackResult.ActionPerformed -> note.undoDelete()
                            FeedbackResult.Dismissed -> note.commitDelete()
                        }
                    }
                }
                NoteEvent.AudioRemoved -> appScope.launch {
                    when (feedback.show(words.audioRemoved, FeedbackAction(words.undo, FocalIcon.Undo))) {
                        FeedbackResult.ActionPerformed -> note.undoRemoveAudio()
                        FeedbackResult.Dismissed -> note.commitRemoveAudio()
                    }
                }
                NoteEvent.TooLateToUndo -> appScope.launch { feedback.show(words.tooLate) }
                // The page shows "Replaced N · Undo" itself: inside Edit its Undo belongs to the session's log.
                is NoteEvent.Replaced -> Unit
                is NoteEvent.Error -> appScope.launch { feedback.show(event.message) }
            }
        }
    }
    LaunchedEffect(player) { player.errors.collect { message -> appScope.launch { feedback.show(message) } } }
    // A note deleted elsewhere (or from here) leaves the screen; a read failure offers a reload.
    LaunchedEffect(state.missing) { if (state.missing) leave() }
    // "Note ready" is not posted for the note on screen (spec 5.12); playback pauses app-wide on stop (AppEffects).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, note) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> note.setShown(true)
                Lifecycle.Event.ON_STOP -> note.setShown(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            note.setShown(false)
        }
    }

    val actions = remember(note, player, library) {
        NoteActions(
            back = leave,
            rename = note::rename,
            copy = { copyNoteText(context, id, current.title, note.copyText()) },
            copyParagraph = { paragraph ->
                if (copyNoteText(context, id, current.title, paragraph.text) && confirmsCopyInApp) {
                    appScope.launch { feedback.show(words.textCopied) }
                }
            },
            share = { appScope.launch { shareNoteText(context, current.title, note.copyText()) } },
            removeAudio = {
                player.stopNote(id)
                note.removeAudio()
            },
            delete = {
                player.stopNote(id)
                note.delete()
            },
            retry = note::retry,
            audioSize = { Formatter.formatShortFileSize(context, library.audioBytes(id)) },
            find = note::find,
            replaceAll = note::replaceAll,
            undoReplace = note::undoReplace,
        )
    }
    val edits = remember(note) { ViewModelEdits(note) }
    val listState = rememberNoteListState(id, hit = destination.hit != null)
    NotePage(
        note = state,
        playback = playback,
        actions = actions,
        edits = edits,
        destination = destination,
        dates = rememberLibraryDates(),
        today = rememberToday(),
        captureElsewhere = captureElsewhere,
        landing = landing,
        listState = listState,
    )
}

/** The words the route's feedback uses, resolved in composition. */
private data class RouteWords(val undo: String, val deleted: String, val audioRemoved: String, val tooLate: String, val textCopied: String)

/** The activity's player for one note. [session] is the collected state, so the page observes it. */
@Stable
private class RoutePlayback(private val player: PlaybackViewModel, private val noteId: String, private val state: State<PlaybackSession>) :
    NotePlayback {
    override val session: PlaybackSession get() = state.value
    override fun positionMs(): Long = player.positionMs()
    override fun playWord(paragraph: ParagraphUi, offset: Int) = player.playWord(noteId, paragraph, offset)
    override fun playFrom(ms: Long) = player.playFrom(noteId, ms)
    override fun playRange(startMs: Long, endMs: Long) = player.playRange(noteId, startMs, endMs)
    override fun toggle() = player.toggle(noteId)
    override fun pause() = player.pause()
    override fun stop() = player.stopNote(noteId)
}

/** The edit session's store: the note's view model. */
private class ViewModelEdits(private val note: NoteViewModel) : EditBackend {
    override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome = note.editParagraph(id, revision, text)
    override suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome = note.split(id, revision, offset)
    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome =
        note.merge(firstId, firstRevision, secondId, secondRevision)
    override suspend fun restore(id: Long, revision: Long): EditOutcome = note.restoreOriginal(id, revision)
}

/**
 * The note's scroll position, kept per note id for the process (spec 5.5): returning to a note opens it where it was
 * read. A search hit scrolls to itself instead.
 */
@Composable
private fun rememberNoteListState(noteId: String, hit: Boolean): LazyListState {
    val list = remember(noteId) {
        val saved = if (hit) null else NoteScrollMemory.positions[noteId]
        LazyListState(saved?.first ?: 0, saved?.second ?: 0)
    }
    DisposableEffect(list, noteId) {
        onDispose { NoteScrollMemory.positions[noteId] = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
    }
    return list
}

/** Scroll positions by note id, for the life of the process. */
private object NoteScrollMemory {
    val positions = HashMap<String, Pair<Int, Int>>()
}
