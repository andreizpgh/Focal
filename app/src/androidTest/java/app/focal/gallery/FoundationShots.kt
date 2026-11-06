package app.focal.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.ui.components.BottomColumn
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FeedbackPill
import app.focal.ui.components.FixedMeter
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.FocalIconButton
import app.focal.ui.components.FocalMenuDivider
import app.focal.ui.components.FocalMenuItem
import app.focal.ui.components.FocalPrimaryPill
import app.focal.ui.components.FocalTextButton
import app.focal.ui.components.FeedbackHostState
import app.focal.ui.components.Glyph
import app.focal.ui.components.Mark
import app.focal.ui.components.MenuSurface
import app.focal.ui.components.NineBars
import app.focal.ui.components.NotePill
import app.focal.ui.components.PauseBadge
import app.focal.ui.components.PauseBadgeMetrics
import app.focal.ui.components.PlayCircle
import app.focal.ui.components.ProgressPhase
import app.focal.ui.components.RecordDot
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMode
import app.focal.ui.components.WaveRenderer
import app.focal.ui.components.markBox
import app.focal.ui.components.rememberBottomColumnState
import app.focal.ui.components.sceneMaterial
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.design.Scene
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.Stage
import kotlinx.coroutines.delay

/** The foundation stage's renders: tokens, every control state, the bars, the material, the shell's layers. */
object FoundationShots {
    val all: List<Shot> = buildList {
        addAll(themed("tokens-colour") { ColourSheet() })
        addAll(themed("tokens-type") { TypeSheet() })
        addAll(themed("controls-library") { LibraryControls() })
        addAll(themed("controls-note") { NoteControls() })
        add(Shot("controls-material", dark = false) { MaterialControls() })
        addAll(themed("waves") { WaveSheet() })
        add(Shot("scene-live", dark = false) { SceneMock(paused = false) })
        add(Shot("scene-paused", dark = false) { SceneMock(paused = true) })
        addAll(themed("empty") { EmptyMock() })
        add(Shot("ready-morph-light", dark = false, settleMs = READY_AT_MS + 80) { ReadyMorph() })
        add(Shot("copied-light", dark = false, settleMs = 300, click = COPY_TAG) { CopiedDock() })
        add(Shot("controls-library-light-200", dark = false, fontScale = 2f) { LibraryControls() })
        add(Shot("controls-note-light-200", dark = false, fontScale = 2f) { NoteControls() })
        add(Shot("empty-light-200", dark = false, fontScale = 2f) { EmptyMock() })
    }
}

private const val READY_AT_MS = 500L
private const val COPY_TAG = "copy"
private val noActions = DockActions()

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Grid.x24, vertical = Grid.x16),
        verticalArrangement = Arrangement.spacedBy(Grid.x12),
    ) { content() }
}

@Composable
private fun Head(text: String) {
    Text(text, style = FocalType.section, color = FocalTheme.colors.muted, modifier = Modifier.padding(top = Grid.x4))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourSheet() {
    val c = FocalTheme.colors
    Page {
        Head("Paper")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Grid.x12), verticalArrangement = Arrangement.spacedBy(Grid.x12)) {
            listOf(
                "paper" to c.paper, "ink" to c.ink, "muted" to c.muted, "faint" to c.faint, "line" to c.line,
                "s1" to c.s1, "s2" to c.s2, "lens" to c.lens, "select" to c.select, "inv" to c.inv, "onInv" to c.onInv,
                "raised" to c.raised, "press" to c.press, "bar top" to c.centreBarTop, "bar end" to c.centreBarBottom,
            ).forEach { (name, color) -> Swatch(name, color) }
        }
        Head("Scene and stage")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Grid.x12), verticalArrangement = Arrangement.spacedBy(Grid.x12)) {
            listOf(
                "top" to Scene.top, "mid" to Scene.mid, "bottom" to Scene.bottom, "ink" to Scene.ink, "hi" to Scene.hi,
                "muted" to Scene.muted, "stage" to Stage.muted, "glass" to Scene.glass,
            ).forEach { (name, color) -> Swatch(name, color, onMaterial = true) }
        }
        Head("Material, card size")
        Box(Modifier.fillMaxWidth().height(200.dp).sceneMaterial(FocalShapes.sheetRadius, ring = c.stageRing))
    }
}

