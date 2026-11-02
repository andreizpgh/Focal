package app.focal.speech

object NativeWhisper {
    init { System.loadLibrary("focal") }
    external fun open(path: String): Long
    external fun decode(handle: Long, samples: FloatArray, language: String, threads: Int): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
