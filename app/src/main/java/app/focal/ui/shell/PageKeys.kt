package app.focal.ui.shell

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

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

/**
 * The note page's shortcut keys (spec 8.5) as one focus area hears them before its focused control: Space alone, and
 * Ctrl+F, Ctrl+C, Ctrl+Z, Ctrl+Shift+Z. [take] says whether the page took the key; a key it declines goes on to the
 * focused control. A held Space acts once: its repeats go with the first press, so no control sees half of it.
 */
internal class ShortcutKeys(private val take: (PageKey) -> Boolean) {
    private var spaceTaken = false

    /** A key going down; true when the page took it (a repeat goes where its first press went). */
    fun down(keyCode: Int, repeatCount: Int, noModifiers: Boolean, ctrl: Boolean, shift: Boolean): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE && noModifiers) {
            if (repeatCount == 0) spaceTaken = take(PageKey.PlayPause)
            return spaceTaken
        }
        if (!ctrl) return false
        val key = when (keyCode) {
            KeyEvent.KEYCODE_Z -> if (shift) PageKey.Redo else PageKey.Undo
            KeyEvent.KEYCODE_F -> PageKey.Find
            KeyEvent.KEYCODE_C -> PageKey.Copy
            else -> return false
        }
        return take(key)
    }
}

/** Hears [keys] before the focused control inside this area, while [enabled]. */
internal fun Modifier.shortcutKeys(keys: ShortcutKeys, enabled: () -> Boolean = { true }): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || !enabled()) return@onPreviewKeyEvent false
    val native = event.nativeKeyEvent
    keys.down(native.keyCode, native.repeatCount, native.hasNoModifiers(), native.isCtrlPressed, native.isShiftPressed)
}

/** [ShortcutKeys] that always ask the latest [take]. */
@Composable
internal fun rememberShortcutKeys(take: (PageKey) -> Boolean): ShortcutKeys {
    val current by rememberUpdatedState(take)
    return remember { ShortcutKeys { current(it) } }
}

/**
 * The keys the focused controls of one area answer themselves (spec 8.5). A text field answers every key while it has
 * focus (a space in the query, Ctrl+C on its selection); the live stage answers Space (it expands, as Enter does) and
 * a stage holding a note Ctrl+C (it copies that note). The area's shortcuts leave those keys to the control and take
 * the rest, which a row or a button would otherwise press. Main thread only.
 */
class FocusedKeys {
    private val focused = HashMap<Any, () -> Set<PageKey>>()

    /** True when a control that has focus answers [key] itself. */
    fun owns(key: PageKey): Boolean = focused.values.any { key in it() }

    /** [take] for the keys no focused control answers; a key one does answer is declined, so it goes on to it. */
    fun leaving(take: (PageKey) -> Boolean): (PageKey) -> Boolean = { key -> !owns(key) && take(key) }

    /** [control] has focus or not; while it does, [keys] (read when a key arrives) are its own. */
    internal fun update(control: Any, hasFocus: Boolean, keys: () -> Set<PageKey>) {
        if (hasFocus) focused[control] = keys else focused -= control
    }

    companion object {
        /** What a text field answers: everything is typing. */
        val Typing: Set<PageKey> = PageKey.entries.toSet()
    }
}

/** The [FocusedKeys] of the area a control sits in; null where no area asks. */
val LocalFocusedKeys = staticCompositionLocalOf<FocusedKeys?> { null }

/**
 * Reports a control's focus to its area's [FocusedKeys], with the keys it answers itself while it has focus ([keys],
 * read when a key arrives; a text field's are [FocusedKeys.Typing]). The control counts as unfocused once it leaves.
 */
@Composable
fun rememberFocusReport(keys: () -> Set<PageKey> = { FocusedKeys.Typing }): (Boolean) -> Unit {
    val area = LocalFocusedKeys.current
    val control = remember { Any() }
    val current by rememberUpdatedState(keys)
    DisposableEffect(area) { onDispose { area?.update(control, false) { emptySet() } } }
    return remember(area) { { hasFocus -> area?.update(control, hasFocus) { current() } } }
}
