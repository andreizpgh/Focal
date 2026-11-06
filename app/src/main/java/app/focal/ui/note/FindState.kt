package app.focal.ui.note

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import app.focal.data.TextMatch

/**
 * Find and replace in a note (spec 5.8): the two fields, the matches of the query (folded like search, in reading
 * order, refreshed 120 ms after typing stops) and which one is current. [matchesIn] gives a paragraph its ranges, so
 * only the paragraphs whose matches change redraw.
 */
@Stable
class FindState {
    /** The rows are up (they stay while the find field still has focus to hand back). */
    var open by mutableStateOf(false)
        private set
    /** Find was opened from Edit: closing returns to Edit, the sheet back on the same paragraph. */
    var fromEdit by mutableStateOf(false)
        private set

    val query = TextFieldState()
    val replacement = TextFieldState()
    val queryFocus = FocusRequester()

    /** The matches of [matchedQuery]; they lag the query by the debounce. */
    var matches by mutableStateOf<List<TextMatch>>(emptyList())
        private set
    var matchedQuery by mutableStateOf("")
        private set
    var current by mutableIntStateOf(0)
        private set
    /** Bumped when the text changed under the matches (Replace all, its Undo): the query runs again. */
    var generation by mutableIntStateOf(0)
        private set

    /** The query has run and found nothing ("No matches"). */
    val none: Boolean by derivedStateOf { matchedQuery.isNotEmpty() && matches.isEmpty() }

    val currentMatch: TextMatch? get() = matches.getOrNull(current)

    private val byParagraph: Map<Long, List<IntRange>> by derivedStateOf {
        matches.groupBy({ it.paragraphId }, { it.start until it.end })
    }

    /** The match ranges inside paragraph [id]. */
    fun matchesIn(id: Long): List<IntRange> = byParagraph[id].orEmpty()

    fun open(fromEdit: Boolean) {
        this.fromEdit = fromEdit
        open = true
    }

    fun close() {
        open = false
        matches = emptyList()
        matchedQuery = ""
        current = 0
        query.clearText()
        replacement.clearText()
    }

    /** New matches for [query]; the current one is the first at or after [fromParagraph] (the reading position). */
    fun show(query: String, found: List<TextMatch>, fromParagraph: (TextMatch) -> Boolean) {
        val kept = currentMatch
        val again = query == matchedQuery
        matches = found
        matchedQuery = query
        current = when {
            found.isEmpty() -> 0
            // The same query again (after a replace): stay as close as possible to where the reader was.
            kept != null && again -> found.indexOfFirst { it.paragraphId == kept.paragraphId && it.start >= kept.start }
                .takeIf { it >= 0 } ?: 0
            else -> found.indexOfFirst(fromParagraph).takeIf { it >= 0 } ?: 0
        }
    }

    fun next() {
        if (matches.isNotEmpty()) current = (current + 1) % matches.size
    }

    fun previous() {
        if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size
    }

    /** The note's text changed under the matches. */
    fun refresh() {
        generation++
    }
}
