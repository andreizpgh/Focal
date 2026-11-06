package app.focal.ui.note

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import app.focal.data.TextMatch
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlaybackSession

/** Playback as the note page uses it: the activity's player, or a fake in the gallery. */
@Stable
interface NotePlayback {
    /** Changes only on start, pause, stop and note change. */
    val session: PlaybackSession
    /** Read in frame and draw callbacks only. */
    fun positionMs(): Long
    fun playWord(paragraph: ParagraphUi, offset: Int)
    fun playFrom(ms: Long)
    fun playRange(startMs: Long, endMs: Long)
    fun toggle()
    fun pause()
    fun stop()
}

/** What the note page does beyond playback and editing; the route provides it from the view models. */
@Stable
class NoteActions(
    val back: () -> Unit = {},
    val rename: (String) -> Unit = {},
    /** Copy: puts the note on the clipboard and returns whether it took it; the dock then confirms. */
    val copy: suspend () -> Boolean = { false },
    val copyParagraph: (ParagraphUi) -> Unit = {},
    val share: () -> Unit = {},
    val removeAudio: () -> Unit = {},
    val delete: () -> Unit = {},
    /** "Retry" and "Try again". */
    val retry: () -> Unit = {},
    /** The size the note's audio takes, for "Remove audio · 9 MB". */
    val audioSize: suspend () -> String? = { null },
    val find: suspend (String) -> List<TextMatch> = { emptyList() },
    val replaceAll: suspend (query: String, replacement: String) -> EditOutcome = { _, _ -> EditOutcome.NotEditable },
    val undoReplace: suspend () -> EditOutcome = { EditOutcome.Stale },
)

/** The paragraph holding the caret in Edit: its field's text and focus. */
@Stable
class FieldFocus(val id: Long, text: String, caret: Int) {
    val text = TextFieldState(text, TextRange(caret.coerceIn(0, text.length)))
    val requester = FocusRequester()
}

/**
 * The note page's own state: the mode (Edit, Find, an inline rename, the menus), the focus rule, the field in Edit and
 * the player's visibility. Kept per page; the gallery starts it in any state.
 */
@Stable
class NotePageState internal constructor(editing: Boolean, field: FieldFocus?, internal val takesFocus: Boolean) {
    val focus = NoteFocus().apply { editingId = field?.id }
    val find = FindState()
    val layouts = ParagraphLayouts()
    val arrivals = Arrivals()

    var editing by mutableStateOf(editing)
        internal set
    var field by mutableStateOf(field)
        internal set
    var renaming by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var editMenu by mutableStateOf(false)

    /** The player was put away for Edit or Find: ▶ brings it back where it paused. */
    var playerDismissed by mutableStateOf(false)
    /** A manual scroll suspended follow; "Now playing" or the next word tap resumes it. */
    var followSuspended by mutableStateOf(false)
    /** The playing line is above (true) or below (false) the reading band while follow is suspended; null: in view. */
    var playingOutside by mutableStateOf<Boolean?>(null)
    /** The strip is in the bottom column (it stays while it leaves). */
    var stripShown by mutableStateOf(false)
    /** Predictive back's progress on the player: the strip follows the gesture down. */
    var stripBack by mutableFloatStateOf(0f)
    /** Where a gap's range playback ends: playback that reaches it ends the session. */
    var rangeEndMs: Long? = null

    /** The content title's bottom in the window, for the collapsed title in the bar. */
    var titleBottom by mutableFloatStateOf(Float.POSITIVE_INFINITY)
}

/**
 * The page's state. The gallery starts it in Edit with [field] holding the caret; [takesFocus] false keeps the
 * keyboard down there, so a render is the same on every run.
 */
@Composable
fun rememberNotePageState(editing: Boolean = false, field: FieldFocus? = null, takesFocus: Boolean = true): NotePageState =
    remember { NotePageState(editing || field != null, field, takesFocus) }
