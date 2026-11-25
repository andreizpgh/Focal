package app.focal.ui.shell

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import androidx.navigationevent.NavigationEvent
import app.focal.ui.components.DockPace
import app.focal.ui.design.FocalColors
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page rules that hold without a frame clock (reduced motion: every change has arrived at once). */
class PageTransitionsTest {
    private val pages = PageTransitions(CoroutineScope(Dispatchers.Unconfined)).apply { reduced = true }
    private val a = Destination.Note("a")
    private val b = Destination.Note("b")

    @Test fun aPageThatLeftBeforeTheNextChangeIsNeitherDrawnNorTouched() {
        pages.push(a, PageMotion.Row, other = Destination.Library)
        pages.pop(a, other = Destination.Library)
        pages.push(b, PageMotion.Row, other = Destination.Library)
        // Back from a, then a tap on b while NavDisplay still holds a: a is no part of b's change.
        assertEquals(PageLook.Gone, pages.look(a))
        assertTrue(pages.leaving(a))
        assertTrue(pages.blocks(a))
        assertEquals(PageLook.Shown, pages.look(b))
        assertFalse(pages.leaving(b))
        assertFalse(pages.blocks(b))
    }

    @Test fun withNoChangeOnlyTheTopPageIsDrawn() {
        pages.push(a, PageMotion.Row, other = Destination.Library)
        pages.pop(a, other = Destination.Library)
        pages.left(a)
        assertEquals(PageLook.Gone, pages.look(a))
        assertEquals(PageLook.Shown, pages.look(Destination.Library))
    }

    @Test fun anArrivedPageTakesTouches() {
        pages.push(a, PageMotion.Stage, other = Destination.Library)
        assertFalse(pages.blocks(a))
        assertEquals(null, pages.provisional())
    }

    @Test fun secondaryRowUsesVerticalPaperUnfoldAndLiveStageKeepsPurePaper() {
        assertTrue(PageMotion.Row in PageTransitions.CONTAINERS)
        assertTrue(PageMotion.Stage in PageTransitions.CONTAINERS)
        assertTrue(PageMotion.LiveStage in PageTransitions.CONTAINERS)
        val rowOpen = PageChange(PageMotion.Row, a, push = true, other = Destination.Library)
        val rowBack = PageChange(PageMotion.Row, a, push = false, other = Destination.Library)
        val liveFinish = PageChange(PageMotion.LiveStage, a, push = true, other = Destination.Library)
        assertEquals(0f, pages.material(rowOpen), 0.0001f)
        assertEquals(0f, pages.material(rowBack), 0.0001f)
        assertEquals(0f, pages.material(liveFinish), 0.0001f)
        assertEquals(1f, pages.libraryScale(), 0.0001f)
    }

    @Test fun stageAndRowHoldsDockUntilTransformStartsAndExcludesLiveStageAndCut() {
        val clock = TestFrameClock()
        val livePages = PageTransitions(CoroutineScope(Job() + Dispatchers.Unconfined + clock)).apply { reduced = false }
        livePages.push(a, PageMotion.Row, other = Destination.Library)
        // Waiting on NotePage readiness: dock is held even before a frame ticks.
        assertTrue(livePages.holdsDock(a))
        assertNull(livePages.dockPace(a))

        // LiveStage and Cut never hold the dock or override its Tap pace (T7 / T7b preserved).
        livePages.push(b, PageMotion.LiveStage, other = Destination.Library)
        assertFalse(livePages.holdsDock(b))
        assertNull(livePages.dockPace(b))

        livePages.push(b, PageMotion.Cut, other = Destination.Library)
        assertFalse(livePages.holdsDock(b))
        assertNull(livePages.dockPace(b))
    }

