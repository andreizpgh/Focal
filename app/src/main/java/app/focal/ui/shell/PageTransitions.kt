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
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
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
import app.focal.ui.components.BottomColumnState
import app.focal.ui.components.DockState
import app.focal.ui.components.sceneMaterial
import app.focal.ui.design.FocalClock
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalSettle
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.TriggerClock
import app.focal.ui.design.smoothstep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
class PageReveal internal constructor(
    val chrome: () -> Float,
    val title: () -> Float,
    val body: () -> Float,
    /**
     * Milliseconds since Finish for a page that develops under the settling scene (T7) or out of the live stage
     * (T7b), infinity otherwise: its title, meta and paragraphs arrive at fixed times after it (spec 2.8.2).
     */
    val develop: () -> Float,
)

/** What the current page shows of itself; outside a transform everything is at full alpha. */
val LocalPageReveal = staticCompositionLocalOf { PageReveal({ 1f }, { 1f }, { 1f }, { Float.POSITIVE_INFINITY }) }

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
    /** The stage is being pulled open ([PageTransitions.beginDrag]): the card follows the finger before its page exists. */
    val dragged: Boolean = false,
    /** Where it starts: from rest, or from what the change it replaced mid-flight had drawn. */
    val start: ChangeStart = if (push) ChangeStart.Push else ChangeStart.Pop,
    /**
     * The change's other page: the one [document] opens over or folds back onto (or, in the detail pane, replaces).
     * NavDisplay may still hold a third page, one that left before this change began; it is neither drawn nor
     * touched ([PageTransitions.look]).
     */
    val other: Destination? = null,
)

/**
 * Where a page change starts: the upper page's v, its body's alpha, the other rows' alpha (T16), the title's flight
 * progress and the origin's content alpha. A change that replaces one still in flight (Back during an open, a tap
 * during a fold) starts from what was on screen, so the page reverses where it is instead of jumping to an end.
 */
@Immutable
internal data class ChangeStart(val v: Float, val body: Float, val rows: Float, val title: Float, val content: Float) {
    companion object {
        val Push = ChangeStart(v = 0f, body = 0f, rows = 1f, title = 1f, content = 1f)
        val Pop = ChangeStart(v = 1f, body = 1f, rows = 0f, title = 0f, content = 0f)
    }
}

/** Where a page grows out of and folds into: the stage card or a row, with its title. */
internal class Origin {
    var card: LayoutCoordinates? = null
    var title: LayoutCoordinates? = null
    /** The stage's text under its title, which waits for the flying title to clear it (T16′). */
    var text: LayoutCoordinates? = null
    /** The note the stage holds (null while it is live); a row's own note. */
    var noteId: String? = null
    var titleLines: Int = 2
    /** The title as the origin sets it: the flight starts as this text before the page has laid out its own. */
    var titleText: String = ""
    var titleMuted: Boolean = false
    /** The dock the note's page will show: the dock holds it from the tap, before the page has loaded its note. */
    var dock: DockState? = null
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
 * page stays composed while the change runs; each page draws itself from the upper page's progress v: fade-throughs,
 * the container transforms between the stage or a row and a note's page (drawn by [PageTransformOverlay], above the
 * pages), and the predictive-back preview (scale 0.9, 28 dp corners, a sideways shift) with its commit.
 *
 * A tap or Back drives v, the page's body and the origin's content from one clock, as pure functions of the time on
 * it (spec 6.1: every "+n" is a time on that clock). A fade-through counts from the tap. A transform counts from the
 * frame both its ends are there: an opening holds on its origin until the note's page has loaded and laid out its
 * note ([ready]), a fold holds on its page until the library is laid out under it again. Composing a page takes the
 * frames right after the tap, and a motion counted from the tap spent itself behind them; the time itself is the
 * app's steady frame time (SteadyFrameClock), which a long frame cannot skip ahead. NavDisplay only keeps the
 * outgoing page composed until the clock is done. A predictive back is the exception: the gesture seeks NavDisplay's
 * transition, and v is its progress. The stage can also be pulled open by a drag ([beginDrag]): the clock then goes
 * where the finger takes it until the finger lets go.
 */
@Stable
class PageTransitions internal constructor(private val scope: CoroutineScope) {
    internal var change by mutableStateOf<PageChange?>(null)
        private set

    /** How each page on the stack was opened: Back reverses it. */
    private val opened = HashMap<Destination, PageMotion>()

    internal val stage = Origin()
    internal val rows = HashMap<String, Origin>()
    /** Each composed note page's title, by note id: where the flight lands ([rememberPageTitle]). */
    internal val pageTitles = HashMap<String, PageTitle>()
    /** The library's day labels where they are laid out (a detached one is gone): T16's fold keeps them off the title. */
    internal val labels = HashMap<String, LayoutCoordinates>()

    private var serial = 0

    /**
     * The upper page's progress v (0 → 1 while it opens, 1 → 0 while it folds) and its recorded drawing. NavDisplay
     * composes a pushed page a frame after the back stack changed, so these are state: what reads them in the draw
     * phase is drawn again once the page reports them.
     */
    internal var document by mutableStateOf<State<Float>?>(null)
    internal var layer by mutableStateOf<GraphicsLayer?>(null)

    /** Under reduced motion page changes snap (spec 2.8.4); set by the shell. */
    internal var reduced = false
    /** For the title's clearance over the page's body (T16); set by the shell. */
    internal var density: Density = Density(1f)

    /** Milliseconds since the change's trigger, advanced every frame while the change runs (infinite once done). */
    private var elapsed by mutableFloatStateOf(Float.POSITIVE_INFINITY)
    private var clock: Job? = null

    /** The page's meta and text after a predictive back was released (`fadeFast` out); other changes use [body]. */
    private var predictiveBody = Animatable(1f)

    /** v when a predictive back was released: the fold runs from the previewed page over what remains of v. */
    internal var commitAt = 1f

    /**
     * Whether this change's origin is on screen (decided once, [decide]): else it fades through. State, so whatever
     * read the change while it was undecided is drawn again once it is decided.
     */
    private var resolvedFor by mutableStateOf<PageChange?>(null)
    private var resolved by mutableStateOf(false)

    /**
     * Whether the title flies in this frame (T16, T16′): decided at the start of each frame from the last frame's
     * layout, so the origin's title (hidden in the layout phase) and the flight (drawn in the draw phase) never
     * disagree in the frame a page is first placed (both titles drawn at once).
     */
    private var flightReady by mutableStateOf(false)

    /**
     * Opening: the clock time at which the page's title was first laid out. Until then the flight holds the origin's
     * title in place (NavDisplay composes the page a frame or two after the tap); from then it flies on its own
     * `move` from there, so it never jumps.
     */
    private var titleFrom = Float.NaN

    /**
     * Opening (T16, T16′): the clock time at which the flying title came within [BODY_LANDING] of its line. The
     * page's meta and text wait for it, then arrive whole on `fade`, so no part of a paragraph shows below a title
     * that is still in flight.
     */
    private var bodyFrom = Float.NaN

    /** The page that is opening and the dock its origin says it will show (see [Origin.dock]). */
    private var arriving by mutableStateOf<Pair<Destination, DockState>?>(null)

    /** The dock [destination] will show, while it opens out of the stage or a row and has not registered its own. */
    internal fun arrivingDock(destination: Destination): DockState? = arriving?.takeIf { it.first == destination }?.second

    /** T7, T7b: the time since Finish, for [developing] (see [PageReveal.develop]). */
    private val developClock = TriggerClock(scope, DEVELOP_MS)
    private var developing: Destination? = null

    /**
     * The note pages that have their note and have laid it out ([ready]), for as long as they stay composed. A page
     * opening out of the stage or a row waits for its own ([waits]): composing the page takes the frames right after
     * the tap, and a motion started at the tap would spend itself behind them. A page NavDisplay still holds after it
     * left is still ready if it opens again, and a page on its way out that composes again never counts for another.
     */
    private val readied = HashSet<Destination>()

    /** [destination]'s page has loaded its note and composed it: an opening that waited for it starts now. */
    internal fun ready(destination: Destination) {
        readied += destination
    }

    /** [destination]'s page left composition: it is no longer ready. */
    internal fun disposed(destination: Destination) {
        readied -= destination
    }

    /** Whether [c] holds still until its page is [ready]: an opening out of the stage or a row, with motion on. */
    private fun waits(c: PageChange): Boolean =
        c.push && !reduced && (c.motion == PageMotion.Stage || c.motion == PageMotion.Row) && c.document !in readied

    /**
     * The library's page where it is laid out (a detached one is gone). One pane composes it again when a note's
     * page goes back, which takes the frames right after Back: a fold holds its page whole until the library is laid
     * out again ([returns]), and only then decides whether its origin is on screen.
     */
    internal var library: LayoutCoordinates? = null

