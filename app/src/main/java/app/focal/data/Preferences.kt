package app.focal.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [theme] is "system", "light" or "dark". [paragraphTimes]: a note with audio shows, over each paragraph, the time it
 * starts at in the recording (a tap plays from there).
 */
data class Settings(val theme: String = "system", val paragraphTimes: Boolean = true)
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val settings = MutableStateFlow(Settings(prefs.getString("theme", "system")!!, prefs.getBoolean("paragraph_times", true)))
    fun theme(value: String) {
        require(value in THEMES) { "Unknown theme $value" }
        prefs.edit { putString("theme", value) }; settings.value = settings.value.copy(theme = value)
    }

    fun paragraphTimes(shown: Boolean) {
        prefs.edit { putBoolean("paragraph_times", shown) }; settings.value = settings.value.copy(paragraphTimes = shown)
    }

    /**
     * The paragraph rules the stored notes were last split by ([PARAGRAPH_RULES] once [app.focal.data.NoteStore.reparagraph]
     * has run for them): notes recorded under older rules are split once, at the first start after an update.
     */
    var paragraphRules: Int
        get() = prefs.getInt("paragraph_rules", 1)
        set(value) { prefs.edit { putInt("paragraph_rules", value) } }

    /**
     * The settings peek (design 4.23.5): whether settings has ever been opened, and how many peeks have played
     * (at most two, none after settings was opened). Local only, never reported anywhere.
     */
    var settingsOpened: Boolean
        get() = prefs.getBoolean("settings_opened", false)
        set(value) { prefs.edit { putBoolean("settings_opened", value) } }
    var settingsPeeks: Int
        get() = prefs.getInt("settings_peeks", 0)
        set(value) { prefs.edit { putInt("settings_peeks", value) } }

    /** Notifications are asked for once, after the first capture is confirmed (design 5.11). */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean("notifications_asked", false)
        set(value) { prefs.edit { putBoolean("notifications_asked", value) } }

    companion object {
        val THEMES = listOf("system", "light", "dark")
        /** The current paragraph rules (SpeechTuning's paragraph constants): 2 since 0.3.1. */
        const val PARAGRAPH_RULES = 2
    }
}
