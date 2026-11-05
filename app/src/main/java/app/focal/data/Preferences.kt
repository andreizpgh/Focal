package app.focal.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

data class Settings(val theme: String = "light")
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val settings = MutableStateFlow(Settings(prefs.getString("theme", "light")!!))
    fun theme(value: String) { prefs.edit { putString("theme", value) }; settings.value = settings.value.copy(theme = value) }
    companion object {
        // Languages chosen for notes recorded before Ultra; Ultra notes detect the language.
        private val legacyLanguages = mapOf("en" to "English", "ru" to "Русский", "de" to "Deutsch", "fr" to "Français",
            "es" to "Español", "it" to "Italiano", "pt" to "Português")
        fun languageLabel(language: String, speechEngine: String): String =
            if (speechEngine == ULTRA_ENGINE) "Automatic" else legacyLanguages[language] ?: language
    }
}
