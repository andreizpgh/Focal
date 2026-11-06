package app.focal.ui.shell

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import app.focal.ui.design.FocalColors
import app.focal.ui.state.ThemeMode

/** How a Focal activity's window follows the app's theme setting (spec 2.1). */
object FocalWindow {
    /** API 26-28 keep a translucent scrim behind three-button navigation (spec 9); 29+ draw none. */
    private val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
    private val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

    /**
     * Edge-to-edge with transparent bars whose icons follow the app's setting (not the system's), the system's own
     * night mode for the next splash, and the window background for gaps before Compose draws.
     */
    fun apply(activity: ComponentActivity, mode: ThemeMode) {
        val dark = NightMode.isDark(mode)
        NightMode.applyToSystem(activity, mode)
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) activity.window.isNavigationBarContrastEnforced = false
        activity.window.setBackgroundDrawable((if (dark) FocalColors.Dark else FocalColors.Light).paper.toArgb().toDrawable())
    }
}
