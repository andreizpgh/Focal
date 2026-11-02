package app.focal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF20211F), onPrimary = Color(0xFFFAF9F6),
    background = Color(0xFFFAF9F6), onBackground = Color(0xFF20211F),
    surface = Color(0xFFFAF9F6), onSurface = Color(0xFF20211F),
    surfaceVariant = Color(0xFFEFEEE9), onSurfaceVariant = Color(0xFF6C6D66),
    outline = Color(0xFFCCCEC5), outlineVariant = Color(0xFFE4E4DD),
    secondaryContainer = Color(0xFFECE8DB), onSecondaryContainer = Color(0xFF494332),
    error = Color(0xFF9F3932), errorContainer = Color(0xFFF9E8E3), onErrorContainer = Color(0xFF78342E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFF0EFE8), onPrimary = Color(0xFF20211F),
    background = Color(0xFF181917), onBackground = Color(0xFFF0EFE8),
    surface = Color(0xFF181917), onSurface = Color(0xFFF0EFE8),
    surfaceVariant = Color(0xFF282A25), onSurfaceVariant = Color(0xFFABADA4),
    outline = Color(0xFF55584F), outlineVariant = Color(0xFF34372F),
    secondaryContainer = Color(0xFF39372C), onSecondaryContainer = Color(0xFFE6DFC5),
)
private val Type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 29.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)
@Composable fun FocalTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