    /** Whether [c], a fold, still waits for the library to be laid out under it. */
    private fun returns(c: PageChange): Boolean =
        !c.push && !reduced && c.motion in CONTAINERS && resolvedFor !== c && library?.isAttached != true

    /**
     * [destination] opens on top with [motion]. True when the motion starts at once, timed from now; false when it
     * waits for the page to be [ready] and is timed from then (the dock then swaps with the page, not ahead of it).
     */
    internal fun push(destination: Destination, motion: PageMotion, other: Destination?): Boolean {
        opened[destination] = motion
        top = destination
        // The stage is being pulled open: its page is composed now, under the finger, and the pull goes on; the dock
        // keeps the library's until the finger lets go into the page ([provisionalPage]).
        val pulled = change?.takeIf { it.dragged && it.document == destination && it.motion == motion && pull != null && letGo == null }
        if (pulled != null) {
            arriving = originOf(pulled)?.dock?.let { destination to it }
            provisionalPage = destination
            return true
        }
        pull = null
        letGo = null
        provisionalPage = null
        val interrupted = inFlight(destination, push = true)
        val c = PageChange(
            motion, destination, push = true, serial = ++serial, start = interrupted?.let(::startOf) ?: ChangeStart.Push, other = other,
        )
        carryDecision(interrupted, c)
        // Another page is still folding away (a tap on the library right after Back): it lands first, quickened, while
        // this page composes, and this change starts from there. The two never share a frame, and the tap is not lost.
        if (interrupted == null && motion != PageMotion.Cut && motion != PageMotion.LiveStage && change?.let(::folds) == true) {
            queued = c
            arriving = null
            return false
        }
        queued = null
        begin(c)
        return !waits(c)
    }

    /** [c] is the change in flight from now: its clock starts (an opening first waits for its page, [waits]). */
    private fun begin(c: PageChange) {
        change = c
        val destination = c.document
        val motion = c.motion
        val waits = waits(c)
        arriving = if (waits) null else originOf(c)?.dock?.let { destination to it }
        if (motion == PageMotion.Cut || motion == PageMotion.LiveStage) {
            developing = destination
            developClock.start()
        }
        startClock()
    }

    /**
     * An opening that waits for the page folding away before it ([push]): that fold runs [HURRY] times as fast until it
     * has landed, then this change begins.
     */
    private var queued: PageChange? = null

    /** Whether [c] is a page folding or fading away on the clock: an opening asked for meanwhile waits for it. */
    private fun folds(c: PageChange): Boolean =
        (!c.push && !c.predictive && c.motion != PageMotion.Cut && elapsed.isFinite() && active(c)) || returning(c)

    /** Whether [c] is a pulled card let go short, on its way back into the stage. */
    private fun returning(c: PageChange): Boolean = c.dragged && pull != null && letGo?.to == 0f

    /** Two panes: a note opens in place of [old] in the detail pane, which fades through to it. */
    internal fun replace(old: Destination, destination: Destination, motion: PageMotion): Boolean {
        opened.remove(old)
        return push(destination, motion, other = old)
    }

    /**
     * [destination] left the back stack with no change of its own (a pulled page dropped, see [endDrag]), and [under]
     * is on top again.
     */
    internal fun forget(destination: Destination, under: Destination) {
        opened.remove(destination)
        arriving = null
        top = under
    }

    /**
     * The top of the back stack as the changes left it (null until the first change). With no change in flight, a
     * page NavDisplay still holds that is not on top (one dropped with no change of its own) is not drawn.
     */
    private var top: Destination? = null

    /** [top] goes back to [other], the page under it. */
    internal fun pop(top: Destination, other: Destination?) {
        this.top = other
        arriving = null
        // A page that was to open once the fold before it landed goes back before it ever showed: the fold lands.
        if (queued?.document == top) {
            queued = null
            opened.remove(top)
            return
        }
        pull = null
        letGo = null
        provisionalPage = null
        val current = change
        // A released predictive back, while its seek is under way. A gesture that was cancelled leaves its change
        // behind with the page whole again (nothing reports the cancel): a Back after it is an ordinary one.
        val seeking = (document?.value ?: 1f) < 1f
        if (current != null && current.predictive && !current.committed && current.document == top && seeking) {
            commitAt = document?.value ?: 1f
            predictiveBody = Animatable(1f)
            val committed = current.copy(committed = true, serial = ++serial)
            change = committed
            // No clock runs for a released predictive back: its fold decides at once, from the previewed layout.
            if (committed.motion in CONTAINERS) {
                resolved = originCard(committed) != null
                resolvedFor = committed
            }
        } else {
            val interrupted = inFlight(top, push = false)
            val c = PageChange(
                reverse(opened[top]), top, push = false, serial = ++serial, start = interrupted?.let(::startOf) ?: ChangeStart.Pop, other = other,
            )
            carryDecision(interrupted, c)
            change = c
            startClock()
        }
        opened.remove(top)
    }

    /** The change still in flight that a [push] (or pop) of [document] reverses, if any. */
    private fun inFlight(document: Destination, push: Boolean): PageChange? =
        change?.takeIf { !it.predictive && it.motion != PageMotion.Cut && it.document == document && it.push != push && active(it) }

    /** What [c] has drawn this frame, for the change that reverses it. */
    private fun startOf(c: PageChange): ChangeStart = ChangeStart(
        v = documentProgress(), body = body(c), rows = rows(c), title = titleProgress(c),
        content = if (c.push) content(c) else smoothstep(CONTENT_FROM, 1f, progress(c)),
    )

    /** A reversal of the same transform keeps its decision: its origin is on screen, so it starts without a hold. */
    private fun carryDecision(interrupted: PageChange?, next: PageChange) {
        if (interrupted != null && interrupted.motion == next.motion && interrupted.motion in CONTAINERS && resolvedFor === interrupted) {
            resolvedFor = next
        }
    }

    /**
     * The stage pulled down (spec 6.5, as the live stage is): where the finger has taken the opening (the clock time
     * whose travel is the finger's), or null when no finger holds it. The page is pushed as the pull begins, so the
     * card turns into the page under the finger, the transform's every part following the travel; let go, it opens
     * on from there or goes back to the stage and the page is dropped ([endDrag]). It stays set until the card has
     * landed at either end.
     */
    private var pull: Float? = null
    /** The finger let go: where the card goes from there, carrying the finger's speed ([LetGo]). */
    private var letGo: LetGo? = null
    /**
     * The travel p at which the pulled page was ready (laid out with its note): its own parts (chrome, text, the
     * title's flight) run over what is left of the way from there ([pageTravel]), so a page that took a moment to
     * compose comes in from nothing instead of appearing at the travel already reached.
     */
    private var pageFromP = Float.NaN

    /** The stage starts to follow a finger toward [destination]'s page (its page is pushed next). False when it cannot now. */
    internal fun beginDrag(destination: Destination): Boolean {
        if (reduced || change?.let { active(it) || it.predictive } == true) return false
        val c = PageChange(PageMotion.Stage, destination, push = true, serial = ++serial, dragged = true, other = Destination.Library)
        if (originCard(c) == null) return false
        change = c
        queued = null
        arriving = null
        pull = 0f
        letGo = null
        pageFromP = Float.NaN
        startClock()
        return true
    }

    /** The finger has pulled the card's bottom edge [fraction] of its way to the window's (0..1): the edge is there. */
    internal fun drag(fraction: Float) {
        if (pull == null || letGo != null) return
        pull = stageTime(fraction.coerceIn(0f, 1f))
    }

    /**
     * The finger lifted, moving at [velocity] (the card's way per second, positive toward the page): [open] goes on to
     * the page from where the card is, otherwise the card goes back into the stage. Either way on `stage`, starting at
     * the finger's speed, so the card neither lurches nor stops dead as the finger leaves it. Let go short, the page is
     * off the back stack at once ([drop]): the card going back is the overlay's, the library is the page again, and a
     * tap on it meanwhile opens its note once the card has landed ([queued]).
     */
    internal fun endDrag(open: Boolean, velocity: Float, drop: () -> Unit) {
        if (pull == null || letGo != null) return
        letGo = LetGo(from = documentProgress(), to = if (open) 1f else 0f, velocity = velocity)
        provisionalPage = null
        if (!open) drop()
    }

    /**
     * The page a finger is pulling open and has not let go into (held, or going back into the stage): it is on the
     * back stack only for the pull, and a saved back stack leaves it out.
     */
    internal fun provisional(): Destination? = provisionalPage

