package app.focal.ui.shell

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEvent
import app.focal.ui.components.sceneMaterial
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.smoothstep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** What a page change plays (spec 6.1, 2.8.5). */
enum class PageMotion {
    /** The outgoing page is gone before the incoming one appears (`fade`): two typesettings never overlap (Q7). */
    FadeThrough,

    /** T7: the note's page is pushed under the settling scene, which covers the change: no transition of its own. */
    Cut,

    /** T16′: the stage opens into its note's page; Back folds the page back into the stage. */
    Stage,

    /** T16: a row opens into its note's page; Back folds it back into the row. */
    Row,

    /** T7b: Finish in the library dock: the live stage opens into the new note's page, which develops as in T7. */
    LiveStage,
}

/**
 * How the page in a transform shows its parts, read in the draw phase: its chrome (top bar and paper fades: the
 * paper's alpha, so no white band or half-clipped icon shows inside the dark card), its title (hidden while a copy of
 * it flies between the origin and the page) and its body (meta and text).
 */
@Stable
class PageReveal internal constructor(val chrome: () -> Float, val title: () -> Float, val body: () -> Float)

/** What the current page shows of itself; outside a transform everything is at full alpha. */
val LocalPageReveal = staticCompositionLocalOf { PageReveal({ 1f }, { 1f }, { 1f }) }

/** One page change: the page that opens or folds ([document], always the upper of the two) and how it plays. */
@Immutable
internal data class PageChange(
    val motion: PageMotion,
    val document: Destination,
    val push: Boolean,
    /** A predictive back gesture drives the fold; [committed] once it was released past its threshold. */
    val predictive: Boolean = false,
    val committed: Boolean = false,
    val edge: Int = NavigationEvent.EDGE_LEFT,
    /** Tells two changes of the same kind apart. */
    val serial: Int = 0,
)

/** Where a page grows out of and folds into: the stage card or a row, with its title. */
internal class Origin {
    var card: LayoutCoordinates? = null
    var title: LayoutCoordinates? = null
    /** The note the stage holds (null while it is live); a row's own note. */
    var noteId: String? = null
    var titleLines: Int = 2
}

/** The page title a flight lands on or leaves from (spec 6.1 T16, T16′: a shared element). */
internal class PageTitle {
    var coordinates: LayoutCoordinates? = null
    var noteId: String? = null
    var text: String = ""
    var muted: Boolean = false
    var lines: Int = 3
}

/**
 * The page changes of the back stack (spec 6.1, 2.8.5). Every change in [NavDisplay] is a bare swap whose outgoing
 * page stays composed while the upper page's own progress v runs; each page draws itself from v: fade-throughs, the
 * container transforms between the stage or a row and a note's page (drawn by [PageTransformOverlay], above the
 * pages), and the predictive-back preview (scale 0.9, 28 dp corners, a sideways shift) with its commit.
 */
@Stable
class PageTransitions internal constructor() {
    internal var change by mutableStateOf<PageChange?>(null)
        private set

    /** How each page on the stack was opened: Back reverses it. */
    private val opened = HashMap<Destination, PageMotion>()

    internal val stage = Origin()
    internal val rows = HashMap<String, Origin>()
    internal val pageTitle = PageTitle()

    private var serial = 0

    /**
     * The upper page's progress v (0 → 1 while it opens, 1 → 0 while it folds) and its recorded drawing. NavDisplay
     * composes a pushed page a frame after the back stack changed, so these are state: what reads them in the draw
     * phase is drawn again once the page reports them.
     */
    internal var document by mutableStateOf<State<Float>?>(null)
    internal var layer by mutableStateOf<GraphicsLayer?>(null)

    /** The page's meta and text (`fade` from +80, or `fadeFast` out) and the origin's own content (`fadeFast` out). */
    internal var body = Animatable(1f)
        private set
    internal var content = Animatable(1f)
        private set

    /** v when a predictive back was released: the fold runs from the previewed page over what remains of v. */
    internal var commitAt = 1f

    /** Whether this change's origin is on screen (resolved once, at its first frame): else it fades through. */
    private var resolvedFor: PageChange? = null
    private var resolved = false

