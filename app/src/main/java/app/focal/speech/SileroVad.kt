package app.focal.speech

import app.focal.core.SpeechDetector
import app.focal.core.SpeechTuning
import java.io.File

/**
 * Silero VAD (v4 ONNX) speech decisions through the sherpa-onnx C API, with sherpa-onnx's
 * hysteresis and the threshold, minimum speech and minimum silence of Focal's segmentation
 * experiments ([SpeechTuning]). Each decision equals sherpa-onnx's `SileroVadModel::IsSpeech`
 * for that window; no audio is queued.
 *
 * Feed every consecutive [SpeechTuning.VAD_WINDOW_SAMPLES]-sample window of 16 kHz audio in
 * order, never skipping one, and [reset] before an unrelated stream. Calls are serialized.
 */
internal class SileroVad private constructor(private var handle: Long) : SpeechDetector, AutoCloseable {
    @Synchronized
    override fun isSpeech(window: FloatArray): Boolean {
        check(handle != 0L) { "The voice activity detector is closed." }
        require(window.size == SpeechTuning.VAD_WINDOW_SAMPLES) { "A window holds exactly ${SpeechTuning.VAD_WINDOW_SAMPLES} samples." }
        return SherpaBridge.vadAcceptWindow(handle, window)
    }

    /** Forgets the model and hysteresis state, as for a new detector. */
    @Synchronized
    fun reset() {
        check(handle != 0L) { "The voice activity detector is closed." }
        SherpaBridge.vadReset(handle)
    }

    @Synchronized
    override fun close() {
        val opened = handle
        if (opened != 0L) {
            handle = 0L
            SherpaBridge.destroyVad(opened)
        }
    }

    companion object {
        /** Opens the detector on a silero_vad.onnx file. Throws IllegalStateException on failure. */
        fun open(model: File): SileroVad {
            val handle = SherpaBridge.createVad(
                model = model.path,
                threshold = SpeechTuning.VAD_THRESHOLD,
                minSilenceSeconds = SpeechTuning.VAD_MIN_SILENCE_SAMPLES / SAMPLE_RATE,
                minSpeechSeconds = SpeechTuning.VAD_MIN_SPEECH_SAMPLES / SAMPLE_RATE,
                windowSize = SpeechTuning.VAD_WINDOW_SAMPLES,
                // The bridge empties the detector's segment buffer after every window, so the higher
                // threshold sherpa-onnx applies to long segments never engages; this is only a backstop.
                maxSpeechSeconds = 3_600f,
                numThreads = 1,
            )
            check(handle != 0L) { "The voice activity detector could not be created." }
            return SileroVad(handle)
        }

        private const val SAMPLE_RATE = 16_000f
    }
}
