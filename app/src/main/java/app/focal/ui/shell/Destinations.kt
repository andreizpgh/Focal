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
    data class Note(val id: String) : Destination
    data object Licenses : Destination

    companion object {
        private const val LIBRARY = "library"
        private const val LICENSES = "licenses"
        private const val NOTE = "note:"

        fun encode(destination: Destination): String = when (destination) {
            Library -> LIBRARY
            Licenses -> LICENSES
            is Note -> NOTE + destination.id
        }

        fun decode(value: String): Destination? = when {
            value == LIBRARY -> Library
            value == LICENSES -> Licenses
            value.startsWith(NOTE) -> Note(value.removePrefix(NOTE))
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
