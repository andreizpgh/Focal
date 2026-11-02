package app.focal.speech

object NativeWhisper {
    init { System.loadLibrary("focal") }
    /** Opaque handle, valid until close. Calls for one engine must not decode concurrently. */
    fun open(path: String, checkCancelled: () -> Unit = {}): Long {
        try { BaseModelValidator.validate(java.io.File(path), checkCancelled) }
        catch (e: IllegalArgumentException) { throw IllegalStateException(e.message, e) }
        catch (e: java.io.IOException) { throw IllegalStateException("Could not read the speech model.", e) }
        return openValidatedRaw(path)
    }
    private external fun openValidatedRaw(path: String): Long
    external fun decode(handle: Long, samples: FloatArray, language: String, threads: Int, context: String = ""): ByteArray
    /** Terminal cancellation. Partial results are rejected; create a new engine to retry. */
    external fun cancel(handle: Long)
    /** Safe to repeat and to race with decode/cancel; an active decode retains native ownership. */
    external fun close(handle: Long)
}
