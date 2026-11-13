package app.focal.ui.components

import android.graphics.Paint
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Menu geometry (spec 4.15). */
object MenuMetrics {
    val width = 232.dp
    val maxWidth = 280.dp
    val verticalPadding = FocalGrid.x8
    val item = FocalGrid.x48
    val itemPadding = FocalGrid.x16
    val dividerMargin = FocalGrid.x8
    /** A label that wraps keeps this much room above and below it inside its item. */
    val labelPadding = FocalGrid.x8
    /** From the overflow button the menu's top sits this far below the button's bottom (ref y 100 under 96). */
    val belowAnchor = FocalGrid.x4
    /** Clamped this far from the window edges. */
    val edge = FocalGrid.x16
    /** Room for the Light shadows inside the popup window, which clips its content. */
    val shadowRoom = FocalGrid.x24
    /** Light: 0 1 2 at 8 % and 0 8 24 at 14 % (CSS blurs, so sigma = blur / 2). */
    val nearShadowY = 1.dp
    val nearShadowSigma = 1.dp
    val farShadowY = FocalGrid.x8
    val farShadowSigma = FocalGrid.x12
    const val NEAR_SHADOW_ALPHA = 0.08f
    const val FAR_SHADOW_ALPHA = 0.14f
    const val ENTER_SCALE = 0.95f
    val ring = 1.dp
}

/**
 * The dropdown menu (spec 4.15): `raised`, radius 16, 232 dp wide (up to 280 for long labels), 48 dp items. Light
 * casts two soft shadows; Dark has a 1 dp `raisedRing` instead. It scales in from the anchor corner (0.95 to 1 on
 * `press`, alpha on `fade`) and leaves on alpha (`fadeFast`). A tap outside, Back and Esc dismiss it. Place it inside
 * the overflow button it opens from: its top-end corner sits just under the button's bottom-end corner.
 */
@Composable
fun FocalMenu(expanded: Boolean, onDismiss: () -> Unit, above: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var composed by remember { mutableStateOf(expanded) }
    // The popup is a window of its own, drawn a frame or two after it is asked for: the menu arrives from its first
    // drawn frame, never from the tap, so its arrival is seen whole instead of cut to its end.
    var drawn by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(MenuMetrics.ENTER_SCALE) }
    LaunchedEffect(expanded) {
        if (expanded) {
            composed = true
            scale.snapTo(MenuMetrics.ENTER_SCALE)
            alpha.snapTo(0f)
            snapshotFlow { drawn }.first { it }
            coroutineScope {
                launch { scale.animateTo(1f, FocalMotion.press()) }
                alpha.animateTo(1f, FocalMotion.fade())
            }
        } else if (composed) {
            alpha.animateTo(0f, FocalMotion.fadeFast())
            composed = false
            drawn = false
        }
    }
    if (!composed) return
    val density = LocalDensity.current
    val position = remember(density, above) { MenuPosition(density, above) }
    // Read in the app's window: inside the popup the window is the popup's own.
    val maxWidth = menuMaxWidth()
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .drawBehind { if (!drawn) drawn = true }
                // The layer holds the shadows' room too: a layer drawn at less than full alpha is clipped to its
                // bounds, and one around the surface alone cut the shadows off until the fade had ended.
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                    val room = MenuMetrics.shadowRoom.toPx()
                    val origin = position.origin
                    transformOrigin = TransformOrigin(
                        if (origin.pivotFractionX > 0.5f) 1 - room / size.width else room / size.width,
                        if (origin.pivotFractionY > 0.5f) 1 - room / size.height else room / size.height,
                    )
                }
                .padding(MenuMetrics.shadowRoom),
        ) {
            // While it fades out it takes nothing: a second tap on "Delete" must not ask for it twice.
            MenuSurface(Modifier.untouchable(!expanded), maxWidth = maxWidth, content = content)
        }
    }
}

/**
 * How wide a menu may grow (spec 4.15): 280 dp; at larger font sizes up to the window's 16 dp edges, so an item's
 * label and its value stay on one line where they can, and wrap where even that is too narrow (8.4). Never wider than
 * the window allows.
 */
@Composable
fun menuMaxWidth(): Dp {
    val density = LocalDensity.current
    val available = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } - MenuMetrics.edge * 2
    return if (density.fontScale > 1f) available else minOf(MenuMetrics.maxWidth, available)
}

