package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import app.focal.ui.design.FocalTiming
import app.focal.ui.shell.NoteHit
import app.focal.ui.state.ParagraphUi

/** What the note page is doing, for the focus rule (spec 1, 4.13). */
enum class NoteMode { Reading, Playing, Editing, Finding }

/**
 * The focus rule on the note page (spec 1, "the focus rule"): whatever is current is set in ink and everything else
 * recedes one step to `muted`. Reading: everything is ink. Playing: the playing paragraph. Editing: everything is
 * ink too (the caret says where the edit is; paragraphs that dimmed and lit as the caret moved between them read as
 * flicker). Finding: nothing; the matches carry the focus. Paragraphs read
 * these values through `derivedStateOf`, so a change redraws only the paragraphs whose focus changed; the playing
 * word is read in the draw phase of the playing paragraph only.
 */
@Stable
class NoteFocus {
    var mode by mutableStateOf(NoteMode.Reading)
    /** The paragraph holding the caret in Edit, or null before one is tapped. */
    var editingId by mutableStateOf<Long?>(null)
    /** The paragraph playback is in, and the word being spoken (only with exact word times). */
    var playingId by mutableStateOf<Long?>(null)
    var playingWord by mutableStateOf<Int?>(null)

    /** A search result's hit (spec 5.2): highlighted for 1.5 s on arrival, then fading out. */
    var hit by mutableStateOf<NoteHit?>(null)
    val hitAlpha = Animatable(0f)

    /** When the focus returns to every paragraph, each waits its turn from the bottom up (rack focus, 2.8.1). */
    private var rack: Map<Long, Long> = emptyMap()

    fun inks(id: Long): Boolean = when (mode) {
        NoteMode.Reading -> true
        NoteMode.Playing -> playingId == null || playingId == id
        NoteMode.Editing -> true
        NoteMode.Finding -> false
    }

    /** The title follows the body: it recedes in Find. */
    val titleInks: Boolean get() = mode != NoteMode.Finding

    /**
     * Every paragraph returns to ink: those on screen ([visible], top to bottom) are staggered 24 ms apart from the
     * bottom up (at most 12 of them); the rest snap.
     */
    fun rackFocus(visible: List<Long>) {
        val shown = visible.takeLast(RACK_MAX)
        rack = shown.withIndex().associate { (index, id) -> id to (shown.lastIndex - index) * FocalTiming.RACK_STAGGER_MS }
        mode = NoteMode.Reading
    }

    /** How long paragraph [id] waits before it racks back to ink; the delay is used once. */
    fun rackDelay(id: Long): Long = rack[id] ?: 0L

    private companion object {
        const val RACK_MAX = 12
    }
}

/**
 * The paragraphs' last text layouts, by id, for follow-scroll, the lens and the find band: kept while the width stays,
 * so a paragraph scrolled off screen still knows its lines. Not state: read in effects and callbacks.
 */
class ParagraphLayouts {
    private val layouts = HashMap<Long, TextLayoutResult>()
    fun put(id: Long, layout: TextLayoutResult) { layouts[id] = layout }
    operator fun get(id: Long): TextLayoutResult? = layouts[id]
}

/**
 * New text while a note transcribes (spec 2.8.1 develop): each stretch arrives once, keyed by its paragraph and the
 * start of its range, with the time it arrived. A paragraph seen for the first time arrives whole; an open paragraph
 * that grew arrives from its old end. The first state the page sees, and every change once the note is editable
 * (splits, merges, replaces), arrive settled.
 */
class Arrivals {
    data class Arrival(val start: Int, val atMs: Long)

    private val lengths = HashMap<Long, Int>()
    private val arrivals = HashMap<Long, Arrival>()
    private var seeded = false
    private var last: List<ParagraphUi>? = null

    /** The note's paragraphs now; called after every composition, it does nothing until they change. */
    fun observe(paragraphs: List<ParagraphUi>, transcribing: Boolean, nowMs: Long) {
        if (paragraphs === last) return
        last = paragraphs
        for (paragraph in paragraphs) {
            val id = paragraph.id
            val length = paragraph.text.length
            val before = lengths[id]
            if (seeded && transcribing) {
                if (before == null) arrivals[id] = Arrival(0, nowMs)
                else if (length > before) arrivals[id] = Arrival(before, nowMs)
            }
            lengths[id] = length
        }
        seeded = true
    }

    /** The paragraph's latest arrival, while it is still developing. */
    fun of(id: Long, nowMs: Long): Arrival? = arrivals[id]?.takeIf { nowMs - it.atMs < FocalTiming.SETTLED_AFTER_MS }
}
