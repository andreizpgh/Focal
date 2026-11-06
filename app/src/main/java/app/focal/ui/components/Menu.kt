package app.focal.ui.components

import android.graphics.Paint
import android.os.Build
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.Grid
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Menu geometry (spec 4.15). */
object MenuMetrics {
    val width = 232.dp
    val maxWidth = 280.dp
    val verticalPadding = Grid.x8
    val item = Grid.x48
    val itemPadding = Grid.x16
    val dividerMargin = Grid.x8
    /** From the overflow button the menu's top sits this far below the button's bottom (ref y 100 under 96). */
    val belowAnchor = Grid.x4
    /** Clamped this far from the window edges. */
    val edge = Grid.x16
    /** Room for the Light shadows inside the popup window, which clips its content. */
    val shadowRoom = Grid.x24
    /** Light: 0 1 2 at 8 % and 0 8 24 at 14 % (CSS blurs, so sigma = blur / 2). */
    val nearShadowY = 1.dp
    val nearShadowSigma = 1.dp
    val farShadowY = Grid.x8
    val farShadowSigma = Grid.x12
    const val NEAR_SHADOW_ALPHA = 0.08f
    const val FAR_SHADOW_ALPHA = 0.14f
    const val ENTER_SCALE = 0.95f
    val ring = 1.dp
}

/** Where a menu opens. */
sealed interface MenuAnchor {
    /** From an overflow button: the menu's top-end corner just under the button's bottom-end corner. */
    data object BelowEnd : MenuAnchor
    /** From a long-press: the menu's top-start corner at [offset] inside the anchor composable, flipped to fit. */
    data class At(val offset: IntOffset) : MenuAnchor
}

/**
 * The dropdown menu (spec 4.15): `raised`, radius 16, 232 dp wide (up to 280 for long labels), 48 dp items. Light
 * casts two soft shadows; Dark has a 1 dp `raisedRing` instead. It scales in from the anchor corner (0.95 to 1 on
 * `press`, alpha on `fade`) and leaves on alpha (`fadeFast`). A tap outside, Back and Esc dismiss it. Place it inside
 * the composable it anchors to.
 */
@Composable
fun FocalMenu(expanded: Boolean, onDismiss: () -> Unit, anchor: MenuAnchor = MenuAnchor.BelowEnd, content: @Composable ColumnScope.() -> Unit) {
    var composed by remember { mutableStateOf(expanded) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(MenuMetrics.ENTER_SCALE) }
    LaunchedEffect(expanded) {
        if (expanded) {
            composed = true
            scale.snapTo(MenuMetrics.ENTER_SCALE)
            alpha.snapTo(0f)
            coroutineScope {
                launch { scale.animateTo(1f, FocalMotion.press()) }
                alpha.animateTo(1f, FocalMotion.fade())
            }
        } else if (composed) {
            alpha.animateTo(0f, FocalMotion.fadeFast())
            composed = false
        }
    }
    if (!composed) return
    val density = LocalDensity.current
    val position = remember(anchor, density) { MenuPosition(anchor, density) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.padding(MenuMetrics.shadowRoom)) {
            MenuSurface(
                Modifier.graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = position.origin
                },
                content,
            )
        }
    }
}

/** The menu's container: `raised`, radius 16, Light shadows or the Dark ring, 8 dp vertical padding. */
@Composable
fun MenuSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = FocalTheme.colors
    Column(
        modifier
            .menuElevation(colors.dark)
            .background(colors.raised, FocalShapes.menu)
            .then(if (colors.dark) Modifier.border(MenuMetrics.ring, colors.raisedRing, FocalShapes.menu) else Modifier)
            .widthIn(min = MenuMetrics.width, max = MenuMetrics.maxWidth)
            .width(IntrinsicSize.Max)
            .padding(vertical = MenuMetrics.verticalPadding),
        content = content,
    )
}

/**
 * A menu item: `label` at 400 in ink, an optional trailing value in `meta` `muted` ("9 MB"). Disabled (Edit's "Restore
 * original" while the paragraph is unedited) it is `faint` and not clickable.
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
        Text(
            text, style = FocalType.label.copy(fontWeight = FocalWeight.Regular), color = if (enabled) colors.ink else colors.faint,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
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
            .background(FocalTheme.colors.line),
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

/** Positions the popup (its window includes [MenuMetrics.shadowRoom] on every side) and records its scale origin. */
private class MenuPosition(private val anchor: MenuAnchor, private val density: Density) : PopupPositionProvider {
    var origin = TransformOrigin(1f, 0f)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        with(density) {
            val room = MenuMetrics.shadowRoom.roundToPx()
            val edge = MenuMetrics.edge.roundToPx()
            val width = popupContentSize.width - 2 * room
            val height = popupContentSize.height - 2 * room
            val rtl = layoutDirection == LayoutDirection.Rtl
            var x: Int
            var y: Int
            when (anchor) {
                MenuAnchor.BelowEnd -> {
                    x = if (rtl) anchorBounds.left else anchorBounds.right - width
                    y = anchorBounds.bottom + MenuMetrics.belowAnchor.roundToPx()
                    origin = TransformOrigin(if (rtl) 0f else 1f, 0f)
                }
                is MenuAnchor.At -> {
                    val pointX = anchorBounds.left + anchor.offset.x
                    val pointY = anchorBounds.top + anchor.offset.y
                    val flipX = if (rtl) pointX - width >= edge else pointX + width > windowSize.width - edge
                    val flipY = pointY + height > windowSize.height - edge
                    x = if (flipX) pointX - width else pointX
                    y = if (flipY) pointY - height else pointY
                    origin = TransformOrigin(if (flipX) 1f else 0f, if (flipY) 1f else 0f)
                }
            }
            x = x.coerceIn(edge, (windowSize.width - edge - width).coerceAtLeast(edge))
            y = y.coerceIn(edge, (windowSize.height - edge - height).coerceAtLeast(edge))
            return IntOffset(x - room, y - room)
        }
    }
}
