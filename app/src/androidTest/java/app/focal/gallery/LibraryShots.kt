package app.focal.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.focal.recording.SpeechAvailability
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FixedMeter
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalMenuDivider
import app.focal.ui.components.FocalMenuItem
import app.focal.ui.components.MenuSurface
import app.focal.ui.library.DatePatterns
import app.focal.ui.library.DayWords
import app.focal.ui.library.LibraryActions
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.LibraryHost
import app.focal.ui.library.LibraryModel
import app.focal.ui.library.LibraryPage
import app.focal.ui.library.STAGE_PILL_TAG
import app.focal.ui.library.SettingsLayerState
import app.focal.ui.library.StageUi
import app.focal.ui.library.rememberLibrarySearchState
import app.focal.ui.settings.LicensesPage
import app.focal.ui.settings.SettingsActions
import app.focal.ui.settings.SettingsPage
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.LibraryDay
import app.focal.ui.state.LiveParagraph
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteRow
import app.focal.ui.state.PauseReason
import app.focal.ui.state.SearchRow
import app.focal.ui.state.SearchUiState
import app.focal.ui.state.SettingsUiState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** The library stage's renders: every library state, search, the settings layer and its pages. */
object LibraryShots {
    val all: List<Shot> = buildList {
        addAll(themed("home") { Library(idleModel()) })
        addAll(themed("empty") { Library(LibraryModel(loaded = true)) })
        addAll(themed("recording") { Library(liveModel(paused = false)) })
        addAll(themed("paused") { Library(liveModel(paused = true)) })
        addAll(themed("storage-full") { Library(liveModel(paused = true, reason = PauseReason.STORAGE)) })
        add(Shot("recording-first-light", dark = false) { Library(liveModel(paused = false, text = false)) })
        addAll(themed("transcribing") { Library(workingModel(NotePhase.TRANSCRIBING)) })
        add(Shot("waiting-light", dark = false) { Library(workingModel(NotePhase.WAITING)) })
        addAll(themed("ready") { Library(readyModel(fresh = true)) })
        add(Shot("ready-moment-light", dark = false, settleMs = READY_AT_MS + MOMENT_AT_MS) { ReadyMoment() })
        add(Shot("copied-light", dark = false, settleMs = COPIED_AT_MS, click = STAGE_PILL_TAG) { Library(readyModel(fresh = true)) })
        add(Shot("no-speech-light", dark = false) { Library(noSpeechModel()) })
        addAll(themed("scrolled") { Library(idleModel(), scroll = true) })
        addAll(themed("search") { Library(idleModel(), search = searchResults()) })
        add(Shot("search-none-light", dark = false) { Library(idleModel(), search = SearchUiState("boyler", "boyler")) })
        addAll(themed("menu") { Library(idleModel(), menu = true) })
        addAll(themed("undo") { Library(idleModel(), feedback = true) })
        addAll(themed("settings") { Strip(1f) })
        addAll(themed("settings-swipe-50") { Strip(0.5f) })
        add(Shot("peek-light", dark = false) { Strip(32f / 420f) })
        add(Shot("peek-empty-light", dark = false) { Strip(32f / 420f, LibraryModel(loaded = true)) })
        add(Shot("licenses-light", dark = false) { LicensesPage(onBack = {}) })
        add(Shot("home-light-200", dark = false, fontScale = 2f) { Library(idleModel()) })
        add(Shot("recording-light-200", dark = false, fontScale = 2f) { Library(liveModel(paused = true)) })
        add(Shot("empty-light-200", dark = false, fontScale = 2f) { Library(LibraryModel(loaded = true)) })
        add(Shot("settings-light-200", dark = false, fontScale = 2f) { Strip(1f) })
    }
}

private const val READY_AT_MS = 400L
/** The reference render is +520 ms into the moment: the morph done, the shimmer mid-card. */
private const val MOMENT_AT_MS = 520L
private const val COPIED_AT_MS = 300L