    /** [destination] opens on top with [motion]; its body starts hidden when it will fade in under a flying title. */
    internal fun push(destination: Destination, motion: PageMotion) {
        opened[destination] = motion
        body = Animatable(if (motion == PageMotion.Stage || motion == PageMotion.Row) 0f else 1f)
        content = Animatable(1f)
        change = PageChange(motion, destination, push = true, serial = ++serial)
    }

    internal fun pop(top: Destination) {
        val current = change
        if (current != null && current.predictive && !current.committed && current.document == top) {
            commitAt = document?.value ?: 1f
            body = Animatable(1f)
            change = current.copy(committed = true, serial = ++serial)
        } else {
            body = Animatable(1f)
            content = Animatable(1f)
            change = PageChange(reverse(opened[top]), top, push = false, serial = ++serial)
        }
        opened.remove(top)
    }

    /** A predictive back started on [top] (NavDisplay seeks the pop with the gesture's progress). */
    internal fun beginPredictive(top: Destination, edge: Int) {
        val current = change
        if (current != null && current.predictive && !current.committed && current.document == top) return
        body = Animatable(1f)
        content = Animatable(1f)
        change = PageChange(reverse(opened[top]), top, push = false, predictive = true, edge = edge, serial = ++serial)
    }

    /** A page that landed from the scene or the live stage folds back into the stage, which now holds its note. */
    private fun reverse(motion: PageMotion?): PageMotion = when (motion) {
        PageMotion.Stage, PageMotion.LiveStage, PageMotion.Cut -> PageMotion.Stage
        PageMotion.Row -> PageMotion.Row
        else -> PageMotion.FadeThrough
    }

    /** True for a note page that opened from the stage or the live stage: its text develops as in T7 (T7b). */
    internal fun develops(destination: Destination): Boolean = opened[destination] == PageMotion.LiveStage

    /** The NavDisplay spec of a page's progress v: the upper page's drives the change; the lower page keeps time with it. */
    internal fun spec(): FiniteAnimationSpec<Float> {
        val c = change ?: return snap()
        if (c.predictive) return tween(PREDICTIVE_MS, easing = LinearEasing)
        return when (c.motion) {
            PageMotion.Cut -> snap()
            PageMotion.FadeThrough -> FocalMotion.fade()
            PageMotion.Stage, PageMotion.Row, PageMotion.LiveStage -> FocalMotion.move()
        }
    }

    private fun noteIdOf(destination: Destination): String? = (destination as? Destination.Note)?.id

    private fun originOf(c: PageChange): Origin? {
        val id = noteIdOf(c.document) ?: return null
        return when (c.motion) {
            PageMotion.Stage -> stage.takeIf { it.noteId == id }
            PageMotion.LiveStage -> stage
            PageMotion.Row -> rows[id]
            else -> null
        }
    }

    /** The origin's card while it is laid out and on screen. */
    private fun originCard(c: PageChange): LayoutCoordinates? =
        originOf(c)?.card?.takeIf { it.isAttached && it.size.width > 0 && !it.boundsInWindowOrEmpty().isEmpty }

    /**
     * Whether the change in flight is a container transform whose origin is on screen. Decided once, when the origin
     * is laid out or the upper page has started to move: NavDisplay composes the incoming page a frame or two after
     * the back stack changes, so until then nothing has moved and the decision waits.
     */
    internal fun transforms(c: PageChange? = change): Boolean {
        if (c == null || c.motion !in CONTAINERS) return false
        if (c.predictive && !c.committed) return false
        if (resolvedFor !== c) {
            val found = originCard(c) != null
            val started = c.predictive || documentProgress() != (if (c.push) 0f else 1f)
            if (!found && !started) return false
            resolved = found
            resolvedFor = c
        }
        return resolved
    }

    /** Whether the transform is still drawing: the upper page has not reached its end. */
    internal fun active(c: PageChange? = change): Boolean {
        c ?: return false
        val v = documentProgress()
        return if (c.push) v < 1f else v > 0f
    }

    /** The note whose title is flying between its origin and its page: both hide their own. */
    internal fun flying(noteId: String): Boolean {
        val c = change ?: return false
        if (c.motion != PageMotion.Stage && c.motion != PageMotion.Row) return false
        if (noteIdOf(c.document) != noteId) return false
        return transforms(c) && active(c) && titleFlies(c)
    }

