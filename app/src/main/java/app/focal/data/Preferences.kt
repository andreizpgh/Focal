package app.focal.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/** [theme] is "system", "light" or "dark". */
data class Settings(val theme: String = "system")
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val settings = MutableStateFlow(Settings(prefs.getString("theme", "system")!!))
    fun theme(value: String) {
        require(value in THEMES) { "Unknown theme $value" }
        prefs.edit { putString("theme", value) }; settings.value = settings.value.copy(theme = value)
    }

    /**
     * Recognizer loads that started but did not finish (they failed, or the process died during them).
     * Written synchronously, so a load that kills the process is still counted. Automatic loads stop at
     * [app.focal.recording.RecordingService.MAX_UNFINISHED_LOADS]; the user's Retry resets the count.
     */
    var unfinishedEngineLoads: Int
        get() = prefs.getInt("unfinished_engine_loads", 0)
        set(value) { prefs.edit(commit = true) { putInt("unfinished_engine_loads", value) } }

    companion object {
        val THEMES = listOf("system", "light", "dark")
        // Languages chosen for notes recorded before Ultra; Ultra notes detect the language.
        private val legacyLanguages = mapOf("en" to "English", "ru" to "Русский", "de" to "Deutsch", "fr" to "Français",
            "es" to "Español", "it" to "Italiano", "pt" to "Português")
        fun languageLabel(language: String, speechEngine: String): String =
            if (speechEngine == ULTRA_ENGINE) "Automatic" else legacyLanguages[language] ?: language
    }
}