internal val today: LocalDate = LocalDate.of(2026, 11, 6)
private val zone: ZoneId = ZoneId.systemDefault()
internal val dates = LibraryDates(
    Locale.UK, zone,
    DatePatterns("HH:mm", "EEE", "EEEE", "d MMM", "d MMM y", "d MMMM", "d MMMM y"),
    DayWords("Today", "Yesterday"),
)
internal val meter = FixedMeter(floatArrayOf(0.94f, 0.62f, 0.88f, 0.55f, 0.38f))

private fun dayOf(label: String): LocalDate = when (label) {
    "Today" -> today
    "Yesterday" -> today.minusDays(1)
    else -> today.minusDays(4)
}

private fun epoch(day: LocalDate, time: String) = LocalDateTime.of(day, LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

private fun durationMs(text: String): Long {
    val parts = text.split(" ")
    var total = 0L
    var i = 0
    while (i + 1 < parts.size) {
        val value = parts[i].toLong()
        total += when (parts[i + 1]) { "h" -> value * 3_600_000; "min" -> value * 60_000; else -> value * 1000 }
        i += 2
    }
    return total
}

internal fun rows(): List<NoteRow> = FakeNotes.mapIndexed { index, note ->
    NoteRow(
        id = "note-$index", title = note.title, titleIsCustom = true, created = epoch(dayOf(note.day), note.time),
        durationMs = durationMs(note.duration), preview = note.preview, phase = NotePhase.READY, progress = null,
        audioPresent = true, failedParts = 0, canRemoveAudio = true,
    )
}

internal fun days(rows: List<NoteRow>): List<LibraryDay> =
    rows.groupBy { dates.date(it.created).toEpochDay() }.map { (day, list) -> LibraryDay(day, list) }

internal fun idleModel(): LibraryModel {
    val all = rows()
    return LibraryModel(loaded = true, stage = StageUi.Note(all.first(), fresh = false), days = days(all.drop(1)))
}

internal fun liveModel(paused: Boolean, reason: PauseReason? = null, text: Boolean = true): LibraryModel {
    val all = rows()
    val paragraphs = if (!text) emptyList() else (if (paused) FakeLive else FakeScene).mapIndexed { i, t -> LiveParagraph(i.toLong(), t, open = false) }
    return LibraryModel(
        loaded = true,
        stage = StageUi.Live(if (paused) 768 else 256, paused, reason, SpeechAvailability.READY, paragraphs, hint = !text),
        days = days(all),
    )
}

internal val saturday = NoteRow(
    id = "saturday", title = "So the plan for Saturday is simple", titleIsCustom = false, created = epoch(today, "17:08"),
    durationMs = 4 * 60_000L,
    preview = "So the plan for Saturday is simple. We meet at the trailhead at eight, not half past, because the car park is full by nine. Anna brings the stove and the big pot.",
    phase = NotePhase.READY, progress = null, audioPresent = true, failedParts = 0, canRemoveAudio = true,
)

internal fun workingModel(phase: NotePhase): LibraryModel {
    val all = rows()
    val note = saturday.copy(phase = phase, progress = if (phase == NotePhase.TRANSCRIBING) 0.82f else null)
    return LibraryModel(loaded = true, stage = StageUi.Note(note, fresh = false), days = days(all))
}

internal fun readyModel(fresh: Boolean): LibraryModel =
    LibraryModel(loaded = true, stage = StageUi.Note(saturday, fresh = fresh), days = days(rows()))

private fun noSpeechModel(): LibraryModel = LibraryModel(
    loaded = true,
    stage = StageUi.Note(saturday.copy(title = "", preview = "", durationMs = 9_000), fresh = false),
    days = days(rows()),
)

internal fun searchResults(): SearchUiState {
    val all = rows()
    fun result(row: NoteRow, snippet: String): SearchRow {
        val hits = Regex("Friday").findAll(snippet).map { it.range }.toList()
        return SearchRow(row, paragraphId = 1, hitStart = 0, hitEnd = 6, snippet = snippet, snippetHits = hits)
    }
    val results = listOf(
        result(all[0], "…test it with five people and decide on Friday. Send the deck to Ines by Friday…"),
        result(all[4], "…the Friday boat brought oil, flour and the post, and nothing else for a week."),
        result(all[6], "…on Friday nights the crossing took twice as long, because of the cattle."),
    )
    return SearchUiState("Friday", "Friday", results)
}

/** The library page inside the real shell (the dock, the feedback pill, the strip), from fake state. */
@Composable
internal fun Library(
    model: LibraryModel,
    scroll: Boolean = false,
    search: SearchUiState? = null,
    menu: Boolean = false,
    feedback: Boolean = false,
    rename: String? = null,
) {
    val shell = rememberShellState()
    val list = rememberLazyListState()
    if (scroll) LaunchedEffect(list) { list.scrollToItem(2, 140) }
    if (feedback) LaunchedEffect(shell) { shell.bottom.feedback.show("Note deleted", FeedbackAction("Undo", FocalIcon.Undo)) }
    FocalShell(shell, destination = { LibraryDestination(model, list, search, menu, rename = rename) })
}

/** The Library destination (the page and the settings strip) inside whatever shell is current. */
@Composable
internal fun LibraryDestination(
    model: LibraryModel,
    list: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    search: SearchUiState? = null,
    menu: Boolean = false,
    actions: LibraryActions = remember { LibraryActions(copy = { true }) },
    rename: String? = null,
) {
    val shell = app.focal.ui.shell.LocalShell.current
    LibraryHost(
        strip = shell.settings, preferences = null, armed = { true }, hasNotes = !model.empty, capturing = model.live != null,
        blocked = false, layer = remember { SettingsLayerState() },
        library = {
            LibraryPage(
                model, search ?: SearchUiState(), meter, dates, today, actions, listState = list,
                searchState = rememberLibrarySearchState(open = search != null),
                query = rememberTextFieldState(search?.query.orEmpty()),
                initialRename = rename,
            )
            if (menu) RowMenu()
        },
        settings = { Settings() },
    )
}

/** The row menu at a long-press on the second row (a popup on a phone; drawn inline here). */
@Composable
private fun RowMenu() {
    Box(Modifier.fillMaxSize()) {
        MenuSurface(Modifier.menuAt(x = 120.dp, y = 420.dp)) {
            FocalMenuItem("Copy text", {})
            FocalMenuItem("Share", {})
            FocalMenuItem("Rename", {})
            FocalMenuDivider()
            FocalMenuItem("Remove audio", {}, trailing = "9 MB")
            FocalMenuItem("Delete", {})
        }
    }
}

@Composable
private fun Settings() {
    SettingsPage(
        SettingsUiState(audioBytes = 1_400_000_000L, notesWithAudio = 38, versionName = "1.0.0"),
        remember { SettingsActions() },
    )
}

/** The strip at [s]: the library and the settings page beside it. */
@Composable
internal fun Strip(s: Float, model: LibraryModel = idleModel()) {
    val shell = rememberShellState()
    LaunchedEffect(shell) { shell.settings.track(s) }
    val actions = remember { LibraryActions() }
    FocalShell(shell, destination = {
        LibraryHost(
            strip = shell.settings, preferences = null, armed = { true }, hasNotes = true, capturing = false, blocked = false,
            layer = remember { SettingsLayerState() },
            library = { LibraryPage(model, SearchUiState(), meter, dates, today, actions) },
            settings = { Settings() },
        )
    })
}

/** T8″: the stage's note turns Ready on screen; captured as the reference, +520 ms into the moment. */
@Composable
private fun ReadyMoment() {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(READY_AT_MS)
        ready = true
    }
    val note = if (ready) saturday.copy(id = "moment") else saturday.copy(id = "moment", phase = NotePhase.TRANSCRIBING, progress = 0.97f)
    Library(LibraryModel(loaded = true, stage = StageUi.Note(note, fresh = ready), days = days(rows())))
}
