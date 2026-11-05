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