    @Test fun predictivePreviewLeavesDockUntouchedAndCommitDrivesPagePace() {
        val clock = TestFrameClock()
        val livePages = PageTransitions(CoroutineScope(Job() + Dispatchers.Unconfined + clock)).apply { reduced = false }
        attachStageOrigin(livePages, "a", a)

        // Open completes via clock frames so predictive back can start cleanly from rest.
        livePages.push(a, PageMotion.Stage, other = Destination.Library)
        advanceTransition(clock, startNanos = 1_000_000_000L, stepMs = 40L, count = 20)
        assertTrue(livePages.settled())

        val progress = mutableFloatStateOf(0.8f)
        livePages.document = progress
        livePages.beginPredictive(a, NavigationEvent.EDGE_LEFT, Destination.Library)
        val previewChange = checkNotNull(livePages.change)
        assertTrue(previewChange.predictive)
        assertFalse(previewChange.committed)
        assertFalse(livePages.holdsDock(Destination.Library))
        assertNull(livePages.dockPace(Destination.Library))
        assertNull(livePages.dockPace(a))

        // Releasing predictive back commits it and binds Library's dock 1:1 to normalized commit progress.
        livePages.pop(a, other = Destination.Library)
        val committedChange = checkNotNull(livePages.change)
        assertTrue(committedChange.predictive)
        assertTrue(committedChange.committed)
        val pace = livePages.dockPace(Destination.Library) as? DockPace.Page
        assertNotNull(pace)
        checkNotNull(pace)

        assertEquals(0f, pace.liveProgress(), 0.001f)
        progress.floatValue = 0.68f
        val early = pace.liveProgress()
        progress.floatValue = 0.4f
        val later = pace.liveProgress()
        assertTrue("Expected early progress strictly between 0 and later: early=$early later=$later", early > 0.05f && early < later && later < 1f)
        progress.floatValue = 0f
        assertEquals(1f, pace.liveProgress(), 0.001f)
    }

    @Test fun stageOpenDrivesDockPacePerFrameAndFastReversalHoldsLastProgress() {
        val clock = TestFrameClock()
        val livePages = PageTransitions(CoroutineScope(Job() + Dispatchers.Unconfined + clock)).apply { reduced = false }
        attachStageOrigin(livePages, "a", a)

        // Before readiness / title-layout wait frames complete: dock stays held on the previous screen's controls.
        livePages.push(a, PageMotion.Stage, other = Destination.Library)
        assertTrue(livePages.holdsDock(a))
        assertNull(livePages.dockPace(a))

        // Wait through TITLE_LAYOUT_FRAMES fallback so the Stage transform starts synchronously with the dock.
        var nowNanos = 1_000_000_000L
        repeat(PageTransitions.TITLE_LAYOUT_FRAMES + 1) {
            clock.step(nowNanos)
            nowNanos += 16_000_000L
        }
        assertFalse(livePages.holdsDock(a))
        val pushPace = livePages.dockPace(a) as? DockPace.Page
        assertNotNull(pushPace)
        checkNotNull(pushPace)

        clock.step(nowNanos + 48_000_000L)
        val midProgress = pushPace.liveProgress()
        assertTrue("Expected mid-flight progress, was $midProgress", midProgress > 0.05f && midProgress < 0.99f)

        // Pressing Back mid-flight creates a new serial; the superseded pace returns NaN rather than jumping to 1f.
        livePages.pop(a, other = Destination.Library)
        assertTrue(pushPace.liveProgress().isNaN())
    }

    @Test fun rowTapDuringReturningStageDragQueuesWithoutClearingReturnGesture() {
        val clock = TestFrameClock()
        val livePages = PageTransitions(CoroutineScope(Job() + Dispatchers.Unconfined + clock)).apply { reduced = false }
        attachStageOrigin(livePages, "a", a)
        livePages.rows["b"] = Origin().apply {
            card = FakeLayoutCoordinates(IntSize(400, 120), Offset(0f, 600f))
            noteId = "b"
        }

        assertTrue(livePages.beginDrag(a))
        livePages.drag(0.35f)
        clock.step(1_000_000_000L)
        livePages.endDrag(open = false, velocity = -2f) {}

        // Tapping row b while Stage a is returning must queue b and keep a's return gesture active.
        assertFalse(livePages.push(b, PageMotion.Row, other = Destination.Library))
        assertTrue(livePages.holdsDock(b))
        assertNull(livePages.dockPace(b))
        assertEquals(a, livePages.change?.document)

        // Once the quickened return finishes, b begins automatically.
        advanceTransition(clock, startNanos = 1_016_000_000L, stepMs = 16L, count = 30)
        assertEquals(b, livePages.change?.document)
    }