    /**
     * [provisional] as state: the dock keeps the library's controls while the page is only pulled at (a pull let go
     * short never swaps it), and swaps to the page's as the finger lets go into it.
     */
    internal var provisionalPage by mutableStateOf<Destination?>(null)
        private set

    /** True while a finger holds the pulled stage, or it is still settling after the finger let go. */
    private fun pulling(c: PageChange): Boolean = c.dragged && pull != null

    /** A pulled page's own way, 1 to 0, from the travel at which it was ready (see [pageFromP]); 0 once it has arrived. */
    private fun pageTravel(c: PageChange): Float {
        if (!elapsed.isFinite()) return 0f
        val from = pageFromP
        if (from.isNaN()) return 1f
        return (progress(c) / from).coerceIn(0f, 1f)
    }

    /** A predictive back started on [top] toward [other] (NavDisplay seeks the pop with the gesture's progress). */
    internal fun beginPredictive(top: Destination, edge: Int, other: Destination?) {
        val current = change
        if (current != null && current.predictive && !current.committed && current.document == top) {
            // The same gesture asking again, or a new one after a cancelled one, which may come from the other edge.
            if (current.edge != edge) change = current.copy(edge = edge)
            return
        }
        // A released back still folding: the display asks once more as its pop completes, with the next page on top.
        if (current != null && current.predictive && current.committed && active(current)) return
        // An opening on its way (a tap's, or a pull a finger holds or has let go) has no preview to start from, and
        // turning it into a whole page would undo what is on screen: it runs on, and a back let go over it folds it
        // from where it is, as the Back key does ([pop]). Likewise a page still waiting to open ([queued]).
        if (queued != null) return
        if (current != null && current.push && !current.predictive && current.motion in CONTAINERS && (pulling(current) || active(current))) return
        clock?.cancel()
        predictiveBody = Animatable(1f)
        change = PageChange(reverse(opened[top]), top, push = false, predictive = true, edge = edge, serial = ++serial, other = other)
    }