/** The menu's container: `raised`, radius 16, Light shadows or the Dark ring, 8 dp vertical padding. */
@Composable
fun MenuSurface(modifier: Modifier = Modifier, maxWidth: Dp = menuMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    val colors = FocalTheme.colors
    Column(
        modifier
            .menuElevation(colors.dark)
            .background(colors.raised, FocalShapes.menu)
            .then(if (colors.dark) Modifier.border(MenuMetrics.ring, colors.raisedRing, FocalShapes.menu) else Modifier)
            .widthIn(min = minOf(MenuMetrics.width, maxWidth), max = maxWidth)
            .width(IntrinsicSize.Max)
            .padding(vertical = MenuMetrics.verticalPadding),
        content = content,
    )
}

/**
 * A menu item: `label` at 400 in ink, an optional trailing value in `meta` `muted` ("9 MB"). Disabled (Edit's "Restore
 * original" while the paragraph is unedited) it is `faint` and not clickable. 48 dp tall; a wrapped label grows it.
 */
@Composable
fun FocalMenuItem(text: String, onClick: () -> Unit, trailing: String? = null, enabled: Boolean = true) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MenuMetrics.item)
            .focusRing(RectangleShape)
            .clickable(source, indication = PressPlate, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = MenuMetrics.itemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A label wraps rather than lose its action's name (spec 8.4); it only does at large font sizes.
        Text(
            text, style = FocalType.label.copy(fontWeight = FocalWeight.Regular), color = if (enabled) colors.ink else colors.faint,
            modifier = Modifier.weight(1f).padding(vertical = MenuMetrics.labelPadding),
        )
        if (trailing != null) {
            Spacer(Modifier.width(MenuMetrics.itemPadding))
            Text(trailing, style = FocalType.meta, color = colors.recede, maxLines = 1)
        }
    }
}

/** The one divider: 1 dp `line` with 8 dp above and below (before Remove audio). */
@Composable
fun FocalMenuDivider() {
    Box(
        Modifier.padding(vertical = MenuMetrics.dividerMargin).fillMaxWidth().height(MenuMetrics.ring)
            .background(FocalTheme.colors.raisedLine),
    )
}

/** Light menus cast two soft shadows (platform shadow layers draw on hardware canvases from API 28). */
private fun Modifier.menuElevation(dark: Boolean): Modifier = if (dark || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) this else drawWithCache {
    val radius = FocalShapes.menu.topStart.toPx(size, this)
    val shadows = listOf(
        Triple(MenuMetrics.nearShadowY, MenuMetrics.nearShadowSigma, MenuMetrics.NEAR_SHADOW_ALPHA),
        Triple(MenuMetrics.farShadowY, MenuMetrics.farShadowSigma, MenuMetrics.FAR_SHADOW_ALPHA),
    ).map { (y, sigma, alpha) ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.toArgb()
            setShadowLayer(blurRadius(sigma.toPx()), 0f, y.toPx(), Color.Black.copy(alpha = alpha).toArgb())
        }
    }
    onDrawBehind {
        drawIntoCanvas { canvas ->
            shadows.forEach { canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, it) }
        }
    }
}

/**
 * Positions the popup (its window includes [MenuMetrics.shadowRoom] on every side) under the button's end (x 168-400),
 * or [above] it, and records its scale origin. Only the window itself bounds it.
 */
private class MenuPosition(private val density: Density, private val above: Boolean = false) : PopupPositionProvider {
    var origin = TransformOrigin(1f, 0f)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        with(density) {
            val room = MenuMetrics.shadowRoom.roundToPx()
            val width = popupContentSize.width - 2 * room
            val height = popupContentSize.height - 2 * room
            val rtl = layoutDirection == LayoutDirection.Rtl
            origin = TransformOrigin(if (rtl) 0f else 1f, if (above) 1f else 0f)
            val x = (if (rtl) anchorBounds.left else anchorBounds.right - width).coerceIn(0, (windowSize.width - width).coerceAtLeast(0))
            // Under the button it opens from; [above] it for a control at the bottom of the window (the dock).
            val gap = MenuMetrics.belowAnchor.roundToPx()
            val y = (if (above) anchorBounds.top - gap - height else anchorBounds.bottom + gap).coerceIn(0, (windowSize.height - height).coerceAtLeast(0))
            return IntOffset(x - room, y - room)
        }
    }
}
