package app.focal.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Every colour role of the design (spec 2.1, 2.2, 2.2.1). Paper roles follow the theme; the scene and stage roles
 * are the logo's material and look the same in both themes, except where the contrast setting lifts a receded grey.
 * There is no accent and no dynamic colour: two inks and the greys between them.
 */
@Immutable
data class FocalColors(
    val dark: Boolean,
    // Paper (2.1).
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val s1: Color,
    val s2: Color,
    val lens: Color,
    val select: Color,
    val inv: Color,
    val onInv: Color,
    val raised: Color,
    /** A 1 dp ring around menus in Dark; [Color.Transparent] in Light, where menus cast a shadow instead. */
    val raisedRing: Color,
    val scrim: Color,
    val press: Color,
    val centreBarTop: Color,
    val centreBarBottom: Color,
    /** The receded text step is switched off (high-contrast text): every text stays in its ink. */
    val receding: Boolean = true,
    // The recording scene (2.2): theme-independent.
    val sceneInk: Color = Scene.ink,
    val sceneHi: Color = Scene.hi,
    val sceneMuted: Color = Scene.muted,
    // The stage (2.2.1): theme-independent.
    val stageInk: Color = Scene.ink,
    val stageMuted: Color = Stage.muted,
    /** Dark theme only: separates the card's bottom stop from dark paper. */
    val stageRing: Color = Color.Transparent,
) {
    /** Receded text: [muted], or [ink] when high-contrast text switches receding off. */
    val recede: Color get() = if (receding) muted else ink
    val sceneRecede: Color get() = if (receding) sceneMuted else sceneInk
    val stageRecede: Color get() = if (receding) stageMuted else stageInk

    /** The Material roles mapped exactly (2.1): no baseline purple can leak through a default. */
    fun toColorScheme(): ColorScheme = ColorScheme(
        primary = ink, onPrimary = onInv, primaryContainer = s1, onPrimaryContainer = ink, inversePrimary = onInv,
        secondary = muted, onSecondary = paper, secondaryContainer = s1, onSecondaryContainer = ink,
        tertiary = muted, onTertiary = paper, tertiaryContainer = s1, onTertiaryContainer = ink,
        background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = s1, onSurfaceVariant = muted, surfaceTint = paper,
        inverseSurface = inv, inverseOnSurface = onInv,
        error = ink, onError = onInv, errorContainer = s1, onErrorContainer = ink,
        outline = faint, outlineVariant = line, scrim = Color.Black,
        surfaceBright = paper, surfaceDim = lens,
        surfaceContainer = s1, surfaceContainerHigh = s2, surfaceContainerHighest = lens,
        surfaceContainerLow = s1, surfaceContainerLowest = paper,
        primaryFixed = s1, primaryFixedDim = s1, onPrimaryFixed = ink, onPrimaryFixedVariant = muted,
        secondaryFixed = s2, secondaryFixedDim = s2, onSecondaryFixed = ink, onSecondaryFixedVariant = muted,
        tertiaryFixed = s1, tertiaryFixedDim = s1, onTertiaryFixed = ink, onTertiaryFixedVariant = muted,
    )

    companion object {
        val Light = FocalColors(
            dark = false,
            paper = Color(0xFFFFFFFF), ink = Color(0xFF111111), muted = Color(0xFF6B6B6B), faint = Color(0xFF949494),
            line = Color(0xFFEBEBEB), s1 = Color(0xFFF3F3F3), s2 = Color(0xFFE8E8E8), lens = Color(0xFFE0E0E0),
            select = Color(0xFFD6D6D6), inv = Color(0xFF111111), onInv = Color(0xFFFFFFFF), raised = Color(0xFFFFFFFF),
            raisedRing = Color.Transparent, scrim = Color.Black.copy(alpha = 0.32f), press = Color(0xFF111111).copy(alpha = 0.08f),
            centreBarTop = Color(0xFF111111), centreBarBottom = Color(0xFF3D3D3D),
        )
        val Dark = FocalColors(
            dark = true,
            paper = Color(0xFF0F0F0F), ink = Color(0xFFEDEDED), muted = Color(0xFF8F8F8F), faint = Color(0xFF6E6E6E),
            line = Color(0xFF222222), s1 = Color(0xFF1C1C1C), s2 = Color(0xFF282828), lens = Color(0xFF333333),
            select = Color(0xFF3D3D3D), inv = Color(0xFFEDEDED), onInv = Color(0xFF0F0F0F), raised = Color(0xFF222222),
            raisedRing = Color(0xFF2C2C2C), scrim = Color.Black.copy(alpha = 0.60f), press = Color(0xFFEDEDED).copy(alpha = 0.10f),
            centreBarTop = Color(0xFFFFFFFF), centreBarBottom = Color(0xFFCFCFCF),
            stageRing = Color.White.copy(alpha = 0.07f),
        )

        /**
         * The palette for a theme, with the system's contrast setting (API 34+, [contrast] >= 0.5 lifts every receded
         * grey one step) and high-contrast text (API 36+, receding off).
         */
        fun of(dark: Boolean, contrast: Float = 0f, highContrastText: Boolean = false): FocalColors {
            val base = if (dark) Dark else Light
            val lifted = if (contrast < CONTRAST_STEP) base else base.copy(
                muted = if (dark) Color(0xFFB0B0B0) else Color(0xFF4A4A4A),
                faint = base.muted,
                sceneMuted = Scene.mutedContrast,
                stageMuted = Stage.mutedContrast,
            )
            return if (highContrastText) lifted.copy(receding = false) else lifted
        }

        /** The theme switch animates every role together (`fade`). */
        fun lerp(from: FocalColors, to: FocalColors, t: Float): FocalColors = if (t <= 0f) from else if (t >= 1f) to else to.copy(
            paper = lerp(from.paper, to.paper, t), ink = lerp(from.ink, to.ink, t), muted = lerp(from.muted, to.muted, t),
            faint = lerp(from.faint, to.faint, t), line = lerp(from.line, to.line, t), s1 = lerp(from.s1, to.s1, t),
            s2 = lerp(from.s2, to.s2, t), lens = lerp(from.lens, to.lens, t), select = lerp(from.select, to.select, t),
            inv = lerp(from.inv, to.inv, t), onInv = lerp(from.onInv, to.onInv, t), raised = lerp(from.raised, to.raised, t),
            raisedRing = lerp(from.raisedRing, to.raisedRing, t), scrim = lerp(from.scrim, to.scrim, t),
            press = lerp(from.press, to.press, t), centreBarTop = lerp(from.centreBarTop, to.centreBarTop, t),
            centreBarBottom = lerp(from.centreBarBottom, to.centreBarBottom, t), stageRing = lerp(from.stageRing, to.stageRing, t),
        )

        /** `UiModeManager.getContrast()` at or above this is the "medium" or "high" contrast setting. */
        const val CONTRAST_STEP = 0.5f
    }
}