    /**
     * Starts the change's clock. A change that needs nothing more starts from this trigger, and its first frame counts
     * one frame of motion already (FocalMotion.fromTrigger); a transform first holds until its other end is there
     * ([waits], [returns]). It stops once the change is complete.
     */
    private fun startClock() {
        clock?.cancel()
        val c = change ?: return
        val end = clockEnd(c)
        if (reduced || end <= 0f) {
            elapsed = Float.POSITIVE_INFINITY
            return
        }
        elapsed = 0f
        flightReady = false
        titleFrom = Float.NaN
        bodyFrom = Float.NaN
        val lead = FocalTiming.TRIGGER_LEAD_MS * NANOS_PER_MS
        // Undispatched, so it waits for the very next frame: the one the trigger is drawn in.
        clock = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The system's animator duration scale, as every Compose animation follows it (0 is reduced motion).
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor?.takeIf { it > 0f } ?: 1f
            var trigger = Long.MIN_VALUE
            var held = 0
            var last = Long.MIN_VALUE
            do {
                withFrameNanos { now ->
                    val step = if (last == Long.MIN_VALUE) 0L else now - last
                    last = now
                    // A pull holds the clock: the card's edge is where the finger is, in the very frame the finger is
                    // there; let go, the card carries the finger's speed on to the page or back into the stage.
                    val pulled = pull
                    if (pulled != null && c.dragged) {
                        val let = letGo
                        if (let == null) elapsed = pulled
                        else {
                            if (let.at == Long.MIN_VALUE) let.at = now
                            // A card going back that a tapped note waits for goes [HURRY] times as fast.
                            else if (queued != null) let.at -= step * (HURRY - 1)
                            val t = (now - let.at).toFloat() / NANOS_PER_MS / scale + FocalTiming.TRIGGER_LEAD_MS
                            elapsed = if (let.landed(t)) (if (let.to > 0f) end else 0f) else stageTime(let.travel(t).coerceIn(0f, 1f))
                        }
                        decide(c)
                        if (let != null && (elapsed <= 0f || elapsed >= end)) {
                            pull = null
                            letGo = null
                            if (let.to > 0f) {
                                // Arrived: an opening that has landed, whose body may still be arriving.
                                trigger = now - (elapsed * NANOS_PER_MS * scale).toLong()
                            } else {
                                // The card is the stage again (what was left is a pixel or two); its page is gone
                                // already. A note tapped meanwhile opens now (after the loop).
                                if (change === c && queued == null) change = null
                            }
                        }
                        return@withFrameNanos
                    }
                    // A page opened while this one folds away waits for it: the fold runs on [HURRY] times as fast.
                    if (queued != null && trigger != Long.MIN_VALUE) trigger -= step * (HURRY - 1)
                    // An opening holds on its origin until its page is ready, a fold on its page until the library is
                    // back under it (or either has taken too long).
                    if (trigger == Long.MIN_VALUE && (waits(c) || returns(c)) && held < READY_FRAMES) {
                        held++
                        return@withFrameNanos
                    }
                    if (trigger == Long.MIN_VALUE && (held > 0 || c.dragged) && change === c) arriving = originOf(c)?.dock?.let { c.document to it }
                    // From the tap; a pulled card goes on from where the finger left it.
                    if (trigger == Long.MIN_VALUE) trigger = now - ((if (c.dragged) elapsed * NANOS_PER_MS else lead.toFloat()) * scale).toLong()
                    elapsed = (now - trigger).toFloat() / NANOS_PER_MS / scale
                    // A transform that had to wait for its pages runs from the frame that can draw it, so it never
                    // jumps ahead of what was on screen.
                    if (decide(c) && !c.dragged) {
                        trigger = now - (lead * scale).toLong()
                        elapsed = (now - trigger).toFloat() / NANOS_PER_MS / scale
                    }
                }
                // An opening runs on while its body, which waited for the title, is still arriving; a fold that a page
                // waits for ends as it lands.
            } while (change === c && !(queued != null && progress(c) >= LANDED) &&
                (pulling(c) || elapsed < end || (bodyArriving(c) && elapsed < BODY_LIMIT_MS)))
            elapsed = Float.POSITIVE_INFINITY
            val next = queued
            if (next != null && change === c) {
                queued = null
                pull = null
                letGo = null
                begin(next)
            }
        }
    }

    /**
     * At the start of a frame, from the last frame's layout: whether the change transforms (its origin is laid out
     * and on screen), and whether its title flies. Opening grows out of the origin from the tap; the page arrives in
     * it once NavDisplay has composed it, and its title flies from the moment it is laid out ([titleFrom]). On Back,
     * NavDisplay composes the library a frame or two after the trigger, so the page holds until its origin is laid
     * out ([undecided]) and the clock restarts there; without an origin by [DECIDE_MS] it fades through. Deciding
     * before layout, never during it, keeps every part of one frame on one decision. Returns true in the frame it
     * decides after waiting.
     */
    private fun decide(c: PageChange): Boolean {
        if (c.motion !in CONTAINERS || change !== c) return false
        var waited = false
        if (resolvedFor !== c) {
            val ready = originCard(c) != null
            if (!ready && elapsed <= DECIDE_MS) return false
            resolved = ready
            resolvedFor = c
            waited = elapsed > FocalTiming.TRIGGER_LEAD_MS
        }
        val titled = c.motion == PageMotion.Stage || c.motion == PageMotion.Row
        // Only between two titles that are both on screen. A page reopened where it was read has its title above the
        // window: the origin's title then leaves with the rest of the origin's content instead of flying to nowhere.
        flightReady = resolved && titled && originTitle(c) != null && titleFlies(c)
        if (c.dragged && pageFromP.isNaN() && c.document in readied) pageFromP = progress(c).coerceAtLeast(MIN_PAGE_FROM)
        if (c.push && titleFrom.isNaN() && flightReady && titleFlies(c)) titleFrom = elapsed
        if (c.push && bodyFrom.isNaN() && !titleFrom.isNaN()) {
            val gap = titleGap(c)
            if (gap == null || gap <= with(density) { BODY_LANDING.toPx() }) bodyFrom = elapsed
        }
        return waited
    }

    /** How far the flying title still is from its line on the page (window pixels), while both ends are laid out. */
    private fun titleGap(c: PageChange): Float? {
        val origin = originTitle(c) ?: return null
        val page = titleOf(c)?.coordinates?.takeIf { it.isAttached } ?: return null
        return abs(origin.positionInWindow().y - page.positionInWindow().y) * titleProgress(c)
    }

    /** An opening whose meta and text have not fully arrived (they wait for the flying title; see [bodyFrom]). */
    private fun bodyArriving(c: PageChange): Boolean =
        c.push && (c.motion == PageMotion.Stage || c.motion == PageMotion.Row) && body(c) < 1f

    private fun originTitle(c: PageChange): LayoutCoordinates? = originOf(c)?.title?.takeIf { it.isAttached && it.size.width > 0 }

    /**
     * The flying title's progress, 0 = the page's title, 1 = the origin's: opening, its own `move` from the frame the
     * page's title was laid out (until then it holds at the origin); folding and after a predictive release, the
     * change's own. A fold that turned an opening back starts where that opening's title was (it lagged the page by the
     * frames its title took to lay out), and the difference melts away on the fold's own curve.
     */
    internal fun titleProgress(c: PageChange): Float {
        if (!c.push) {
            if (c.predictive) return progress(c)
            val behind = c.start.title - (1 - c.start.v)
            return (progress(c) + behind * (1 - move(elapsed))).coerceIn(0f, 1f)
        }
        // Pulled: the title rises with the travel, from where the page was ready (it rode the card until then).
        if (c.dragged) return pageTravel(c)
        if (titleFrom.isNaN()) return c.start.title
        return c.start.title * (1 - move(elapsed - titleFrom))
    }

    /** How long a change's clock runs: until its slowest part has settled (spec 2.8). */
    private fun clockEnd(c: PageChange): Float = when (c.motion) {
        PageMotion.Cut -> 0f
        PageMotion.FadeThrough -> FocalSettle.FADE_MS.toFloat()
        PageMotion.Stage, PageMotion.Row, PageMotion.LiveStage -> FocalSettle.STAGE_MS.toFloat()
    }

    /** The upper page's progress v on the clock: 0 → 1 as it opens, 1 → 0 as it folds (from where it started). */
    private fun clockProgress(c: PageChange): Float {
        val t = when (c.motion) {
            PageMotion.Cut -> 1f
            PageMotion.FadeThrough -> fade(elapsed)
            PageMotion.Stage, PageMotion.Row, PageMotion.LiveStage -> move(elapsed)
        }
        val from = c.start.v
        return if (c.push) from + (1 - from) * t else from * (1 - t)
    }

    /** A page that landed from the scene or the live stage folds back into the stage, which now holds its note. */
    private fun reverse(motion: PageMotion?): PageMotion = when (motion) {
        PageMotion.Stage, PageMotion.LiveStage, PageMotion.Cut -> PageMotion.Stage
        PageMotion.Row -> PageMotion.Row
        else -> PageMotion.FadeThrough
    }

    /** True for a note page that opened from the stage or the live stage: its text develops as in T7 (T7b). */
    internal fun develops(destination: Destination): Boolean = opened[destination] == PageMotion.LiveStage

    /**
     * The NavDisplay spec of a page's progress: a predictive back seeks it. Otherwise it only keeps the outgoing page
     * composed until the change's clock is done, which starts up to two frames before NavDisplay's transition does.
     */
    internal fun spec(): FiniteAnimationSpec<Float> {
        val c = change ?: return snap()
        if (c.predictive) return tween(PREDICTIVE_MS, easing = LinearEasing)
        if (c.motion == PageMotion.Cut) return snap()
        // A transform's clock starts after a hold (an opening waits for its page, a fold for the library): both pages
        // stay composed that much longer. A page kept past its change is not drawn and takes no touch ([PageFrame]).
        val hold = if (c.motion in CONTAINERS) READY_HOLD_MS else 0
        return tween(clockEnd(c).toInt() + HOLD_MARGIN_MS + hold, easing = LinearEasing)
    }

    private fun noteIdOf(destination: Destination): String? = (destination as? Destination.Note)?.id

    /** The title of [c]'s note page, while that page is composed. */
    internal fun titleOf(c: PageChange): PageTitle? = noteIdOf(c.document)?.let { pageTitles[it] }

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
     * Whether the change in flight is a container transform whose origin is on screen: decided by the clock at the
     * start of a frame ([decide]); a released predictive back decides as it is released ([pop]).
     */
    internal fun transforms(c: PageChange? = change): Boolean {
        if (c == null || c.motion !in CONTAINERS) return false
        if (c.predictive && !c.committed) return false
        return resolvedFor === c && resolved
    }

    /**
     * A container change whose clock has not decided yet (see [decide]): the page that was on screen holds. Under
     * reduced motion no clock runs and the change snaps.
     */
    private fun undecided(c: PageChange): Boolean =
        c.motion in CONTAINERS && !c.predictive && resolvedFor !== c && elapsed.isFinite()

    /** Whether the transform is still drawing: the upper page has not reached its end. */
    internal fun active(c: PageChange? = change): Boolean {
        c ?: return false
        val v = documentProgress()
        return if (c.push) v < 1f else v > 0f
    }

    /** No change is drawing: there is none, or its upper page has reached its end. */
    internal fun settled(): Boolean = change?.let { !active(it) } ?: true

    /** The note whose title is flying between its origin and its page: both hide their own. */
    internal fun flying(noteId: String): Boolean {
        val c = change ?: return false
        if (c.motion != PageMotion.Stage && c.motion != PageMotion.Row) return false
        if (noteIdOf(c.document) != noteId) return false
        if (c.predictive) return transforms(c) && active(c) && titleFlies(c)
        return flightReady && active(c)
    }

    private fun titleFlies(c: PageChange): Boolean {
        val origin = originOf(c)?.title?.takeIf { it.isAttached } ?: return false
        val page = titleOf(c)?.coordinates?.takeIf { it.isAttached } ?: return false
        if (origin.size.width <= 0 || page.size.width <= 0) return false
        // Some of it on screen: inside its frame, as far as the page's list shows it.
        val shown = inPage(c, page, clip = true) ?: return page.boundsInWindowOrEmpty().height > 0f
        val height = frames[c.document]?.size?.height?.toFloat() ?: return false
        return min(shown.bottom, height) - max(shown.top, 0f) > 0f
    }

    /** The page's title in the overlay's space, [c]'s page standing in place (see [inPage]). */
    internal fun pageTitleIn(c: PageChange, title: LayoutCoordinates, overlay: LayoutCoordinates): Rect =
        inPage(c, title, clip = false) ?: overlay.rectOf(title)

    /** p: 0 = the page, 1 = the origin; on a predictive commit, from the previewed page. */
    internal fun progress(c: PageChange): Float {
        val v = documentProgress()
        if (!c.predictive) return 1 - v
        if (commitAt <= 0f) return 1f
        val linear = (1 - v / commitAt).coerceIn(0f, 1f)
        return if (linear >= 1f) 1f else move(linear * FocalSettle.STAGE_MS)
    }

    /** The predictive preview's eased progress e: during the gesture, and frozen at its release. */
    internal fun preview(c: PageChange): Float {
        if (!c.predictive) return 0f
        val v = if (c.committed) commitAt else document?.value ?: 1f
        return BackEase.transform((1 - v).coerceIn(0f, 1f))
    }

    /** The material's alpha in a stage transform: it crossfades to paper over the first 60 % of the travel. */
    internal fun material(c: PageChange): Float = when {
        c.motion == PageMotion.Row -> 0f
        // Pulled, the card stays the card over the first quarter of the way: a short pull is a stretch, not a grey card.
        c.dragged -> smoothstep(PULLED_MATERIAL_FROM, PULLED_MATERIAL_TO, progress(c))
        else -> smoothstep(MATERIAL_FROM, 1f, progress(c))
    }

    internal fun reveal(destination: Destination): PageReveal = PageReveal(
        chrome = {
            val c = change
            when {
                c == null || c.document != destination || !transforms(c) -> 1f
                // A row's: opening, from +80 on `fade` (the meta and text wait for the title); folding, with the body.
                c.motion == PageMotion.Row -> when {
                    c.push -> rowChrome(c)
                    active(c) -> body(c)
                    else -> 1f
                }
                !active(c) -> 1f
                else -> chrome(c)
            }
        },
        title = {
            val id = noteIdOf(destination)
            if (id != null && flying(id)) 0f else 1f
        },
        body = {
            val c = change
            when {
                c == null || c.document != destination || !transforms(c) || c.motion == PageMotion.LiveStage -> 1f
                // Opening, the body waits for the title and may still be arriving once the clip has landed.
                active(c) || c.push -> body(c)
                else -> 1f
            }
        },
        develop = { if (developing == destination && !reduced) developClock.elapsed else Float.POSITIVE_INFINITY },
    )

    /**
     * The page's chrome (top bar, top and bottom fades) in a stage transform (Q7: it fades with the paper): it hands
     * over to the stage's own time, text and footer, which arrive from p 0.6 on a fold, so it is gone by then and
     * never drawn over them. Opening, it arrives while the material has all but gone.
     */
    private fun chrome(c: PageChange): Float =
        if (c.dragged) 1 - smoothstep(PULLED_CHROME_FROM, PULLED_CHROME_TO, pageTravel(c))
        else 1 - smoothstep(MATERIAL_FROM, CONTENT_FROM, progress(c))

    /**
     * A page opening out of a row: its chrome (top bar, fades) has no material to hand over from, so it arrives on
     * `fade` from +80 (spec 6.1, T16), while its meta and text wait for the flying title ([body]).
     */
    private fun rowChrome(c: PageChange): Float {
        if (!elapsed.isFinite()) return 1f
        val from = c.start.body
        return from + (1 - from) * fade(elapsed - BODY_DELAY_MS)
    }

    /**
     * The page's meta and text in a transform (spec 6.1): opening, they arrive whole on `fade` once the flying title is
     * within [BODY_LANDING] of its line (T16, T16′; [bodyFrom]), or from +80 when no title flies; folding, they leave on
     * `fadeFast`, and folding into a row they fade through with the returning rows ([rows]). Two typesettings are
     * never drawn over each other, and no paragraph shows in part below a title still in flight.
     */
    private fun body(c: PageChange): Float {
        val from = c.start.body
        return when {
            c.predictive -> predictiveBody.value
            // Pulled: with the travel, over the last part of the page's own way, under the title that rises with it.
            c.dragged -> 1 - smoothstep(PULLED_BODY_FROM, PULLED_BODY_TO, pageTravel(c))
            !c.push && c.motion == PageMotion.Row -> from * (1 - smoothstep(0f, HALF, fadeFast(elapsed)))
            !c.push -> from * (1 - fadeFast(elapsed))
            c.motion == PageMotion.Stage || c.motion == PageMotion.Row -> {
                val at = when {
                    !elapsed.isFinite() -> return 1f
                    !bodyFrom.isNaN() -> bodyFrom
                    // The title flies: the body waits for it.
                    !titleFrom.isNaN() -> return from
                    // No title has flown by now (the page's title was never laid out): the spec's +80.
                    elapsed > TITLE_WAIT_MS -> TITLE_WAIT_MS
                    else -> return from
                }
                from + (1 - from) * fade(elapsed - at)
            }
            else -> 1f
        }
    }

    /**
     * T16: the other rows around a row that opens leave on `fadeFast` (spec 6.1); on Back they return as the page's
     * text leaves, a fade-through on the same `fadeFast`.
     */
    private fun rows(c: PageChange): Float {
        val from = c.start.rows
        if (c.push) return from * (1 - fadeFast(elapsed))
        // They wait for as much of the page's text as was there: none when Back turned an opening around before its
        // text arrived, so the library never blanks between a page that showed nothing and the rows.
        return from + (1 - from) * smoothstep(HALF * c.start.body, 1f, fadeFast(elapsed))
    }

    /** The origin's own content as the page opens out of it (T16′, T7b): the time, text and footer leave on `fadeFast`. */
    internal fun content(c: PageChange): Float = when {
        !c.dragged -> c.start.content * (1 - fadeFast(elapsed))
        // Pulled, it follows the travel: whole over the first fifth of the way, gone before the material.
        else -> smoothstep(PULLED_CONTENT_FROM, PULLED_CONTENT_TO, progress(c))
    }

    /**
     * How far the page's text stays clear of the flying title (and, folding into a row, the rows around it):
     * [TITLE_CLEARANCE] while it is in flight, nothing as it lands on its own line, so no pixel of the meta below it
     * is held back once it has landed.
     */
    internal fun flightFeather(c: PageChange): Float {
        val p = titleProgress(c)
        val toLanding = if (c.push) p else 1 - p
        return with(density) { TITLE_CLEARANCE.toPx() } * toLanding.coerceIn(0f, 1f)
    }

    /** Reads what every frame of a change follows (the clock, or a predictive back's progress), so a reader redraws. */
    internal fun observe() {
        elapsed
        document?.value
    }

    /**
     * The upper page's progress v: from the clock, or a predictive back's NavDisplay progress (0 before a pushed page
     * is composed, and once a popped page has left).
     */
    private fun documentProgress(): Float {
        val c = change
        if (c != null && !c.predictive) return clockProgress(c)
        return document?.value ?: 0f
    }

    /** A popped page left: nothing is in flight any more. */
    internal fun left(destination: Destination) {
        val c = change ?: return
        if (c.document == destination && !c.push) change = null
    }

    /**
     * How one page draws itself in the current change (read in the draw phase), from the upper page's progress v.
     * [under]: as the change's other page, whichever it is (two panes: the library's pane under the licences).
     */
    internal fun look(destination: Destination, under: Boolean = false): PageLook {
        val c = change ?: return if (top == null || top == destination) PageLook.Shown else PageLook.Gone
        // A page that left before this change began, which NavDisplay still holds for a moment, is no part of it.
        if (!under && stray(c, destination)) return PageLook.Gone
        val v = documentProgress()
        val upper = destination == c.document
        if (c.predictive) {
            // The page under a back swipe shows while the swipe previews it: a swipe let go short leaves the page
            // over it whole again (nothing reports the cancel), and it covers what is under it once more.
            if (!upper) return if (c.committed || preview(c) > 0f) PageLook.Shown else PageLook.Gone
            if (!c.committed) return PageLook(preview = preview(c), edge = c.edge)
            if (transforms(c)) return if (active(c)) PageLook.Recorded else PageLook.Gone
            val fraction = if (commitAt <= 0f) 0f else v / commitAt
            return PageLook(alpha = smoothstep(0f, 1f, fraction), preview = preview(c), edge = c.edge)
        }
        // T7: the scene covers the change; the page below leaves at once (pages are transparent sheets on one paper).
        if (c.motion == PageMotion.Cut) return if (upper) PageLook.Shown else PageLook.Gone
        if (undecided(c)) return if (upper == !c.push) PageLook.Shown else PageLook.Gone
        if (transforms(c)) {
            // The origin went away under a transform (the display let its page go): the page is drawn as it is
            // rather than through an overlay that has nothing left to draw it into.
            if (active(c) && originCard(c) == null) return if (upper) PageLook.Shown else PageLook.Gone
            if (!upper) {
                val alpha = when {
                    // The page has arrived: NavDisplay may keep the one below for a frame more; it is not drawn.
                    c.push && !active(c) -> 0f
                    c.motion != PageMotion.Row -> 1f
                    else -> rows(c)
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

    /** [destination] is neither of [c]'s pages: it left before [c] began. */
    private fun stray(c: PageChange, destination: Destination): Boolean =
        c.other != null && destination != c.document && destination != c.other

    /**
     * Whether [destination]'s page is on its way out: the page a change folds or fades away (from the moment Back is
     * let go), or one that left before this change began. Nothing on it can be pressed; while it still stands in
     * place ([aside]) it stops every touch, so nothing under it is pressed unseen either.
     */
    internal fun leaving(destination: Destination): Boolean {
        val c = change ?: return false
        if (stray(c, destination)) return true
        if (destination == c.document && returning(c)) return true
        return destination == c.document && !c.push && (!c.predictive || c.committed)
    }

    /**
     * Whether [destination]'s page takes no touch now: it is [leaving], or it is still opening out of the stage or a
     * row, held by a finger or on its way, where what is drawn is the overlay's card and the page's controls are not
     * where they will be. A second tap there presses nothing (the library under it is covered, and takes none either).
     */
    internal fun blocks(destination: Destination): Boolean {
        if (leaving(destination)) return true
        val c = change ?: return false
        return c.push && destination == c.document && c.motion in CONTAINERS && (pulling(c) || documentProgress() < LANDED)
    }

    /**
     * Whether [destination]'s page, drawn this frame as [look], stands aside, where it takes no touch and the page
     * under it takes them: a page that is not drawn, once its change no longer reads where it stands (a transform
     * reads its origin on the page below) or as soon as it is leaving; and a page that leaves through a transform
     * (drawn by the overlay from its recording) from the moment it is leaving, so a tap on the library during the fold
     * reaches the row it was meant for (the page it opens waits for the fold, [queued]). The overlay finds the page's
     * title in the page's own frame ([frames]), so a page aside still folds onto its own lines.
     */
    internal fun aside(destination: Destination, look: PageLook): Boolean {
        val c = change ?: return look.alpha <= 0f
        return when {
            look.recorded -> leaving(destination)
            look.alpha <= 0f -> leaving(destination) || settled()
            else -> false
        }
    }

    /** Where each composed page's frame is laid out: the transforms find the page's title in it. */
    internal val frames = HashMap<Destination, LayoutCoordinates>()

    /**
     * [title], a part of [c]'s page, in the page's own frame: the frame stands where the overlay does (one pane, the
     * only layout with transforms) unless it is aside. [clip]: only what the page's list shows of it.
     */
    private fun inPage(c: PageChange, title: LayoutCoordinates, clip: Boolean): Rect? =
        frames[c.document]?.takeIf { it.isAttached }?.localBoundingBoxOf(title, clipBounds = clip)

    /** A predictive back released past its threshold: the page's meta and text leave on `fadeFast` as it folds. */
    internal suspend fun play(c: PageChange) {
        if (c.motion in CONTAINERS && c.predictive && c.committed) predictiveBody.animateTo(0f, FocalMotion.fadeFast())
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
        /** T7, T7b: the develop runs until the last staggered paragraph has arrived (spec 2.8.2). */
        val DEVELOP_MS = FocalTiming.SETTLE_PARAGRAPH_DELAY_MS + (FocalTiming.STAGGER_MAX - 1) * FocalTiming.RACK_STAGGER_MS +
            FocalSettle.FADE_SLOW_MS
        /** NavDisplay keeps the outgoing page this much longer than the clock runs: it starts up to two frames late. */
        const val HOLD_MARGIN_MS = 3 * FocalTiming.TRIGGER_LEAD_MS.toInt()
        /** ... and this much longer through a transform, whose clock may start that long after the change. */
        const val READY_HOLD_MS = 600
        /** `bodyFadeDelay` (spec 2.8.3): the page's meta and text start to arrive this long after the trigger. */
        const val BODY_DELAY_MS = 80f
        /** How long a container change may wait for its two ends to be laid out before it fades through instead. */
        const val DECIDE_MS = 4f * FocalTiming.TRIGGER_LEAD_MS
        /** An opening waits at most this many frames for its page to be ready, then opens anyway. */
        const val READY_FRAMES = 45
        /** A page opened while another folds away waits for it, and the fold runs this many times as fast meanwhile. */
        const val HURRY = 4L
        /*
         * A pulled card turns into its page in overlapping steps, so that at no point of the way is it an empty slab
         * (a pull can stop anywhere): over p (1 at the stage, 0 at the page) its own time, text and footer leave from
         * 0.85 to 0.65 and its material turns to paper from 0.8 to 0.45; over the page's own way q its bar arrives
         * from 0.75 to 0.55 and its text from 0.65 to 0.35, under the title that rises all the way.
         */
        const val PULLED_CONTENT_TO = 0.85f
        const val PULLED_CONTENT_FROM = 0.65f
        const val PULLED_MATERIAL_TO = 0.8f
        const val PULLED_MATERIAL_FROM = 0.45f
        const val PULLED_CHROME_TO = 0.75f
        const val PULLED_CHROME_FROM = 0.55f
        const val PULLED_BODY_TO = 0.65f
        const val PULLED_BODY_FROM = 0.35f
        /** A card let go has landed at either end once this little of its way is left, and stays so (a pixel or two). */
        const val PULL_LANDED = 0.002f
        /** How far ahead a let-go card's spring is looked at to tell that it stays landed. */
        const val LANDED_PROBE_MS = 32f
        /** The least travel a pulled page's own way is counted from (see pageFromP). */
        const val MIN_PAGE_FROM = 0.05f
        /** A page that folds into its origin stands aside once this much of its way is done: the rest is a pixel or two. */
        const val LANDED = 0.995f
        /** T16, T16′: the soft edge between the flying title and the text it has passed, while it is in flight. */
        val TITLE_CLEARANCE = 16.dp
        /** T16, T16′: the page's meta and text arrive once the flying title is this close to its own line. */
        val BODY_LANDING = 16.dp
        /** Opening, how long the body waits for a page title that is never laid out before it arrives anyway. */
        const val TITLE_WAIT_MS = DECIDE_MS + BODY_DELAY_MS
        /** A clock kept running for an arriving body stops by then whatever happens. */
        const val BODY_LIMIT_MS = 1_500f
        private const val NANOS_PER_MS = 1_000_000L
        const val MATERIAL_FROM = 0.4f
        /** The stage's time, text and footer arrive over the last 40 % of a fold (`smoothstep(0.6, 1, q)`). */
        const val CONTENT_FROM = 0.6f
        const val HALF = 0.5f
        const val PREVIEW_SCALE = 0.1f
        const val SHIFT_DIVISOR = 20f
        val ShiftInset = 8.dp
    }
}

/**
 * A pulled card let go at travel [from] (0 the stage, 1 the page), moving at [velocity] (travel per second): it goes on
 * to [to] on `stage` from that speed.
 */
private class LetGo(val from: Float, val to: Float, val velocity: Float) {
    /** The frame time of the first frame after the finger left (unset until then). */
    var at = Long.MIN_VALUE

    fun travel(ms: Float): Float =
        FocalMotion.springTo(FocalMotion.SPATIAL_DAMPING, FocalMotion.STAGE_STIFFNESS, from, to, velocity, ms)

    /** Within a pixel or two of [to], and staying there (or past any time it could take). */
    fun landed(ms: Float): Boolean = ms >= 2 * FocalSettle.STAGE_MS ||
        (abs(travel(ms) - to) <= PageTransitions.PULL_LANDED && abs(travel(ms + PageTransitions.LANDED_PROBE_MS) - to) <= PageTransitions.PULL_LANDED)
}

/** The clock time at which a page's travel has covered [v] of its way: [move]'s inverse, by bisection. */
private fun stageTime(v: Float): Float {
    var low = 0f
    var high = FocalSettle.STAGE_MS.toFloat()
    repeat(STAGE_TIME_STEPS) {
        val mid = (low + high) / 2
        if (move(mid) < v) low = mid else high = mid
    }
    return (low + high) / 2
}

private const val STAGE_TIME_STEPS = 16

/** A page's travel between its origin and the window: the whole window moves, so it is the slow spatial spring. */
private fun move(ms: Float): Float = FocalClock.stage(ms)
private fun fade(ms: Float): Float = FocalClock.fade(ms)
private fun fadeFast(ms: Float): Float = FocalClock.fadeFast(ms)

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
internal fun PageFrame(
    destination: Destination,
    pages: PageTransitions,
    background: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
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
    val placed = remember { PlacedFrame() }
    DisposableEffect(pages, destination) {
        onDispose {
            placed.coordinates?.let { if (pages.frames[destination] === it) pages.frames.remove(destination) }
            pages.disposed(destination)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val look = pages.look(destination)
                alpha = if (look.recorded) 1f else look.alpha
                // A page that is not drawn, or one leaving that has all but landed, stands aside: one kept composed a
                // moment longer never swallows a tap meant for the page under it (PageTransitions.aside).
                val aside = pages.aside(destination, look)
                val e = if (look.recorded) 0f else look.preview
                scaleX = previewScale(e)
                scaleY = scaleX
                translationX = if (aside) ASIDE * size.width else previewShift(e, size.width, look.edge)
                shape = if (e > 0f) RoundedCornerShape(FocalShapes.sheetRadius * e) else androidx.compose.ui.graphics.RectangleShape
                clip = e > 0f
            }
            // Inside the layer: the frame's own space, where the transforms find the page's title even while it is aside.
            .onPlaced {
                placed.coordinates = it
                pages.frames[destination] = it
            }
            // A page on its way out or still on its way in presses nothing, and while it stands in place nothing under
            // it either.
            .pointerInput(pages, destination) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (pages.blocks(destination)) event.changes.forEach { it.consume() }
                    }
                }
            }
            .drawWithContent {
                val look = pages.look(destination)
                if (look.recorded) layer.record { this@drawWithContent.drawContent() }
                else if (look.alpha > 0f) {
                    // A previewed page is a sheet of paper over the page it returns to.
                    if (look.preview > 0f) drawRect(paper)
                    drawContent()
                }
            }
            // Its own paper, if it brings one, is part of the page: it fades and moves with it.
            .then(if (background.isSpecified) Modifier.background(background) else Modifier),
    ) {
        CompositionLocalProvider(LocalPageReveal provides reveal) { content() }
    }
}

