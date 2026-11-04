package app.focal.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0F0F0), onPrimaryContainer = Color(0xFF171717),
    background = Color.White, onBackground = Color(0xFF181818),
    surface = Color.White, onSurface = Color(0xFF181818),
    surfaceVariant = Color(0xFFF4F4F4), onSurfaceVariant = Color(0xFF6C6C6C),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainerHigh = Color(0xFFEDEDED),
    outline = Color(0xFF999999), outlineVariant = Color(0xFFEAEAEA),
    secondary = Color(0xFF666666), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0), onSecondaryContainer = Color(0xFF333333),
    tertiary = Color(0xFF666666),
    error = Color(0xFF9A3030), errorContainer = Color(0xFFF6ECEC), onErrorContainer = Color(0xFF752626),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFF1F1F1), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF292929), onPrimaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF111111), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF111111), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF222222), onSurfaceVariant = Color(0xFFA0A0A0),
    surfaceContainer = Color(0xFF222222), surfaceContainerLow = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    outline = Color(0xFF777777), outlineVariant = Color(0xFF2C2C2C),
    secondary = Color(0xFFAAAAAA), onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF252525), onSecondaryContainer = Color(0xFFDDDDDD),
    tertiary = Color(0xFFAAAAAA),
    error = Color(0xFFE5A5A5), errorContainer = Color(0xFF362626), onErrorContainer = Color(0xFFF0C5C5),
)
private val Type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.6).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.15).sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable fun FocalTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val activity = LocalActivity.current
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light, typography = Type,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(18.dp)),
        content = content,
    )
}