@Composable
private fun Swatch(name: String, color: Color, onMaterial: Boolean = false) {
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp)
                .then(if (onMaterial) Modifier.sceneMaterial(FocalShapes.blockRadius) else Modifier)
                .border(1.dp, FocalTheme.colors.line, FocalShapes.block)
                .background(color, FocalShapes.block),
        )
        FakeCaption(name)
    }
}

@Composable
private fun TypeSheet() {
    val c = FocalTheme.colors
    Page {
        listOf(
            "title 28/34" to FocalType.title, "headline 22/28" to FocalType.headline, "body 18/28" to FocalType.body,
            "rowTitle 17/24" to FocalType.rowTitle, "field 17/24" to FocalType.field, "label 16/20" to FocalType.label,
            "action 15/20" to FocalType.action, "secondary 15/22" to FocalType.secondary, "meta 15/20" to FocalType.meta,
            "dateline 15/20" to FocalType.dateline, "section 14/20" to FocalType.section, "caption 13/18" to FocalType.caption,
        ).forEach { (name, style) ->
            Column {
                FakeCaption(name)
                Text("Speak, and it’s written down. 0123456789 Ёж", style = style, color = c.ink, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(220.dp).sceneMaterial(FocalShapes.sheetRadius, ring = c.stageRing).padding(Grid.x20)) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("12", style = FocalType.stageLength, color = Scene.ink)
                    Spacer(Modifier.width(5.dp))
                    Text("min", style = FocalType.meta.copy(fontWeight = FocalWeight.Medium), color = Stage.muted, modifier = Modifier.padding(bottom = 9.dp))
                    Spacer(Modifier.weight(1f))
                    Text("4:16", style = FocalType.sceneTimer.copy(fontSize = 60.sp, lineHeight = 66.sp), color = Scene.hi)
                }
                Text("If the forecast still says rain after lunch, we turn back.", style = FocalType.sceneText, color = Scene.ink)
            }
        }
    }
}

@Composable
private fun DockDemo(caption: String, state: DockState, actions: DockActions = noActions, edge: (() -> Float)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Grid.x4)) {
        FakeCaption(caption, Modifier.then(if (edge != null) Modifier.graphicsLayer { alpha = 0.9f } else Modifier))
        Dock(state, actions, materialEdge = edge)
    }
}

@Composable
private fun LibraryControls() {
    val feedback = remember { FeedbackHostState() }
    LaunchedEffect(feedback) { feedback.show("Note deleted", FeedbackAction("Undo", FocalIcon.Undo)) }
    Page {
        DockDemo("Library, empty", DockState.LibraryEmpty)
        DockDemo("Library", DockState.LibraryIdle)
        DockDemo("Recording (library dress)", DockState.Live(paused = false))
        DockDemo("Paused (library dress)", DockState.Live(paused = true))
        FakeCaption("Feedback")
        FeedbackPill(feedback)
        FakeCaption("Icon buttons, text button, primary pill")
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocalIconButton(FocalIcon.Back, "Back", {})
            FocalIconButton(FocalIcon.Edit, "Edit", {})
            FocalIconButton(FocalIcon.More, "More options", {})
            FocalIconButton(FocalIcon.Redo, "Redo", {}, enabled = false)
            Spacer(Modifier.width(Grid.x8))
            FocalTextButton("Not now", {})
        }
        FocalPrimaryPill("Allow microphone", {}, Modifier.fillMaxWidth(), leading = { RecordDot(it) })
        FakeCaption("Menu")
        MenuSurface {
            FocalMenuItem("Copy text", {})
            FocalMenuItem("Share", {})
            FocalMenuItem("Rename", {})
            FocalMenuDivider()
            FocalMenuItem("Remove audio", {}, trailing = "9 MB")
            FocalMenuItem("Delete", {})
        }
    }
}

