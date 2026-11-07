package app.focal.gallery

import app.focal.ui.library.LibraryModel
import app.focal.ui.settings.LICENSE_ENTRY_TAG
import app.focal.ui.settings.LicensesPage
import app.focal.ui.state.SearchUiState

/**
 * The finish stage's renders: the licences page in both themes and with the speech model's attribution open, and the
 * font-scale pass of spec 8.4 (1.3 and 2.0) over the screens the earlier stages rendered at 1.0 only, in Light and
 * Dark where the stages did not.
 */
object FinishShots {
    val all: List<Shot> = buildList {
        add(Shot("licenses-dark", dark = true) { LicensesPage(onBack = {}) })
        add(Shot("licenses-open-light", dark = false, click = "$LICENSE_ENTRY_TAG:0", await = LICENSE_TEXT) { LicensesPage(onBack = {}) })
        add(Shot("licenses-open-dark", dark = true, click = "$LICENSE_ENTRY_TAG:0", await = LICENSE_TEXT) { LicensesPage(onBack = {}) })
        add(Shot("licenses-light-200", dark = false, fontScale = LARGE) { LicensesPage(onBack = {}) })
        add(Shot("settings-dark-200", dark = true, fontScale = LARGE) { Strip(1f) })
        add(Shot("home-dark-200", dark = true, fontScale = LARGE) { Library(idleModel()) })
        add(Shot("empty-dark-200", dark = true, fontScale = LARGE) { Library(LibraryModel(loaded = true)) })
        add(Shot("search-light-200", dark = false, fontScale = LARGE) { Library(idleModel(), search = searchResults()) })
        add(Shot("search-none-light-200", dark = false, fontScale = LARGE) { Library(idleModel(), search = SearchUiState("boyler", "boyler")) })
        add(Shot("undo-light-200", dark = false, fontScale = LARGE) { Library(idleModel(), feedback = true) })
        add(Shot("rec-paused-light-200", dark = false, fontScale = LARGE) { SceneFrame(1f, paused()) })
        add(Shot("rec-live-dark-200", dark = true, fontScale = LARGE) { SceneFrame(1f, live()) })
        add(Shot("playing-light-200", dark = false, fontScale = LARGE) { Note(design(), playing = true) })
        add(Shot("note-dark-200", dark = true, fontScale = LARGE) { Note(design()) })
        add(Shot("home-light-130", dark = false, fontScale = MEDIUM) { Library(idleModel()) })
        add(Shot("settings-light-130", dark = false, fontScale = MEDIUM) { Strip(1f) })
        add(Shot("note-light-130", dark = false, fontScale = MEDIUM) { Note(design()) })
        add(Shot("rec-live-light-130", dark = false, fontScale = MEDIUM) { SceneFrame(1f, live()) })
    }

    private const val MEDIUM = 1.3f
    private const val LARGE = 2f
}

/** A phrase of the speech model's attribution, the first entry's text. */
private const val LICENSE_TEXT = "Moondream"
