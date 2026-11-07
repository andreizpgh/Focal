package app.focal.ui.shell

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import app.focal.ui.state.ThemeMode

/**
 * The app's theme setting applied where Android can see it, so the window background and the splash match the first
 * Compose frame on a cold start (no light flash in Dark, no dark flash in Light; spec 2.1). On API 31+ the system
 * keeps the app's night mode itself; below that the activity overrides its own configuration before its resources
 * are created. Compose resolves the theme from the setting directly ([isDark]).
 */
object NightMode {
    /** The system's own night mode, regardless of any app override. Re-read it on configuration changes. */
    fun systemDark(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isDark(mode: ThemeMode, systemDark: Boolean = systemDark()): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /**
     * API 31+: hands the setting to the system, which persists it and applies it to the next splash. The system
     * changes the configuration only when the value differs, so calling it again is cheap.
     */
    fun applyToSystem(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(UiModeManager::class.java) ?: return
        val night = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        manager.setApplicationNightMode(night)
    }

    /**
     * API 26-30: the configuration override an activity applies in `attachBaseContext`, so `values-night` resources
     * (the window background, the splash) follow the setting from its first frame. Null when nothing is overridden.
     */
    fun legacyOverride(mode: ThemeMode): Configuration? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || mode == ThemeMode.SYSTEM) return null
        return Configuration().apply {
            uiMode = if (mode == ThemeMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    }
}