/** The recording scene's material and inks (2.2), taken from docs/mark.svg. The same in both themes. */
object Scene {
    val top = Color(0xFF262626)
    val mid = Color(0xFF171717)
    val bottom = Color(0xFF0A0A0A)
    /** Where [mid] sits on the vertical gradient, as a fraction of the drawn height. */
    const val MID_STOP = 0.46f
    /** The top highlight: white at this alpha in a radial gradient centred on the top edge. */
    const val HIGHLIGHT_ALPHA = 0.075f
    /** The highlight ellipse's radii, as fractions of the drawn width and height. */
    const val HIGHLIGHT_RADIUS_X = 1.30f
    const val HIGHLIGHT_RADIUS_Y = 0.46f
    /** The highlight fades linearly to 0 at this fraction of its radius. */
    const val HIGHLIGHT_END = 0.72f
    /** The grain: white at this alpha, a 256 px value-noise tile. */
    const val GRAIN_ALPHA = 0.07f

    val ink = Color(0xFFEDEDED)
    val hi = Color(0xFFF7F7F7)
    val muted = Color(0xFF8C8C8C)
    val mutedContrast = Color(0xFFA6A6A6)
    /** The arrival colour of developing text on the material (2.8.1): [ink] at 25 %. */
    val faint = Color(0xFFEDEDED).copy(alpha = 0.25f)
    val glass = Color.White.copy(alpha = 0.085f)
    /** A 1 dp inner top highlight on glass. */
    val glassHighlight = Color.White.copy(alpha = 0.10f)
    val actionTop = Color(0xFFFFFFFF)
    val actionBottom = Color(0xFFDCDCDC)
    val actionLabel = Color(0xFF111111)
    val actionRing = Color.White.copy(alpha = 0.06f)
    val actionShadow = Color.Black.copy(alpha = 0.35f)
    val chevron = Color(0xFFEDEDED).copy(alpha = 0.5f)
    val waveBar = Color(0xFFEDEDED)
    val waveCentreTop = Color(0xFFFFFFFF)
    val waveCentreBottom = Color(0xFFCFCFCF)
    val glow = Color.White.copy(alpha = 0.34f)
}

/** The stage: the material at card size (2.2.1). */
object Stage {
    val muted = Color(0xFF999999)
    val mutedContrast = Color(0xFFB0B0B0)
    val actionShadow = Color.Black.copy(alpha = 0.40f)
    val track = Color.White.copy(alpha = 0.22f)
    /** The pause badge: a [Scene.ink] disc, cut out of the bars by a ring in the material's colour. */
    val badgeRing = Color(0xFF121212).copy(alpha = 0.55f)
    val badgeGlyph = Color(0xFF161616)
    /** The readiness shimmer's peak alpha (white). */
    const val SHIMMER_PEAK = 0.10f
    const val SHIMMER_SHOULDER = 0.035f
}

val LocalFocalColors = staticCompositionLocalOf { FocalColors.Light }
