package app.focal.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** The note page's keyboard shortcuts (spec 8.5). */
enum class PageKey { Find, Copy, Undo, Redo, PlayPause }

/**
 * Routes the page shortcuts the activity hears to the page on top. A Compose key modifier hears keys only while a
 * node inside it has focus, and nothing has focus while a note is read; the activity hears them whenever no focused
 * node took them first (Ctrl combinations in `onKeyShortcut`, Space in `onKeyDown`), as it does Ctrl+, (4.23.6).
 * Main thread only.
 */
@Stable
class PageKeys {
    private val handlers = ArrayList<(PageKey) -> Boolean>()

    /** True when a page took [key]. The page registered last is asked first. */
    fun dispatch(key: PageKey): Boolean = handlers.asReversed().any { it(key) }

    internal fun register(handler: (PageKey) -> Boolean) {
        handlers += handler
    }

    internal fun unregister(handler: (PageKey) -> Boolean) {
        handlers -= handler
    }
}

/** The activity's [PageKeys]; a default one (that nothing dispatches to) for screens hosted elsewhere. */
val LocalPageKeys = staticCompositionLocalOf { PageKeys() }

/** Answers [PageKeys] with [handler] while this composition is alive; [handler] says whether it took the key. */
@Composable
internal fun PageKeysEffect(handler: (PageKey) -> Boolean) {
    val keys = LocalPageKeys.current
    val current by rememberUpdatedState(handler)
    DisposableEffect(keys) {
        val registered: (PageKey) -> Boolean = { current(it) }
        keys.register(registered)
        onDispose { keys.unregister(registered) }
    }
}