/**
 * The library's page in its own pane (FocalShell's LibraryPane). In one pane it is the page under every change, drawn
 * from [PageTransitions.look] like any page below; with no change in flight it is shown unless a page [covered] it.
 * It never stands aside: whatever covers it is drawn in place and takes the touches, and the library's own places
 * (the stage's bounds the scene grows out of, the rows a page folds into) stay where they are on screen. In two panes
 * only the licences cover it, and it fades through under them.
 */
@Composable
internal fun LibraryFrame(
    pages: PageTransitions,
    twoPane: Boolean,
    covered: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val change = pages.change
                if (twoPane) {
                    alpha = if (change?.document == Destination.Licenses) pages.look(Destination.Library, under = true).alpha else 1f
                    return@graphicsLayer
                }
                val look = when {
                    change != null -> pages.look(Destination.Library)
                    covered() -> PageLook.Gone
                    else -> PageLook.Shown
                }
                alpha = look.alpha
            }
            .then(modifier),
    ) { content() }
}

/** A page that is not drawn is placed this many widths aside (see [PageFrame]). */
private const val ASIDE = 2f

/** Where one page's frame was laid out, so it can unregister only itself. */
private class PlacedFrame { var coordinates: LayoutCoordinates? = null }

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
 *
 * Where the clip's edge runs through the page's text on paper, the text fades out toward it (the page's own bottom
 * fade, riding the edge) instead of being cut mid-glyph; where the flying title has still to pass, neither the page's
 * text nor (folding into a row) the library's rows are drawn under it. Once the clip's bottom has reached the
 * library's bottom fade, paper covers the library under it, so the page and its fade end on paper above the dock and
 * no row of the library shows between them ([bottom] says where that fade is).
 */
