package app.focal.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

data class Settings(val language: String = "en", val theme: String = "light")
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val settings = MutableStateFlow(Settings(prefs.getString("language", "en")!!.takeIf { it in languages } ?: "en", prefs.getString("theme", "light")!!))
    fun language(value: String) { require(value in languages); prefs.edit { putString("language", value) }; settings.value = settings.value.copy(language = value) }
    fun theme(value: String) { prefs.edit { putString("theme", value) }; settings.value = settings.value.copy(theme = value) }
    companion object {
        val languages = linkedMapOf("en" to "English", "ru" to "Русский", "de" to "Deutsch", "fr" to "Français", "es" to "Español", "it" to "Italiano", "pt" to "Português")
        fun languageLabel(language: String, speechEngine: String): String =
            if (speechEngine == "ultra-v1") "Automatic" else languages[language] ?: language
    }
}
