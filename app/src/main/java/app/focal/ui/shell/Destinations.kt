package app.focal.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * The pages of the app (spec 5): the library (with settings as its second page, not a destination), a note, and the
 * licences. Transcribing, Ready and Editing are states of a note; the recording scene is an overlay, not a page.
 */
@Immutable
sealed interface Destination {
    data object Library : Destination

    /** A note; opened from a search result, [hit] is where the note scrolls to and what it highlights (spec 5.2). */
    data class Note(val id: String, val hit: NoteHit? = null) : Destination
    data object Licenses : Destination

    companion object {
        private const val LIBRARY = "library"
        private const val LICENSES = "licenses"
        private const val NOTE = "note:"
        private const val NOTE_AT_HIT = "hit:"
        private const val HIT_FIELDS = 3

        fun encode(destination: Destination): String = when (destination) {
            Library -> LIBRARY
            Licenses -> LICENSES
            is Note -> destination.hit?.let { "$NOTE_AT_HIT${it.paragraphId},${it.start},${it.end}:${destination.id}" }
                ?: (NOTE + destination.id)
        }

        fun decode(value: String): Destination? = when {
            value == LIBRARY -> Library
            value == LICENSES -> Licenses
            value.startsWith(NOTE) -> Note(value.removePrefix(NOTE))
            value.startsWith(NOTE_AT_HIT) -> {
                val rest = value.removePrefix(NOTE_AT_HIT)
                val fields = rest.substringBefore(':').split(',').mapNotNull { it.toLongOrNull() }
                val id = rest.substringAfter(':', "")
                if (fields.size != HIT_FIELDS || id.isEmpty()) null
                else Note(id, NoteHit(fields[0], fields[1].toInt(), fields[2].toInt()))
            }
            else -> null
        }

        /** Keeps the back stack across configuration changes and process death, always rooted at the library. */
        val BackStackSaver: Saver<SnapshotStateList<Destination>, List<String>> = Saver(
            save = { stack -> stack.map(::encode) },
            restore = { saved ->
                val stack = saved.mapNotNull(::decode).filterIndexed { index, it -> index == 0 || it != Library }
                (if (stack.firstOrNull() == Library) stack else listOf(Library) + stack).toMutableStateList()
            },
        )
    }
}

/** A search hit inside a note: chars [start, end) of paragraph [paragraphId]'s text. */
@Immutable
data class NoteHit(val paragraphId: Long, val start: Int, val end: Int)