@Composable
internal fun PageTransformOverlay(pages: PageTransitions, live: LiveSurfaceState, bottom: BottomColumnState, modifier: Modifier = Modifier) {
    // Always laid out, so its place in the window is known before a transform's first frame.
    val placement = remember { OverlayPlacement() }
    Box(modifier.fillMaxSize().onPlaced { placement.coordinates = it }) {
        val change = pages.change
        if (change != null && change.motion in PageTransitions.CONTAINERS) TransformLayers(pages, live, bottom, change, placement)
    }
}

@Composable
private fun TransformLayers(pages: PageTransitions, live: LiveSurfaceState, bottom: BottomColumnState, change: PageChange, placement: OverlayPlacement) {
    LaunchedEffect(change) { pages.play(change) }
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val frame: () -> TransformFrame? = {
        // Read the progress first: every layout, layer and draw lambda below follows it, whatever returns early.
        pages.observe()
        val coordinates = placement.coordinates
        if (coordinates == null || !coordinates.isAttached || !pages.transforms(change) || !pages.active(change)) null
        else pages.frameIn(change, coordinates, density)
    }
    // The title in flight this frame, laid out at its interpolated size (the measurer caches it for the second read).
    val titleInFlight: DrawScope.(TransformFrame) -> FlightText? = { f ->
        val id = (change.document as? Destination.Note)?.id
        val coordinates = placement.coordinates
        if (id == null || coordinates == null || !pages.flying(id)) null
        else pages.flight(change, f, coordinates, this, colors)?.let { title ->
            // At its box's whole width, as both ends set it: the text aligns in its own direction inside the box.
            val width = max(1, title.width.roundToInt())
            val layout = measurer.measure(
                title.text, title.style, overflow = TextOverflow.Ellipsis, maxLines = title.lines,
                constraints = Constraints(minWidth = width, maxWidth = width), density = this, layoutDirection = layoutDirection,
            )
            FlightText(title, layout)
        }
    }
    val stageLike = change.motion != PageMotion.Row
    // Paper over the library under the clip, once the clip's bottom has reached the library's bottom fade.
    if (stageLike) Spacer(
        Modifier.fillMaxSize().drawBehind {
            val f = frame() ?: return@drawBehind
            val fadeTop = bottom.fadeEnd(this) - bottom.fadeHeight(this)
            if (fadeTop.isNaN() || f.clip.bottom >= size.height) return@drawBehind
            val cover = smoothstep(fadeTop - FocalLayout.bottomFade.toPx(), fadeTop, f.clip.bottom)
            if (cover > 0f) drawRect(colors.paper, Offset(0f, f.clip.bottom), Size(size.width, size.height - f.clip.bottom), alpha = cover)
        },
    )
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
        // The page at its own layout (its preview's transform after a predictive release), its text faded toward the
        // clip's edges and, opening, not yet shown where the flying title has still to pass (from its landing line
        // down to the title), so it comes in behind a title that rises from a row and is never drawn under it.
        // Folding into a row, the returning rows stay under paper where the title has still to pass on its way down.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val f = frame() ?: return@drawBehind
                val layer = pages.layer ?: return@drawBehind
                val title = titleInFlight(f)
                val feather = pages.flightFeather(change)
                if (title != null && !stageLike && !change.push) {
                    titleAhead(title, feather)?.let { ahead -> drawRect(ahead.brush(colors.paper), ahead.topLeft(), ahead.size(size.width)) }
                    placement.coordinates?.let { overlay -> coverLabelsAbove(pages, overlay, title, colors.paper) }
                }
                val wake = if (change.push) title?.let { titleWake(it, feather) } else null
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                    if (f.preview <= 0f) drawLayer(layer) else {
                        val scale = previewScale(f.preview)
                        val shift = previewShift(f.preview, size.width, f.edge)
                        withTransform({
                            translate(shift, 0f)
                            scale(scale, scale, pivot = Offset(size.width / 2, size.height / 2))
                        }) { drawLayer(layer) }
                    }
                    clearEdges(f, soft = if (stageLike) ClipEdges.Bottom else ClipEdges.Both)
                    wake?.let { drawRect(it.brush(Color.Black), it.topLeft(), it.size(size.width), blendMode = BlendMode.DstOut) }
                    canvas.restore()
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
                    // The title flies at its own pace and the content rides the clip: the stage's text waits until
                    // the title has cleared its line, so the shrinking title never touches it (T16′).
                    val text = titleInFlight(f)?.let { stageTextHeld(pages.stage, it, f) }
                    if (text == null) {
                        translate(f.clip.left, f.clip.top) { drawLayer(layer) }
                        return@drawBehind
                    }
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                        translate(f.clip.left, f.clip.top) { drawLayer(layer) }
                        drawRect(Color.Black, text.first.topLeft, text.first.size, alpha = text.second, blendMode = BlendMode.DstOut)
                        canvas.restore()
                    }
                },
        )
        // The title, a shared element between the origin and the page.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val f = frame() ?: return@drawBehind
                val title = titleInFlight(f) ?: return@drawBehind
                drawText(title.layout, color = title.flight.color, topLeft = title.flight.topLeft)
            },
        )
    }
}

