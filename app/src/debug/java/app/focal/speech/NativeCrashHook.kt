package app.focal.speech

/** Debug builds only (device tests): a native crash in the speech bridge, as the runtime would have one. */
internal object NativeCrashHook {
    init {
        System.loadLibrary("focal_speech")
    }

    const val SEGFAULT = 0
    const val ABORT = 1

    /** Writes through a null pointer ([SEGFAULT], SIGSEGV) or aborts ([ABORT], SIGABRT). Never returns. */
    @JvmStatic external fun crash(kind: Int)
}