@Composable
private fun NoteControls() {
    Page {
        DockDemo("Transcribing, 64 %", DockState.Note(PlayCircle.Play, NotePill.Progress(ProgressPhase.Transcribing, 0.64f)))
        DockDemo("Transcribing, 18 %", DockState.Note(PlayCircle.Play, NotePill.Progress(ProgressPhase.Transcribing, 0.18f)))
        DockDemo("Waiting", DockState.Note(PlayCircle.Play, NotePill.Progress(ProgressPhase.Waiting, 0.4f)))
        DockDemo("Preparing", DockState.Note(PlayCircle.Play, NotePill.Progress(ProgressPhase.Preparing, 0f)))
        DockDemo("Ready", DockState.Note(PlayCircle.Play, NotePill.Copy()))
        DockDemo("Playing", DockState.Note(PlayCircle.PausePlayback, NotePill.Copy()))
        DockDemo("Text only", DockState.Note(PlayCircle.None, NotePill.Copy()))
        DockDemo("No speech", DockState.Note(PlayCircle.Play, NotePill.Copy(enabled = false)))
        DockDemo("Recording elsewhere", DockState.Note(PlayCircle.Unavailable, NotePill.Copy()))
    }
}

@Composable
private fun MaterialControls() {
    var dockTop by remember { mutableFloatStateOf(0f) }
    val half = with(LocalDensity.current) { 28.dp.toPx() }
    Box(Modifier.fillMaxSize().sceneMaterial()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Grid.x24, vertical = Grid.x16),
            verticalArrangement = Arrangement.spacedBy(Grid.x24),
        ) {
            Text("Material dress", style = FocalType.section, color = Scene.muted)
            Dock(DockState.Live(paused = false), noActions, materialEdge = { Float.MAX_VALUE })
            Dock(DockState.Live(paused = true), noActions, materialEdge = { Float.MAX_VALUE })
            Text("The wipe at the material's edge (half way)", style = FocalType.section, color = Scene.muted)
            Box(Modifier.onGloballyPositioned { dockTop = it.positionInWindow().y }) {
                Dock(DockState.Live(paused = false), noActions, materialEdge = { dockTop + half })
            }
            Box(Modifier.fillMaxWidth().background(FocalTheme.colors.paper, FocalShapes.sheet).padding(Grid.x16)) {
                Dock(DockState.Live(paused = true), noActions)
            }
        }
    }
}

private val previewLevels = floatArrayOf(0.94f, 0.62f, 0.88f, 0.55f, 0.38f)

@Composable
private fun WaveSheet() {
    val c = FocalTheme.colors
    val meter = remember { FixedMeter(previewLevels) }
    Page {
        Box(Modifier.fillMaxWidth().height(300.dp).sceneMaterial(FocalShapes.sheetRadius, ring = c.stageRing)) {
            Column(Modifier.padding(Grid.x16), verticalArrangement = Arrangement.spacedBy(Grid.x8)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.x4)) {
                    listOf(WaveRenderer.Layers, WaveRenderer.Sprites, WaveRenderer.AlphaOnly).forEach { renderer ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            NineBars(WaveMode.Live(meter), WaveDress.Material, Modifier.markBox(84.dp), renderer)
                            Text(renderer.name, style = FocalType.caption, color = Scene.muted)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.x24), verticalAlignment = Alignment.CenterVertically) {
                    WithBadge(56.dp, PauseBadgeMetrics.stage) { NineBars(WaveMode.Live(meter, paused = true), WaveDress.Material, Modifier.markBox(56.dp)) }
                    NineBars(WaveMode.Live(meter), WaveDress.Material, Modifier.markBox(56.dp))
                    WithBadge(88.dp, PauseBadgeMetrics.scene) { NineBars(WaveMode.Live(meter, paused = true), WaveDress.Material, Modifier.markBox(88.dp)) }
                }
            }
        }
        Head("Paper")
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x24), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NineBars(WaveMode.Rest, WaveDress.paper(c), Modifier.markBox(112.dp))
                FakeCaption("rest 112")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NineBars(WaveMode.Static, WaveDress.paper(c), Modifier.markBox(40.dp))
                FakeCaption("static 40")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NineBars(WaveMode.Working(frozenAtMs = 300L), WaveDress.paper(c), Modifier.markBox(40.dp))
                FakeCaption("working 40")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NineBars(WaveMode.Static, WaveDress.paper(c), Modifier.markBox(16.dp).graphicsLayer { alpha = 0.4f })
                FakeCaption("gap 16")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.x24), verticalAlignment = Alignment.CenterVertically) {
            NineBars(WaveMode.Live(meter), WaveDress.paper(c), Modifier.markBox(40.dp), WaveRenderer.Sprites)
            NineBars(WaveMode.Working(paused = true, frozenAtMs = 300L), WaveDress.paper(c), Modifier.markBox(40.dp))
            Box(Modifier.size(4.dp, 24.dp).background(c.ink, FocalShapes.pill))
        }
    }
}

