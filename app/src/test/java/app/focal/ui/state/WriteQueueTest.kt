package app.focal.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class WriteQueueTest {
    /**
     * A write that suspends outside any lock (a busy database) still holds back the next one: it starts only once the
     * one before it has ended, so it reads what that one left (the field an undo reseeded, the ids a split made).
     */
    @Test fun eachWriteStartsOnlyOnceTheOneBeforeItHasEnded() = runBlocking {
        val queue = WriteQueue(this)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        queue.launch {
            events += "first starts"
            gate.await()
            events += "first ends"
        }
        queue.launch { events += "second starts" }
        repeat(3) { yield() }
        assertEquals(listOf("first starts"), events)
        gate.complete(Unit)
        queue.settle()
        assertEquals(listOf("first starts", "first ends", "second starts"), events)
    }
}
