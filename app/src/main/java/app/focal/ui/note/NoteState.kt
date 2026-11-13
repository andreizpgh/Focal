package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import app.focal.data.TextMatch
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlaybackSession
import app.focal.ui.state.WriteQueue

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
    /**
     * Edit's writes, in order, in the note's view model's scope: a sequence running when the page goes (an undo, then
     * the draft's last save) still finishes. Null: the page's own queue, which ends with it (the gallery, tests).
     */
    /** ● "Continue recording": more is recorded into this note (the microphone is asked for if need be). */
    val recordMore: () -> Unit = {},
    /** The note's own recording, while it runs on this page under a folded scene: the dock's Pause | Finish. */
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val finish: () -> Unit = {},
    val writes: WriteQueue? = null,
    /**
     * Edit's session, kept by the note's view model: a page recreated by rotation, or the note opened again beside the
     * library, goes on with what it showed and had not stored, its log and its field. Null: the page's own session,
     * which ends with it (the gallery, tests).
     */
    val session: EditSession? = null,
)

/** The paragraph holding the caret in Edit: its field's text and focus. */
@Stable
class FieldFocus(val id: Long, text: String, selection: TextRange) {
    constructor(id: Long, text: String, caret: Int) : this(id, text, TextRange(caret))

    val text = TextFieldState(text, TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length)))
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
    /**
     * Predictive back's progress on the player: the strip follows the gesture down. It stays where a committed
     * gesture left it until the strip is gone, and springs back (`press`) when the gesture is cancelled.
     */
    val stripBack = Animatable(0f)
    /** Where a gap's range playback ends: playback that reaches it ends the session. */
    var rangeEndMs: Long? = null
    /** Edit's ▶ started the playback now running: a short clip of the words at the caret, without strip or follow. */
    var listening by mutableStateOf(false)
        internal set

    /** The content title's bottom in the window, for the collapsed title in the bar. */
    var titleBottom by mutableFloatStateOf(Float.POSITIVE_INFINITY)

    /** The content title's top in the window, for its lines at the band's edge (read in effects, not observed). */
    internal var titleTop = Float.NaN

    /** The head's lines under the title (the meta's, the notices) in the window, likewise. */
    internal var headLines: List<Span> = emptyList()

    /** The search hit was scrolled to and marked: a recreated page keeps its reading position instead. */
    var hitShown by mutableStateOf(false)

    /** The running Edit session's log, read when the page's state is saved (set by the page). */
    internal var editLog: () -> EditLog? = { restoredLog }

    /** The id the focused field's paragraph is stored under, for the saved state (set by the page). */
    internal var storedId: (Long) -> Long = { it }

    /** The text the session shows for a paragraph, kept with the field in the saved state (set by the page). */
    internal var fieldShown: (Long) -> String? = { null }

    /**
     * The field is the Edit session's: false for a field restored with the page until the page has taken up the session
     * ([NotePage]), so no write stores it before it was checked against its paragraph.
     */
    internal var fieldBound = true

    /** What the restored field's paragraph showed when the state was saved ([KeptField.shown]). */
    internal var restoredShown: String? = null

    /** The log a recreated page's Edit session goes on with, once the note has loaded (then null). */
    internal var restoredLog: EditLog? = null

    internal companion object {
        /**
         * Keeps what the user was doing across recreation (rotation, a font or locale change, process death): Edit
         * with the focused paragraph's draft, its selection and what its paragraph showed, and the session's changes
         * and undo and redo log ([EditLog.encode] keeps its newest steps within a text budget), Find with its two
         * fields, a rename, the hit having been shown. The menus are not kept.
         */
        fun saver(takesFocus: Boolean): Saver<NotePageState, Any> = listSaver(
            save = { page ->
                val field = page.field
                val shown = if (page.fieldBound) field?.id?.let(page.fieldShown) else page.restoredShown
                listOf(
                    page.editing, field?.id?.let(page.storedId), field?.text?.text?.toString(), field?.text?.selection?.start, field?.text?.selection?.end,
                    page.find.open, page.find.fromEdit, page.find.query.text.toString(), page.find.replacement.text.toString(),
                    page.renaming, page.hitShown, page.editLog()?.encode(), shown,
                )
            },
            restore = { saved ->
                val id = saved[FIELD_ID] as Long?
                val field = id?.let {
                    FieldFocus(it, saved[FIELD_TEXT] as String, TextRange(saved[SELECTION_START] as Int, saved[SELECTION_END] as Int))
                }
                NotePageState(saved[EDITING] as Boolean, field, takesFocus).apply {
                    find.restore(saved[FIND_OPEN] as Boolean, saved[FIND_FROM_EDIT] as Boolean, saved[QUERY] as String, saved[REPLACEMENT] as String)
                    renaming = saved[RENAMING] as Boolean
                    hitShown = saved[HIT_SHOWN] as Boolean
                    restoredLog = EditLog.decode(saved.getOrNull(EDIT_LOG) as List<*>?)
                    restoredShown = saved.getOrNull(FIELD_SHOWN) as String?
                    fieldBound = field == null
                }
            },
        )

        private const val EDITING = 0
        private const val FIELD_ID = 1
        private const val FIELD_TEXT = 2
        private const val SELECTION_START = 3
        private const val SELECTION_END = 4
        private const val FIND_OPEN = 5
        private const val FIND_FROM_EDIT = 6
        private const val QUERY = 7
        private const val REPLACEMENT = 8
        private const val RENAMING = 9
        private const val HIT_SHOWN = 10
        private const val EDIT_LOG = 11
        private const val FIELD_SHOWN = 12
    }
}

/**
 * The page's state, kept across recreation. The gallery starts it in Edit with [field] holding the caret;
 * [takesFocus] false keeps the keyboard down there, so a render is the same on every run.
 */
@Composable
fun rememberNotePageState(editing: Boolean = false, field: FieldFocus? = null, takesFocus: Boolean = true): NotePageState =
    rememberSaveable(saver = NotePageState.saver(takesFocus)) { NotePageState(editing || field != null, field, takesFocus) }
