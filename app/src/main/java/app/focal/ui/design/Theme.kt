package app.focal.ui.design

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.focal.ui.components.PressPlate

/**
 * The design system's root. [dark] is the resolved theme (the app setting, or the system's when the setting is
 * System). Contrast and high-contrast text are read from the system here; reduced motion as well. A theme change
 * animates every colour role together on `fade` (snaps under reduced motion).
 */
@Composable
fun FocalTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val contrast = rememberContrast(context)
    val highContrastText = rememberHighContrastText(context)
    val reducedMotion = rememberReducedMotion()
    val colors = animatedColors(FocalColors.of(dark, contrast, highContrastText), reducedMotion)
    val selection = remember(colors.ink, colors.select) { TextSelectionColors(colors.ink, colors.select) }
    CompositionLocalProvider(
        LocalFocalColors provides colors,
        LocalReducedMotion provides reducedMotion,
        LocalTextSelectionColors provides selection,
    ) {
        MaterialTheme(colorScheme = colors.toColorScheme(), typography = FocalType.material, shapes = FocalShapes.material) {
            // After MaterialTheme, which provides its ripple: Focal presses are plates and scales, never ripples.
            CompositionLocalProvider(
                LocalIndication provides PressPlate,
                LocalContentColor provides colors.ink,
                content = content,
            )
        }
    }
}

/** Reads of the current design tokens inside composition. */
object FocalTheme {
    val colors: FocalColors
        @Composable @ReadOnlyComposable get() = LocalFocalColors.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** The two palettes a theme switch runs between; changed only by the switch's effect. */
private class ThemeSwitch(var from: FocalColors, var to: FocalColors)

@Composable
private fun animatedColors(target: FocalColors, reducedMotion: Boolean): FocalColors {
    val switch = remember { ThemeSwitch(target, target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == switch.to) return@LaunchedEffect
        // Restart from whatever is on screen now, so an interrupted switch never jumps.
        switch.from = FocalColors.lerp(switch.from, switch.to, progress.value)
        switch.to = target
        progress.snapTo(0f)
        if (reducedMotion) progress.snapTo(1f) else progress.animateTo(1f, FocalMotion.fade())
    }
    return FocalColors.lerp(switch.from, switch.to, progress.value)
}

/** `UiModeManager.getContrast()` on API 34+ (0 = standard), following changes while the app runs. */
@Composable
private fun rememberContrast(context: Context): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0f
    val manager = remember(context) { context.getSystemService(UiModeManager::class.java) } ?: return 0f
    var contrast by remember(manager) { mutableFloatStateOf(manager.contrast) }
    DisposableEffect(manager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { manager.removeContrastChangeListener(listener) }
    }
    return contrast
}

/** High-contrast text (API 36+): receding is switched off and all text stays in its ink. */
@Composable
private fun rememberHighContrastText(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) } ?: return false
    var enabled by remember(manager) { mutableStateOf(manager.isHighContrastTextEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.HighContrastTextStateChangeListener { enabled = it }
        manager.addHighContrastTextStateChangeListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { manager.removeHighContrastTextStateChangeListener(listener) }
    }
    return enabled
}