/** A wave with the pause badge at its box-relative position: box origin + (64, 47) x h / 56. */
@Composable
private fun WithBadge(height: Dp, diameter: Dp, wave: @Composable () -> Unit) {
    Box {
        wave()
        val k = height.value / Mark.BADGE_REFERENCE_HEIGHT
        PauseBadge(
            diameter = { diameter },
            modifier = Modifier.offset((Mark.BADGE_X * k).dp - PauseBadgeMetrics.scene / 2, (Mark.BADGE_Y * k).dp - PauseBadgeMetrics.scene / 2),
        )
    }
}

/** The scene's layout at rest with text (spec 4.21.7), drawn from the components (the scene stage builds it). */
@Composable
private fun SceneMock(paused: Boolean) {
    val meter = remember { FixedMeter(previewLevels) }
    val bottom = rememberBottomColumnState()
    Box(Modifier.fillMaxSize().sceneMaterial()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                Glyph(FocalIcon.Up, Scene.chevron, Modifier.padding(Grid.x12), size = 22.dp)
            }
            Box(Modifier.fillMaxWidth().padding(top = 76.dp), contentAlignment = Alignment.TopCenter) {
                if (paused) WithBadge(136.dp, PauseBadgeMetrics.scene) { NineBars(WaveMode.Live(meter, paused = true), WaveDress.Material, Modifier.markBox(136.dp)) }
                else NineBars(WaveMode.Live(meter), WaveDress.Material, Modifier.markBox(136.dp))
            }
            Text(
                if (paused) "12:48" else "4:16", style = FocalType.sceneTimer, color = if (paused) Scene.muted else Scene.hi,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 228.dp),
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 358.dp, start = Grid.x24, end = Grid.x24).height(397.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = 128.dp.toPx()), blendMode = BlendMode.DstIn)
                    },
                verticalArrangement = Arrangement.spacedBy(Grid.x16, Alignment.Bottom),
            ) {
                FakeScene.forEachIndexed { index, text ->
                    Text(text, style = FocalType.sceneText, color = if (index == FakeScene.lastIndex) Scene.ink else Scene.muted)
                }
            }
        }
        BottomColumn(bottom, dock = { Dock(DockState.Live(paused), noActions, materialEdge = { Float.MAX_VALUE }) }, modifier = Modifier.fillMaxSize())
    }
}

/** The empty library (spec 4.22.5): the instrument at rest, the two lines, Record alone. */
@Composable
private fun EmptyMock() {
    val c = FocalTheme.colors
    val bottom = rememberBottomColumnState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 268.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NineBars(WaveMode.Rest, WaveDress.paper(c), Modifier.markBox(112.dp))
            Text(
                "Speak, and it’s written down.", style = FocalType.title.copy(lineBreak = LineBreak.Heading), color = c.ink,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = Grid.x36).widthIn(max = 300.dp),
            )
            Text(
                "Tap Record and talk.\nText appears after each pause.", style = FocalType.field, color = c.muted,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = Grid.x12, start = Grid.x24, end = Grid.x24),
            )
        }
        BottomColumn(bottom, dock = { Dock(DockState.LibraryEmpty, noActions) }, modifier = Modifier.fillMaxSize())
    }
}







/** T8: the dock's progress pill a few frames into its one morph into Copy. */
@Composable
private fun ReadyMorph() {
    var pill by remember { mutableStateOf<NotePill>(NotePill.Progress(ProgressPhase.Transcribing, 0.9f)) }
    LaunchedEffect(Unit) { delay(READY_AT_MS); pill = NotePill.Copy() }
    val bottom = rememberBottomColumnState()
    BottomColumn(bottom, dock = { Dock(DockState.Note(PlayCircle.Play, pill), noActions) }, modifier = Modifier.fillMaxSize())
}

/** T9: Copy confirmed (the check and "Copied"). */
@Composable
private fun CopiedDock() {
    val bottom = rememberBottomColumnState()
    val actions = remember { DockActions(copy = { true }) }
    BottomColumn(
        bottom,
        dock = { Box(Modifier.testTag(COPY_TAG)) { Dock(DockState.Note(PlayCircle.Play, NotePill.Copy()), actions) } },
        modifier = Modifier.fillMaxSize(),
    )
}

