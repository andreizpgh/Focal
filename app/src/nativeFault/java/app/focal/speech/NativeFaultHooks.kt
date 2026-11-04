package app.focal.speech

/** Included only by the explicit native-fault debug build. Never shipped in normal APKs. */
object NativeFaultHooks {
    init { System.loadLibrary("focal") }
    const val FAIL_ALLOCATION = 1
    const val PAUSE_ALLOCATION = 2

    external fun identity(): String
    /** Arm immediately before transcribe/decode, on that same worker thread. */
    external fun arm(mode: Int): Long
    /** Call in the worker's finally block, including validation failures before native inference. */
    external fun disarm()
    external fun awaitEntered(token: Long, timeoutMs: Long): Boolean
    external fun release(token: Long)
    /** Requires the armed decode to have finished and its engine to have closed. */
    external fun clear(token: Long)
    private external fun snapshotRaw(token: Long): LongArray

    data class Snapshot(
        val handle: Long,
        val encoderCompletions: Long,
        val oldDecoders: Long,
        val newDecoders: Long,
        val allocationEntries: Long,
        val refusals: Long,
        val entered: Boolean,
        val released: Boolean,
        val finished: Boolean,
        val destroyed: Long,
        val hasResult: Boolean,
        val resultCode: Long,
        val timedOut: Boolean,
        val invalidTarget: Boolean,
    )

    fun snapshot(token: Long): Snapshot {
        val values = snapshotRaw(token)
        check(values.size == 14) { "Native fault snapshot contract changed." }
        fun flag(index: Int): Boolean {
            check(values[index] in 0L..1L) { "Invalid native fault flag." }
            return values[index] == 1L
        }
        return Snapshot(values[0], values[1], values[2], values[3], values[4], values[5],
            flag(6), flag(7), flag(8), values[9], flag(10), values[11], flag(12), flag(13))
    }
}
