package app.focal.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.focal.R

/**
 * Inter 4.1, variable (wght 100-900, opsz 14-32), bundled as a subset (scripts/subset-inter.py). Variation settings
 * belong to a [Font], not to a [TextStyle], so the two optical sizes are two families (spec 2.3).
 */
private fun inter(weight: Int, opticalSize: Float) = Font(
    R.font.inter_var,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

private const val TEXT_OPSZ = 16f
private const val DISPLAY_OPSZ = 32f

/** Weights the text family carries: 420 is the live scene text; 500 run-ins and meta; 600 titles and labels. */
object FocalWeight {
    val Regular = FontWeight(400)
    val Scene = FontWeight(420)
    val Medium = FontWeight(500)
    val Semibold = FontWeight(600)
    /** Display only: the thin numerals of the timer and the stage's length. */
    val Thin = FontWeight(250)
}

val FocalText = FontFamily(listOf(400, 420, 500, 600).map { inter(it, TEXT_OPSZ) })
val FocalDisplay = FontFamily(listOf(250, 600).map { inter(it, DISPLAY_OPSZ) })

/** Tabular figures for numbers that change in place (timers, durations, counts, percentages). */
private const val TNUM = "tnum"

/** Like CSS: each line box is exactly the line height, with the leading split evenly above and below. */
private val CssLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    family: FontFamily,
    size: Int,
    line: Int,
    weight: FontWeight,
    trackingEm: Float,
    features: String? = null,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = trackingEm.em,
    fontFeatureSettings = features,
    fontSynthesis = FontSynthesis.None,
    lineHeightStyle = CssLines,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** The type scale (spec 2.3). Tracking follows Inter's dynamic metrics; the scene tokens are tuned by eye. */
object FocalType {
    val sceneTimer = style(FocalDisplay, 76, 84, FocalWeight.Thin, -0.035f, TNUM)
    val sceneText = style(FocalText, 20, 30, FocalWeight.Scene, -0.016f)
    val stageLength = style(FocalDisplay, 40, 48, FocalWeight.Thin, -0.02f, TNUM)
    val stageText = style(FocalText, 17, 24, FocalWeight.Regular, -0.0128f)
    val dateline = style(FocalText, 15, 20, FocalWeight.Medium, -0.0088f, TNUM)
    val title = style(FocalDisplay, 28, 34, FocalWeight.Semibold, -0.0209f)
    val headline = style(FocalDisplay, 22, 28, FocalWeight.Semibold, -0.0183f)
    val body = style(FocalText, 18, 28, FocalWeight.Regular, -0.0143f)
        .copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.None)
    val rowTitle = style(FocalText, 17, 24, FocalWeight.Semibold, -0.0128f)
    val field = style(FocalText, 17, 24, FocalWeight.Regular, -0.0128f)
    val label = style(FocalText, 16, 20, FocalWeight.Semibold, -0.0110f)
    val action = style(FocalText, 15, 20, FocalWeight.Semibold, -0.0088f)
    val secondary = style(FocalText, 15, 22, FocalWeight.Regular, -0.0088f)
    val meta = style(FocalText, 15, 20, FocalWeight.Regular, -0.0088f, TNUM)
    val section = style(FocalText, 14, 20, FocalWeight.Semibold, -0.0062f)
    val caption = style(FocalText, 13, 18, FocalWeight.Regular, -0.0032f, TNUM)

    /** The user's words set in [body], [stageText], [sceneText] and [secondary] (see [words]). */
    val bodyWords = body.words()
    val stageWords = stageText.words()
    val sceneWords = sceneText.words()
    val secondaryWords = secondary.words()

    /** Material components that are still used read these (no baseline Roboto anywhere). */
    val material = Typography(
        displayLarge = title, displayMedium = title, displaySmall = title,
        headlineLarge = title, headlineMedium = title, headlineSmall = headline,
        titleLarge = headline, titleMedium = rowTitle, titleSmall = section,
        bodyLarge = body, bodyMedium = secondary, bodySmall = caption,
        labelLarge = label, labelMedium = action, labelSmall = caption,
    )
}

/**
 * The user's words (the transcript, its live text, row previews, search snippets) take their direction from their
 * first strong letter, as a TextView's do: an Arabic or Hebrew note reads right to left, aligned to its own start, in
 * an English interface, and an English note left to right in a mirrored one. Chrome (labels, states, hints) keeps the
 * layout's direction, the default for a style (spec 8.5).
 */
fun TextStyle.words(): TextStyle = copy(textDirection = TextDirection.Content)

/** Tabular figures on a style whose numbers change in place ("tnum on numbers" in the scale). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TNUM)

/**
 * How much the font scale has grown this style's line (its line height in pixels over the same number of dp), with
 * Android's non-linear scaling: geometry measured in lines of a text (a mask over the top line) grows by it.
 */
fun Density.lineScale(style: TextStyle): Float = style.lineHeight.toPx() / style.lineHeight.value.dp.toPx()
