package app.focal.core

/**
 * Every tuning constant of the speech pipeline, in one place.
 *
 * The values come from the host experiments on read speech (FLEURS, 7 languages) and their audit.
 * Constants marked provisional are not validated for natural dictation: re-check them on the owner's
 * recordings before relying on them. Times are milliseconds unless the name says otherwise; PCM is
 * 16 kHz mono 16-bit.
 */
object SpeechTuning {
    const val SAMPLE_RATE = 16_000
    const val SAMPLES_PER_MS = SAMPLE_RATE / 1000

    // Segmenter: a window is cut at the first pause once it holds MIN audio, and hard-cut by MAX.
    const val MIN_WINDOW_MS = 6_000L
    const val MAX_WINDOW_MS = 20_000L
    /** A non-speech run this long is a pause; the cut lands half of it into the pause. */
    const val PAUSE_MS = 500L
    /** Hard cuts only: each neighbour reaches half of this across the cut. */
    const val HARD_CUT_OVERLAP_MS = 1_000L
    /** Shortest quiet run that places a hard cut; below it the 5-frame energy minimum does. */
    const val HARD_CUT_MIN_RUN_MS = 60L
    /** Audio decoded after a pause cut; words whose midpoint falls in it belong to the next window. */
    const val RIGHT_CONTEXT_MS = 1_000L
    /** Pure-pause audio decoded before a pause-cut window, without a keep filter. */
    const val PAUSE_LEAD_IN_MS = 250L
    /** After process death the detector is re-warmed on this much audio before the open window. */
    const val RECOVERY_WARM_UP_MS = 2_000L

    // Silero VAD (sherpa-onnx v2.0.1 defaults as used in the experiments).
    const val VAD_WINDOW_SAMPLES = 512
    const val VAD_THRESHOLD = 0.5f
    const val VAD_MIN_SPEECH_SAMPLES = 1_600
    const val VAD_MIN_SILENCE_SAMPLES = 800

    // Window gate: windows that are not worth decoding, and results that are not shown.
    /** A window owning less audio than this is recorded as silent without decoding. */
    const val MIN_DECODE_MS = 200L
    /** A window whose peak sample is below this level is recorded as silent without decoding. */
    const val SILENCE_PEAK_DBFS = -60.0
    /** A decode of a window without any detected speech is stored but hidden up to this many words. */
    const val HALLUCINATION_MAX_WORDS = 2

    // Words and window merge.
    const val MIN_WORD_END_MS = 40L
    /** Overlapped seams: the later copy of a repeated word starting this close is dropped. */
    const val DEDUPE_MS = 480L

    // Formatting. The measured pause is next start - previous end from TDT token times, which runs
    // about 0.3-0.5 s longer than the acoustic gap, so these are in "measured pause" units.
    /** Seam repair only acts on seams with a shorter pause than this. */
    const val SEAM_PAUSE_MS = 800L
    /** Provisional: pause after a sentence end that starts a paragraph (with MIN sentences). */
    const val PARAGRAPH_PAUSE_MS = 2_000L
    /** Provisional: pause after a sentence end that always starts a paragraph. */
    const val LONG_PAUSE_MS = 3_500L
    /** Provisional. */
    const val PARAGRAPH_MIN_SENTENCES = 2
    /** Provisional: a paragraph closes at the first sentence end once it holds this many words. */
    const val PARAGRAPH_MAX_WORDS = 80
    const val TITLE_MAX_CHARS = 48
    /** A first sentence shorter than this is extended by the next one for the title. */
    const val TITLE_MIN_CHARS = 12

    // Doubtful words (provisional GO): min content-token probability below the threshold.
    const val DOUBT_ENABLED = true
    const val DOUBT_THRESHOLD = 0.5
}
