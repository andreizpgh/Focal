package app.focal.ui.note

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lightweight offline dictionary suggestions from Android's system spell-checker service.
 *
 * Only suggests replacements when the system dictionary explicitly marks [word] as misspelled / not in the
 * dictionary, so valid words are never cluttered with noise. Returns an empty list cleanly if no system
 * spell-checker is available on the device.
 */
internal object SpellSuggestions {
    private const val MAX_SUGGESTIONS = 2
    private const val TIMEOUT_MS = 350L

    suspend fun forWord(context: Context, word: String): List<String> {
        val clean = word.trim()
        if (clean.length < 2 || clean.any(Char::isWhitespace) || clean.none(Char::isLetter)) return emptyList()
        val manager = context.getSystemService(TextServicesManager::class.java) ?: return emptyList()
        val locale = detectLocale(clean)
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    var session: SpellCheckerSession? = null
                    fun finish(results: List<String>) {
                        runCatching { session?.close() }
                        session = null
                        if (continuation.isActive) continuation.resume(results)
                    }
                    val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                            val info = results?.firstOrNull()
                            finish(extract(clean, info))
                        }

                        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                            val info = results?.firstOrNull()?.let { sentence ->
                                if (sentence.suggestionsCount > 0) sentence.getSuggestionsInfoAt(0) else null
                            }
                            finish(extract(clean, info))
                        }
                    }
                    session = runCatching {
                        manager.newSpellCheckerSession(null, locale, listener, false)
                    }.getOrNull()
                    if (session == null) {
                        finish(emptyList())
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation {
                        runCatching { session?.close() }
                        session = null
                    }
                    runCatching {
                        session?.getSentenceSuggestions(arrayOf(TextInfo(clean)), MAX_SUGGESTIONS + 2)
                    }.onFailure { finish(emptyList()) }
                }
            }.orEmpty()
        }
    }

    private fun extract(original: String, info: SuggestionsInfo?): List<String> {
        if (info == null) return emptyList()
        val attrs = info.suggestionsAttributes
        val inDict = (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0
        val typo = (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
        if (inDict || !typo) return emptyList()
        val originalLower = original.lowercase(Locale.ROOT)
        val out = ArrayList<String>(MAX_SUGGESTIONS)
        val count = info.suggestionsCount
        for (i in 0 until count) {
            val candidate = info.getSuggestionAt(i)?.trim().orEmpty()
            if (candidate.isEmpty() || candidate.lowercase(Locale.ROOT) == originalLower) continue
            if (out.none { it.equals(candidate, ignoreCase = true) }) {
                out += candidate
                if (out.size >= MAX_SUGGESTIONS) break
            }
        }
        return out
    }

    private fun detectLocale(word: String): Locale {
        if (word.any { it in '\u0400'..'\u04FF' }) return Locale.forLanguageTag("ru")
        val default = Locale.getDefault()
        if (default.language.equals("ru", ignoreCase = true)) return Locale.ENGLISH
        return default
    }
}