    private fun attachStageOrigin(pages: PageTransitions, noteId: String, destination: Destination) {
        pages.library = FakeLayoutCoordinates(IntSize(824, 1830))
        pages.stage.card = FakeLayoutCoordinates(IntSize(760, 260), Offset(32f, 240f))
        pages.stage.noteId = noteId
        pages.ready(destination)
    }

    private fun advanceTransition(clock: TestFrameClock, startNanos: Long, stepMs: Long, count: Int) {
        for (i in 0 until count) {
            clock.step(startNanos + i * stepMs * 1_000_000L)
        }
    }

    @Test fun edgeMaskChoosesOpaqueBackingWithStageTintOrBoundedSaveLayer() {
        assertTrue(PageEdgeMask.usesOpaqueEdgeFades(1f))
        assertTrue(PageEdgeMask.usesOpaqueEdgeFades(0.999f))
        assertFalse(PageEdgeMask.usesOpaqueEdgeFades(0.998f))

        val light = FocalColors.Light
        assertEquals(light.paper, PageEdgeMask.backingColor(light.paper, light.stageIdleSurface, isStage = false, material = 1f))
        assertEquals(light.paper, PageEdgeMask.backingColor(light.paper, light.stageIdleSurface, isStage = true, material = 0f))
        val tinted = PageEdgeMask.backingColor(light.paper, light.stageIdleSurface, isStage = true, material = 0.5f)
        assertNotEquals(light.paper, tinted)
        assertEquals(light.stageIdleSurface, PageEdgeMask.backingColor(light.paper, light.stageIdleSurface, isStage = true, material = 1f))

        val bounded = PageEdgeMask.boundedSaveLayerRect(
            clip = Rect(0f, 300f, 824f, 460f),
            feather = 48f,
            viewport = Size(824f, 1830f),
        )
        assertEquals(Rect(0f, 252f, 824f, 508f), bounded)
    }
}

private class TestFrameClock : MonotonicFrameClock {
    private val waiters = mutableListOf<Pair<(Long) -> Any?, Continuation<Any?>>>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            waiters += (onFrame as (Long) -> Any?) to (cont as Continuation<Any?>)
        }

    fun step(frameTimeNanos: Long) {
        val current = waiters.toList()
        waiters.clear()
        current.forEach { (block, cont) -> cont.resume(block(frameTimeNanos)) }
    }
}

private class FakeLayoutCoordinates(
    override val size: IntSize,
    private val origin: Offset = Offset.Zero,
    override var isAttached: Boolean = true,
) : LayoutCoordinates {
    override val providedAlignmentLines: Set<AlignmentLine> = emptySet()
    override val parentLayoutCoordinates: LayoutCoordinates? = null
    override val parentCoordinates: LayoutCoordinates? = null

    override fun windowToLocal(relativeToWindow: Offset): Offset = relativeToWindow - origin

    override fun localToWindow(relativeToLocal: Offset): Offset = relativeToLocal + origin

    override fun localToRoot(relativeToLocal: Offset): Offset = relativeToLocal + origin

    override fun localPositionOf(sourceCoordinates: LayoutCoordinates, relativeToSource: Offset): Offset =
        sourceCoordinates.localToWindow(relativeToSource) - origin

    override fun localBoundingBoxOf(sourceCoordinates: LayoutCoordinates, clipBounds: Boolean): Rect {
        val topLeft = localPositionOf(sourceCoordinates, Offset.Zero)
        return Rect(topLeft.x, topLeft.y, topLeft.x + sourceCoordinates.size.width, topLeft.y + sourceCoordinates.size.height)
    }

    override fun get(alignmentLine: AlignmentLine): Int = AlignmentLine.Unspecified
}