    private fun titleFlies(c: PageChange): Boolean {
        val origin = originOf(c)?.title?.takeIf { it.isAttached } ?: return false
        val page = pageTitle.coordinates?.takeIf { it.isAttached && pageTitle.noteId == noteIdOf(c.document) } ?: return false
        return origin.size.width > 0 && page.size.width > 0 && page.boundsInWindowOrEmpty().height > 0f
    }

    /** p: 0 = the page, 1 = the origin; on a predictive commit, from the previewed page. */
    internal fun progress(c: PageChange): Float {
        val v = documentProgress()
        if (!c.predictive) return 1 - v
        if (commitAt <= 0f) return 1f
        val linear = (1 - v / commitAt).coerceIn(0f, 1f)
        return if (linear >= 1f) 1f else FocalMotion.springFraction(FocalMotion.SPATIAL_DAMPING, FocalMotion.MOVE_STIFFNESS, linear * MOVE_SETTLE_MS)
    }

    /** The predictive preview's eased progress e: during the gesture, and frozen at its release. */
    internal fun preview(c: PageChange): Float {
        if (!c.predictive) return 0f
        val v = if (c.committed) commitAt else document?.value ?: 1f
        return BackEase.transform((1 - v).coerceIn(0f, 1f))
    }

    /** The material's alpha in a stage transform: it crossfades to paper over the first 60 % of the travel. */
    internal fun material(c: PageChange): Float =
        if (c.motion == PageMotion.Row) 0f else smoothstep(MATERIAL_FROM, 1f, progress(c))

    internal fun reveal(destination: Destination): PageReveal = PageReveal(
        chrome = {
            val c = change
            if (c == null || c.document != destination || !transforms(c) || !active(c)) 1f
            else if (c.motion == PageMotion.Row) body.value else 1 - material(c)
        },
        title = {
            val id = noteIdOf(destination)
            if (id != null && flying(id)) 0f else 1f
        },
        body = {
            val c = change
            if (c == null || c.document != destination || !transforms(c) || !active(c) || c.motion == PageMotion.LiveStage) 1f
            else body.value
        },
    )

    /** The upper page's progress: 0 before a pushed page is composed, and once a popped page has left. */
    private fun documentProgress(): Float = document?.value ?: 0f

    /** A popped page left: nothing is in flight any more. */
    internal fun left(destination: Destination) {
        val c = change ?: return
        if (c.document == destination && !c.push) change = null
    }

    /** How one page draws itself in the current change (read in the draw phase), from the upper page's progress v. */
    internal fun look(destination: Destination): PageLook {
        val c = change ?: return PageLook.Shown
        val v = documentProgress()
        val upper = destination == c.document
        if (c.predictive) {
            if (!upper) return PageLook.Shown
            if (!c.committed) return PageLook(preview = preview(c), edge = c.edge)
            if (transforms(c)) return if (active(c)) PageLook.Recorded else PageLook.Gone
            val fraction = if (commitAt <= 0f) 0f else v / commitAt
            return PageLook(alpha = smoothstep(0f, 1f, fraction), preview = preview(c), edge = c.edge)
        }
        // T7: the scene covers the change; the page below leaves at once (pages are transparent sheets on one paper).
        if (c.motion == PageMotion.Cut) return if (upper) PageLook.Shown else PageLook.Gone
        if (transforms(c)) {
            if (!upper) {
                val alpha = when {
                    // The page has arrived: NavDisplay may keep the one below for a frame more; it is not drawn.
                    c.push && !active(c) -> 0f
                    c.motion != PageMotion.Row -> 1f
                    c.push -> 1 - smoothstep(0f, ROWS_OUT, v)
                    else -> smoothstep(ROWS_IN, 1f, 1 - v)
                }
                return PageLook(alpha = alpha)
            }
            return when {
                active(c) -> PageLook.Recorded
                c.push -> PageLook.Shown
                else -> PageLook.Gone
            }
        }
        // A fade-through: the outgoing page is gone before the incoming one appears.
        return PageLook(alpha = if (upper) smoothstep(HALF, 1f, v) else smoothstep(HALF, 1f, 1 - v))
    }