private val RingWidth = 1.dp

/** Which of the clip's edges fade the page's text out toward them. */
private enum class ClipEdges { Bottom, Both }

/**
 * Fades the page's text out toward the clip's bottom edge over the bottom fade's height (and toward its top edge over
 * the top fade's, for a row, whose clip's top runs through the page): the paper the page is drawn on meets paper
 * outside the clip, so a hard edge would cut glyphs in two. At the window's edges the page's own fades are there.
 */
private fun DrawScope.clearEdges(f: TransformFrame, soft: ClipEdges) {
    val bottomFade = FocalLayout.bottomFade.toPx()
    if (f.clip.bottom < size.height) {
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = f.clip.bottom - bottomFade, endY = f.clip.bottom),
            topLeft = Offset(0f, f.clip.bottom - bottomFade), size = Size(size.width, bottomFade), blendMode = BlendMode.DstOut,
        )
    }
    val topFade = FocalLayout.topFade.toPx()
    if (soft == ClipEdges.Both && f.clip.top > 0f) {
        drawRect(
            Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = f.clip.top, endY = f.clip.top + topFade),
            topLeft = Offset(0f, f.clip.top), size = Size(size.width, topFade), blendMode = BlendMode.DstOut,
        )
    }
}

/** The flying title laid out for this frame. */
private class FlightText(val flight: Flight, val layout: TextLayoutResult)

/**
 * A span of the page or the library the flying title has still to pass, from [top] to [bottom]: solid, with [feather]
 * of soft edge on the side the title has already left ([softBelow]: below it while it rises, above it while it sinks).
 */
private class TitlePath(val top: Float, val bottom: Float, val feather: Float, val softBelow: Boolean) {
    fun topLeft() = Offset(0f, if (softBelow) top else top - feather)
    fun size(width: Float) = Size(width, bottom - top + feather)
    fun brush(color: Color): Brush {
        val total = bottom - top + feather
        if (feather <= 0f || total <= 0f) return SolidColor(color)
        val edge = feather / total
        return if (softBelow) {
            Brush.verticalGradient(0f to color, 1 - edge to color, 1f to color.copy(alpha = 0f), startY = top, endY = bottom + feather)
        } else {
            Brush.verticalGradient(0f to color.copy(alpha = 0f), edge to color, 1f to color, startY = top - feather, endY = bottom)
        }
    }
}

/**
 * Folding into a row: a day label just above the sinking title heads a row still under paper, so it stays under
 * paper too, and returns as the title leaves it ([LABEL_CLEARANCE] below its bottom), never sitting on the title.
 */
