package app.focal.ui.shell

import kotlinx.coroutines.Job

/**
 * A predictive-back cancel's spring back, still to run or running. A back handler's own coroutine is cancelled with
 * the gesture, so the spring back runs in an outer scope; the next back stops it before it can undo what that back
 * does.
 */
internal class PendingJob {
    var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }
}