    /** Runs the change's time-based parts: the page's body and the origin's content (spec 6.1 T16, T16′, T7b). */
    internal suspend fun play(c: PageChange) = coroutineScope {
        if (c.motion !in CONTAINERS) return@coroutineScope
        val body = body
        val content = content
        if (c.predictive) {
            if (c.committed) body.animateTo(0f, FocalMotion.fadeFast())
            return@coroutineScope
        }
        // NavDisplay starts the pages' motion a frame or two after the back stack changed: time runs from there.
        val start = if (c.push) 0f else 1f
        snapshotFlow { document?.value ?: start }.first { it != start }
        if (!c.push) {
            body.animateTo(0f, FocalMotion.fadeFast())
            return@coroutineScope
        }
        launch { content.animateTo(0f, FocalMotion.fadeFast()) }
        if (c.motion == PageMotion.LiveStage) return@coroutineScope
        delay(BODY_DELAY_MS)
        body.animateTo(1f, FocalMotion.fade())
    }

    /** The stage holds its recording while the live stage opens into the page (T7b): it is leaving, not changing. */
    internal fun freezesStage(): Boolean {
        val c = change ?: return false
        return c.motion == PageMotion.LiveStage && c.push && active(c)
    }

    internal companion object {
        val CONTAINERS = setOf(PageMotion.Stage, PageMotion.Row, PageMotion.LiveStage)
        /** The seekable length of a predictive back: the gesture maps onto it, and its release runs what remains. */
        const val PREDICTIVE_MS = 500
        /** `move` settles to 0.1 % in this time (spec 2.8). */
        const val MOVE_SETTLE_MS = 317f
        const val BODY_DELAY_MS = 80L
        const val MATERIAL_FROM = 0.4f
        /** The stage's time, text and footer arrive over the last 40 % of a fold (`smoothstep(0.6, 1, q)`). */
        const val CONTENT_FROM = 0.6f
        const val ROWS_OUT = 0.5f
        /** Folding back into a row, the other rows return over the last 30 % of the travel, as the title lands. */
        const val ROWS_IN = 0.7f
        const val HALF = 0.5f
        const val PREVIEW_SCALE = 0.1f
        const val SHIFT_DIVISOR = 20f
        val ShiftInset = 8.dp
    }
}

/** How a page draws itself: [alpha], the predictive preview at eased progress [preview] toward [edge], or recorded. */
@Immutable
internal data class PageLook(
    val alpha: Float = 1f,
    val preview: Float = 0f,
    val edge: Int = NavigationEvent.EDGE_LEFT,
    val recorded: Boolean = false,
) {
    companion object {
        val Shown = PageLook()
        val Gone = PageLook(alpha = 0f)
        val Recorded = PageLook(recorded = true)
    }
}

private fun LayoutCoordinates.boundsInWindowOrEmpty(): Rect = if (isAttached) boundsInWindow() else Rect.Zero

/**
 * The ContentTransform of every page change: a bare swap. Both pages run their progress on the same spec, so the
 * outgoing one stays composed exactly as long as the upper one moves.
 */
internal fun pageSwap() = EnterTransition.None togetherWith ExitTransition.None

/** The predictive preview of a page (spec 2.8.5) at eased progress [e]: scale 1 → 0.9, shift W/20 − 8 dp. */
private fun previewScale(e: Float) = 1 - PageTransitions.PREVIEW_SCALE * e

private fun Density.previewShift(e: Float, width: Float, edge: Int): Float {
    val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
    return direction * (width / PageTransitions.SHIFT_DIVISOR - PageTransitions.ShiftInset.toPx()) * e
}

/**
 * One page of the back stack inside its NavDisplay entry: it runs its own progress v on the entry's transition and
 * draws itself from [PageTransitions.look]. While a container transform plays it only records itself; the overlay
 * draws it clipped.
 */