private fun DrawScope.coverLabelsAbove(pages: PageTransitions, overlay: LayoutCoordinates, title: FlightText, paper: Color) {
    val titleTop = title.flight.topLeft.y
    val clearance = LABEL_CLEARANCE.toPx()
    val gone = ArrayList<String>()
    for ((key, label) in pages.labels) {
        if (!label.isAttached) {
            gone += key
            continue
        }
        val r = overlay.rectOf(label)
        if (r.top >= titleTop || r.bottom <= titleTop - clearance) continue
        val cover = 1 - smoothstep(0f, clearance, titleTop - r.bottom)
        drawRect(paper, r.topLeft, Size(r.width, min(r.bottom, titleTop) - r.top), alpha = cover)
    }
    gone.forEach { pages.labels.remove(it) }
}

/** A day label returns once the folding title is this far below it. */
private val LABEL_CLEARANCE = 24.dp

/** Reports where a library day label sits, for T16's fold (see [coverLabelsAbove]). */
internal fun Modifier.foldLabel(pages: PageTransitions, key: String): Modifier = onPlaced { pages.labels[key] = it }

/**
 * T16′: where the stage's text is drawn this frame (it rides the clip) and how much of it the flying title holds
 * back (0 to 1), or null when none. The text waits while the title's last line box reaches into its first (their
 * glyphs would touch) and fades in as the boxes part by [STAGE_CLEARANCE], when the title's descenders are some 7 dp
 * above the text's capitals (at the stage the boxes are 8 dp apart). The whole text waits, never a slice of a line;
 * the time and the footer arrive on their own.
 */
private fun DrawScope.stageTextHeld(stage: Origin, title: FlightText, f: TransformFrame): Pair<Rect, Float>? {
    val card = stage.card?.takeIf { it.isAttached } ?: return null
    val text = stage.text?.takeIf { it.isAttached } ?: return null
    val layout = title.layout
    if (layout.lineCount == 0) return null
    val local = card.localBoundingBoxOf(text, clipBounds = false)
    val rect = local.translate(f.clip.topLeft)
    val titleBottom = title.flight.topLeft.y + layout.getLineBottom(layout.lineCount - 1)
    val clearance = STAGE_CLEARANCE.toPx()
    val held = 1 - ((rect.top - titleBottom) / clearance).coerceIn(0f, 1f)
    return if (held <= 0f) null else rect to held
}

/** Over how much of a gap below the flying title's last line the stage's text fades in (T16′). */
private val STAGE_CLEARANCE = 4.dp

/** Opening: the page's text from its title's line down to the rising title. */
private fun titleWake(title: FlightText, feather: Float): TitlePath? {
    val top = title.flight.pageTop ?: return null
    val bottom = title.flight.topLeft.y + title.layout.size.height
    return if (bottom <= top) null else TitlePath(top, bottom, feather, softBelow = true)
}

/**
 * Folding into a row: the rows from the sinking title down to the line it lands on. The row it lands in is not
 * ahead of it: the title stops on its own line, beside the duration and above the preview, so that row returns
 * whole (its duration with its preview) with the other rows' `fadeFast`.
 */
private fun titleAhead(title: FlightText, feather: Float): TitlePath? {
    val top = title.flight.topLeft.y
    val bottom = title.flight.originTop
    return if (bottom <= top) null else TitlePath(top, bottom, feather, softBelow = false)
}

/** Where the overlay sits in the window. */
private class OverlayPlacement {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

/**
 * The flying title in one frame. [pageTop] is the top of the page's own title, where the page's text begins (null
 * while the page has not laid it out: the title still holds at its origin); [originTop] the top of the title at its
 * origin, the line a folding title lands on.
 */
private class Flight(
    val text: String,
    val style: TextStyle,
    val color: Color,
    val topLeft: Offset,
    val width: Float,
    val lines: Int,
    val pageTop: Float?,
    val originTop: Float,
)

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
        c.push -> content(c)
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
    val row = c.motion == PageMotion.Row
    val originStyle = if (row) FocalType.rowTitle else FocalType.headline
    val from = overlay.rectOf(originTitle)
    val pageInkFor = { muted: Boolean -> if (muted) colors.recede else colors.ink }
    val originInkFor = { muted: Boolean ->
        when {
            row -> pageInkFor(muted)
            muted -> colors.stageRecede
            else -> colors.stageInk
        }
    }
    val pageTitle = titleOf(c)
    val pageCoordinates = pageTitle?.coordinates?.takeIf { it.isAttached }
    val p = titleProgress(c)
    // A pulled card's title rides the card with the rest of its content, and flies to the page from there.
    val ride = if (c.dragged) f.clip.topLeft - f.origin.topLeft else Offset.Zero
    // Opening, before the page has laid out its title: the title holds where the origin set it.
    if (pageTitle == null || pageCoordinates == null || p >= 1f) {
        // Its ink follows what is under it: the material while it lasts, then paper.
        val muted = originSlot.titleMuted
        return Flight(
            text = originSlot.titleText, style = originStyle.copy(lineBreak = LineBreak.Heading),
            color = lerp(pageInkFor(muted), originInkFor(muted), if (row) 1f else f.material),
            topLeft = from.topLeft + ride, width = from.width, lines = originSlot.titleLines, pageTop = null, originTop = from.top,
        )
    }
    var to = pageTitleIn(c, pageCoordinates, overlay)
    val scale = previewScale(f.preview)
    if (f.preview > 0f) with(density) {
        val shift = previewShift(f.preview, overlay.size.width.toFloat(), f.edge)
        val cx = overlay.size.width / 2f
        val cy = overlay.size.height / 2f
        to = Rect(cx + (to.left - cx) * scale + shift, cy + (to.top - cy) * scale, cx + (to.right - cx) * scale + shift, cy + (to.bottom - cy) * scale)
    }
    val pageStyle = FocalType.title
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
    return Flight(
        text = pageTitle.text,
        style = style,
        color = lerp(pageInkFor(muted), originInkFor(muted), if (row) p else f.material),
        topLeft = lerp(to.topLeft, from.topLeft + ride, p),
        width = lerp(to.width, from.width, p),
        lines = if (p < PageTransitions.HALF) pageTitle.lines else originSlot.titleLines,
        pageTop = to.top,
        originTop = from.top,
    )
}

/** Reports where the stage or a row (its card, or its [title]) sits, for the transforms that open its note. */
internal fun Modifier.pageOrigin(origin: Origin, title: Boolean = false): Modifier = onPlaced {
    if (title) origin.title = it else origin.card = it
}

/** Reports where the stage's text sits, which waits for a flying title to clear it (T16′). */
internal fun Modifier.stageText(origin: Origin): Modifier = onPlaced { origin.text = it }

/**
 * The stage's origin (one per library): which note it holds, its title and how many lines that may take, and the
 * dock its note's page will show.
 */
@Composable
internal fun rememberStageOrigin(noteId: String?, titleLines: Int, titleText: String, titleMuted: Boolean, dock: DockState?): Origin {
    val origin = LocalShell.current.pages.stage
    SideEffect {
        origin.noteId = noteId
        origin.titleLines = titleLines
        origin.titleText = titleText
        origin.titleMuted = titleMuted
        origin.dock = dock
    }
    return origin
}

/** A row's origin, registered while the row is composed. */
@Composable
internal fun rememberRowOrigin(noteId: String, titleLines: Int, titleText: String, titleMuted: Boolean, dock: DockState?): Origin {
    val pages = LocalShell.current.pages
    val origin = remember(noteId) { Origin().also { it.noteId = noteId } }
    SideEffect {
        origin.titleLines = titleLines
        origin.titleText = titleText
        origin.titleMuted = titleMuted
        origin.dock = dock
    }
    DisposableEffect(pages, noteId, origin) {
        pages.rows[noteId] = origin
        onDispose { if (pages.rows[noteId] === origin) pages.rows.remove(noteId) }
    }
    return origin
}

/** The page's title as the flight's landing place: its text, ink and how many lines it may take. */
@Composable
internal fun rememberPageTitle(noteId: String, text: String, muted: Boolean, lines: Int): PageTitle {
    val pages = LocalShell.current.pages
    // One per note's page: a page still on its way out that composes again never writes over the one opening.
    val title = remember(pages, noteId) { PageTitle() }
    SideEffect {
        title.noteId = noteId
        title.text = text
        title.muted = muted
        title.lines = lines
        pages.pageTitles[noteId] = title
    }
    DisposableEffect(pages, noteId, title) {
        onDispose { if (pages.pageTitles[noteId] === title) pages.pageTitles.remove(noteId) }
    }
    return title
}

/** Reports where the page's title sits. */
internal fun Modifier.pageTitle(title: PageTitle): Modifier = onPlaced { title.coordinates = it }
