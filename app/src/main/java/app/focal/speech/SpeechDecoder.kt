package app.focal.speech

/** One opened recognizer. Create, use and close it on the same worker thread. */
internal interface SpeechDecoder : AutoCloseable {
    /** Called synchronously with one exact, unpadded window. Do not retain the samples. */
    fun decode(samples: FloatArray): String
}