@Composable
internal fun PageFrame(destination: Destination, pages: PageTransitions, content: @Composable () -> Unit) {
    val transition = LocalNavAnimatedContentScope.current.transition
    val visibility = transition.animateFloat(transitionSpec = { pages.spec() }, label = "page") { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    val layer = rememberGraphicsLayer()
    val paper = FocalTheme.colors.paper
    val reveal = remember(pages, destination) { pages.reveal(destination) }
    val upper = pages.change?.document == destination
    SideEffect {
        if (upper) {
            pages.document = visibility
            pages.layer = layer
        }
    }
    DisposableEffect(pages, layer) {
        onDispose {
            if (pages.layer === layer) pages.layer = null
            if (pages.document === visibility) pages.document = null
            pages.left(destination)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val look = pages.look(destination)
                alpha = if (look.recorded) 1f else look.alpha
                val e = if (look.recorded) 0f else look.preview
                scaleX = previewScale(e)
                scaleY = scaleX
                translationX = previewShift(e, size.width, look.edge)
                shape = if (e > 0f) RoundedCornerShape(FocalShapes.sheetRadius * e) else androidx.compose.ui.graphics.RectangleShape
                clip = e > 0f
            }
            .drawWithContent {
                val look = pages.look(destination)
                if (look.recorded) layer.record { this@drawWithContent.drawContent() }
                else if (look.alpha > 0f) {
                    // A previewed page is a sheet of paper over the page it returns to.
                    if (look.preview > 0f) drawRect(paper)
                    drawContent()
                }
            },
    ) {
        CompositionLocalProvider(LocalPageReveal provides reveal) { content() }
    }
}

/** A rounded rect inside the layer's bounds. */
private class RoundRectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** Everything the overlay draws in one frame of a transform. */
@Immutable
private data class TransformFrame(
    val clip: Rect,
    val radius: Float,
    val p: Float,
    val paper: Boolean,
    val material: Float,
    val content: Float,
    val origin: Rect,
    val preview: Float,
    val edge: Int,
)

/**
 * The container transforms between the library and a note's page (spec 6.1 T16, T16′, T7b and their reverses; 2.8.5
 * commit): one clip from the page's window (or its predictive preview) to the origin's bounds, radius 0 → 28 dp for the
 * stage; inside it, paper, the stage's material (it crossfades to paper over the first 60 % of the travel), the page
 * at its own layout, the origin's own content at the clip's top-left, and the title flying between its two places
 * and sizes, set at every frame in the interpolated style so both ends match their real typesetting.
 */
@Composable
internal fun PageTransformOverlay(pages: PageTransitions, live: LiveSurfaceState) {
    // Always laid out, so its place in the window is known before a transform's first frame.
    val placement = remember { OverlayPlacement() }
    Box(Modifier.fillMaxSize().onPlaced { placement.coordinates = it }) {
        val change = pages.change
        if (change != null && change.motion in PageTransitions.CONTAINERS) TransformLayers(pages, live, change, placement)
    }
}

@Composable
private fun TransformLayers(pages: PageTransitions, live: LiveSurfaceState, change: PageChange, placement: OverlayPlacement) {
    LaunchedEffect(change) { pages.play(change) }
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val frame: () -> TransformFrame? = {
        // Read the progress first: every layout, layer and draw lambda below follows it, whatever returns early.
        pages.document?.value
        val coordinates = placement.coordinates
        if (coordinates == null || !coordinates.isAttached || !pages.transforms(change) || !pages.active(change)) null
        else pages.frameIn(change, coordinates, density)
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val f = frame()
                if (f == null) {
                    alpha = 0f
                    return@graphicsLayer
                }
                alpha = 1f
                shape = RoundRectShape(f.clip, f.radius)
                clip = true
            },
    ) {
        // Paper under the stage's material, so the library never shows through the card as it turns into the page.
        Spacer(Modifier.fillMaxSize().drawBehind { if (frame()?.paper == true) drawRect(colors.paper) })
        Spacer(
            Modifier
                .layout { measurable, constraints ->
                    val r = frame()?.clip ?: Rect.Zero
                    val placeable = measurable.measure(Constraints.fixed(max(0, r.width.roundToInt()), max(0, r.height.roundToInt())))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .graphicsLayer { alpha = frame()?.material ?: 0f }
                .sceneMaterial(grainOrigin = { frame()?.let { it.clip.topLeft - it.origin.topLeft } ?: Offset.Zero }),
        )
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val f = frame() ?: return@drawBehind
                if (f.material <= 0f || colors.stageRing.alpha <= 0f) return@drawBehind
                val width = RingWidth.toPx()
                drawRoundRect(
                    colors.stageRing, topLeft = f.clip.topLeft + Offset(width / 2, width / 2),
                    size = Size(f.clip.width - width, f.clip.height - width),
                    cornerRadius = CornerRadius(max(0f, f.radius - width / 2)), style = Stroke(width), alpha = f.material,
                )
            },
        )
        // The page at its own layout (its preview's transform after a predictive release).
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val f = frame() ?: return@drawBehind
                val layer = pages.layer ?: return@drawBehind
                if (f.preview <= 0f) drawLayer(layer) else {
                    val scale = previewScale(f.preview)
                    val shift = previewShift(f.preview, size.width, f.edge)
                    withTransform({
                        translate(shift, 0f)
                        scale(scale, scale, pivot = Offset(size.width / 2, size.height / 2))
                    }) { drawLayer(layer) }
                }
            },
        )
        // The stage's own content (time, text, footer; or the live wave, timer and text) rides the clip's top-left.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = frame()?.content ?: 0f }
                .drawBehind {
                    val f = frame() ?: return@drawBehind
                    if (change.motion == PageMotion.Row) return@drawBehind
                    val layer = live.stageNote ?: return@drawBehind
                    translate(f.clip.left, f.clip.top) { drawLayer(layer) }
                },
        )
        // The title, a shared element between the origin and the page.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val f = frame() ?: return@drawBehind
                val id = (change.document as? Destination.Note)?.id ?: return@drawBehind
                if (!pages.flying(id)) return@drawBehind
                val flight = pages.flight(change, f, placement.coordinates ?: return@drawBehind, this, colors) ?: return@drawBehind
                val layout = measurer.measure(
                    pages.pageTitle.text, flight.style, overflow = TextOverflow.Ellipsis, maxLines = flight.lines,
                    constraints = Constraints(maxWidth = max(1, flight.width.roundToInt())), density = this, layoutDirection = layoutDirection,
                )
                drawText(layout, color = flight.color, topLeft = flight.topLeft)
            },
        )
    }
}

