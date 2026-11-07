package app.focal.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Edit's writes one at a time, in the order they were asked for (spec 5.7): autosave, Enter, Backspace, undo and
 * redo, Restore original, Replace all, Done and the save as the page stops. Each starts once the one before it has
 * finished, and reads what it stores (the field's text) then, never the text a step still running will replace.
 *
 * The note's view model holds one in its own scope, so a sequence that is running when the page goes (an undo, then
 * the draft's last save) still finishes, and a recreated page's writes wait for the ones its predecessor asked for.
 * [scope] must dispatch: a write asked for from inside a text field's input handling starts after it, never in it.
 * Used on the main thread.
 */
class WriteQueue(private val scope: CoroutineScope) {
    private var last: Job? = null

    /** Runs [block] in the queue's scope once every write asked for before it has finished. */
    fun launch(block: suspend () -> Unit): Job {
        val previous = last
        return scope.launch {
            previous?.join()
            block()
        }.also { last = it }
    }

    /** Waits for every write asked for so far. */
    suspend fun settle() {
        last?.join()
    }
}