private val RingWidth = 1.dp

/** Where the overlay sits in the window. */
private class OverlayPlacement {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

/** The flying title in one frame. */
private class Flight(val style: TextStyle, val color: Color, val topLeft: Offset, val width: Float, val lines: Int)

/** The rect of [of] in [into]'s space, unclipped. */
private fun LayoutCoordinates.rectOf(of: LayoutCoordinates): Rect = localBoundingBoxOf(of, clipBounds = false)

private fun PageTransitions.frameIn(c: PageChange, overlay: LayoutCoordinates, density: Density): TransformFrame? {
    val card = (when (c.motion) {
        PageMotion.Row -> rows[(c.document as? Destination.Note)?.id]
        else -> stage
    })?.card?.takeIf { it.isAttached } ?: return null
    val origin = overlay.rectOf(card)
    val window = Rect(Offset.Zero, Size(overlay.size.width.toFloat(), overlay.size.height.toFloat()))
    val e = preview(c)
    val base = if (e <= 0f) window else with(density) {
        val scale = previewScale(e)
        val shift = previewShift(e, window.width, c.edge)
        val w = window.width * scale
        val h = window.height * scale
        Rect(window.center.x - w / 2 + shift, window.center.y - h / 2, window.center.x + w / 2 + shift, window.center.y + h / 2)
    }
    val p = progress(c)
    val stageLike = c.motion != PageMotion.Row
    val radius = with(density) {
        lerp(FocalShapes.sheetRadius.toPx() * e, if (stageLike) FocalShapes.sheetRadius.toPx() else 0f, p)
    }
    val content = when {
        !stageLike -> 0f
        c.push -> this.content.value
        else -> smoothstep(PageTransitions.CONTENT_FROM, 1f, p)
    }
    return TransformFrame(
        clip = lerp(base, origin, p), radius = radius, p = p, paper = stageLike, material = material(c),
        content = content, origin = origin, preview = e, edge = c.edge,
    )
}

/**
 * The title between its origin (the stage's `headline` 22/28 or a row's `rowTitle` 17/24) and the page's `title`
 * 28/34: position, width, size, line height and tracking all follow p, and the colour follows the material (stageInk
 * on the material, ink on paper). Laid out every frame, so it lands on each end's own line breaks.
 */
private fun PageTransitions.flight(c: PageChange, f: TransformFrame, overlay: LayoutCoordinates, density: Density, colors: app.focal.ui.design.FocalColors): Flight? {
    val originSlot = (if (c.motion == PageMotion.Row) rows[(c.document as? Destination.Note)?.id] else stage) ?: return null
    val originTitle = originSlot.title?.takeIf { it.isAttached } ?: return null
    val pageCoordinates = pageTitle.coordinates?.takeIf { it.isAttached } ?: return null
    val from = overlay.rectOf(originTitle)
    var to = overlay.rectOf(pageCoordinates)
    val scale = previewScale(f.preview)
    if (f.preview > 0f) with(density) {
        val shift = previewShift(f.preview, overlay.size.width.toFloat(), f.edge)
        val cx = overlay.size.width / 2f
        val cy = overlay.size.height / 2f
        to = Rect(cx + (to.left - cx) * scale + shift, cy + (to.top - cy) * scale, cx + (to.right - cx) * scale + shift, cy + (to.bottom - cy) * scale)
    }
    val row = c.motion == PageMotion.Row
    val originStyle = if (row) FocalType.rowTitle else FocalType.headline
    val pageStyle = FocalType.title
    val p = f.p
    val style = with(density) {
        val size = lerp(pageStyle.fontSize.toPx() * scale, originStyle.fontSize.toPx(), p)
        val line = lerp(pageStyle.lineHeight.toPx() * scale, originStyle.lineHeight.toPx(), p)
        pageStyle.copy(
            fontSize = size.toSp(),
            lineHeight = line.toSp(),
            letterSpacing = lerp(pageStyle.letterSpacing.value, originStyle.letterSpacing.value, p).em,
            lineBreak = LineBreak.Heading,
        )
    }
    val muted = pageTitle.muted
    val pageInk = if (muted) colors.recede else colors.ink
    val originInk = when {
        row -> pageInk
        muted -> colors.stageRecede
        else -> colors.stageInk
    }
    return Flight(
        style = style,
        color = lerp(pageInk, originInk, if (row) p else f.material),
        topLeft = lerp(to.topLeft, from.topLeft, p),
        width = lerp(to.width, from.width, p),
        lines = if (p < PageTransitions.HALF) pageTitle.lines else originSlot.titleLines,
    )
}

/** Reports where the stage or a row (its card, or its [title]) sits, for the transforms that open its note. */
internal fun Modifier.pageOrigin(origin: Origin, title: Boolean = false): Modifier = onPlaced {
    if (title) origin.title = it else origin.card = it
}

/** The stage's origin (one per library): which note it holds and how many lines its title may take. */
@Composable
internal fun rememberStageOrigin(noteId: String?, titleLines: Int): Origin {
    val origin = LocalShell.current.pages.stage
    SideEffect {
        origin.noteId = noteId
        origin.titleLines = titleLines
    }
    return origin
}

/** A row's origin, registered while the row is composed. */
@Composable
internal fun rememberRowOrigin(noteId: String, titleLines: Int): Origin {
    val pages = LocalShell.current.pages
    val origin = remember(noteId) { Origin().also { it.noteId = noteId } }
    SideEffect { origin.titleLines = titleLines }
    DisposableEffect(pages, noteId, origin) {
        pages.rows[noteId] = origin
        onDispose { if (pages.rows[noteId] === origin) pages.rows.remove(noteId) }
    }
    return origin
}

/** The page's title as the flight's landing place: its text, ink and how many lines it may take. */
@Composable
internal fun rememberPageTitle(noteId: String, text: String, muted: Boolean, lines: Int): PageTitle {
    val title = LocalShell.current.pages.pageTitle
    SideEffect {
        title.noteId = noteId
        title.text = text
        title.muted = muted
        title.lines = lines
    }
    return title
}

/** Reports where the page's title sits. */
internal fun Modifier.pageTitle(title: PageTitle): Modifier = onPlaced { title.coordinates = it }
