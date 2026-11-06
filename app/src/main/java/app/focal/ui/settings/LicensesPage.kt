package app.focal.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import app.focal.R
import app.focal.ui.components.PaperTopFade
import app.focal.ui.components.PressPlate
import app.focal.ui.components.focusRing
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry of the licences page: its name and origin, and where its full text sits in the bundled notices. */
@Immutable
data class LicenseEntry(@param:StringRes val name: Int, @param:StringRes val origin: Int, val asset: String, val start: String?, val end: String?)

/** The Apache License 2.0 section of the app's notices, which the AndroidX, Material Symbols and Kotlin entries share. */
private const val APACHE = "Apache License 2.0:"

/**
 * Everything Focal ships (spec 5.10), in the order the page lists them: the speech model with its attribution, the
 * speech runtime, the typeface, the platform libraries and the app itself. The headings (the [LicenseEntry.start]
 * and [LicenseEntry.end] prefixes) are the bundled notices' own.
 */
val LicenseEntries = listOf(
    LicenseEntry(R.string.lic_parakeet, R.string.lic_parakeet_origin, LicenseText.ULTRA, null, "Silero VAD — "),
    LicenseEntry(R.string.lic_silero, R.string.lic_silero_origin, LicenseText.ULTRA, "Silero VAD — ", "sherpa-onnx "),
    LicenseEntry(R.string.lic_sherpa, R.string.lic_sherpa_origin, LicenseText.ULTRA, "sherpa-onnx ", "ONNX Runtime "),
    LicenseEntry(R.string.lic_onnxruntime, R.string.lic_onnxruntime_origin, LicenseText.ULTRA, "ONNX Runtime ", null),
    LicenseEntry(R.string.lic_inter, R.string.lic_inter_origin, LicenseText.APP, "Inter ", null),
    LicenseEntry(R.string.lic_androidx, R.string.lic_androidx_origin, LicenseText.APP, APACHE, "Inter "),
    LicenseEntry(R.string.lic_symbols, R.string.lic_symbols_origin, LicenseText.APP, APACHE, "Inter "),
    LicenseEntry(R.string.lic_kotlin, R.string.lic_kotlin_origin, LicenseText.APP, APACHE, "Inter "),
    LicenseEntry(R.string.lic_focal, R.string.lic_focal_origin, LicenseText.APP, null, APACHE),
)

/** Slices the bundled notices (assets/licenses.txt, assets/ultra-licenses.txt) into entries and paragraphs. */
object LicenseText {
    const val APP = "licenses.txt"
    const val ULTRA = "ultra-licenses.txt"
    private val paragraphBreak = Regex("\\r?\\n(?:[ \\t]*\\r?\\n)+")
    private val softBreak = Regex("[ \\t]*\\r?\\n[ \\t]*")

    /**
     * The text from the paragraph whose first line starts with [start] (the file's start when null) to the paragraph
     * whose first line starts with [end] (the file's end when null).
     */
    fun section(text: String, start: String?, end: String?): String {
        val paragraphs = paragraphBreak.split(text)
        val from = if (start == null) 0 else paragraphs.indexOfFirst { it.trimStart().startsWith(start) }.takeIf { it >= 0 } ?: return ""
        val to = if (end == null) paragraphs.size else paragraphs.drop(from + 1).indexOfFirst { it.trimStart().startsWith(end) }
            .let { if (it < 0) paragraphs.size else from + 1 + it }
        return paragraphs.subList(from, to).joinToString("\n\n")
    }

    /** Paragraphs reflowed for a phone's measure: the files' 80-column line breaks become spaces. */
    fun paragraphs(section: String): List<String> =
        paragraphBreak.split(section).map { it.trim().replace(softBreak, " ") }.filter { it.isNotEmpty() }

    suspend fun load(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        listOf(APP, ULTRA).associateWith { name -> runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("") }
    }
}

/**
 * The licences page (spec 5.10): title "Open-source licenses"; each entry its name (`rowTitle`) and its licence and
 * origin (`secondary` `muted`); a tap expands the full text in place (`move`), selectable. Long notices are lazy
 * paragraphs, so the page stays light.
 */
@Composable
fun LicensesPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val context = LocalContext.current
    val files by produceState<Map<String, String>?>(null) { value = LicenseText.load(context) }
    val expanded = rememberSaveable(saver = listSaver()) { mutableStateListOf<Int>() }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.asPaddingValues()
    val navigation = WindowInsets.navigationBars.asPaddingValues()
    val title = stringResource(R.string.licenses)
    Box(modifier.fillMaxSize().semantics { paneTitle = title }) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = status.calculateTopPadding() + FocalLayout.topBar,
                bottom = navigation.calculateBottomPadding() + FocalLayout.gutter,
            ),
        ) {
            item(key = "title") {
                Text(
                    title, style = FocalType.title, color = colors.ink,
                    modifier = Modifier.padding(horizontal = FocalLayout.gutter).padding(top = SettingsMetrics.titleTop, bottom = Grid.x16)
                        .semantics { heading() },
                )
            }
            LicenseEntries.forEachIndexed { index, entry ->
                item(key = "entry:$index") {
                    val source = remember { MutableInteractionSource() }
                    val open = index in expanded
                    val toggle = { if (open) expanded.remove(index) else expanded.add(index) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingsMetrics.rowMinHeight)
                            .focusRing(FocalShapes.block)
                            .clickable(source, indication = EntryPlate, role = Role.Button) { toggle() }
                            // TalkBack says whether the text is shown and offers the opposite.
                            .semantics { if (open) collapse { toggle(); true } else expand { toggle(); true } }
                            .testTag("$LICENSE_ENTRY_TAG:$index")
                            .padding(horizontal = FocalLayout.gutter, vertical = SettingsMetrics.rowPadding),
                    ) {
                        Text(stringResource(entry.name), style = FocalType.rowTitle, color = colors.ink)
                        Text(stringResource(entry.origin), style = FocalType.secondary, color = colors.recede)
                    }
                }
                if (index in expanded) {
                    val text = files?.get(entry.asset)
                    val paragraphs = if (text == null) emptyList() else LicenseText.paragraphs(LicenseText.section(text, entry.start, entry.end))
                    items(paragraphs.withIndex().toList(), key = { "text:$index:${it.index}" }) { (_, paragraph) ->
                        SelectionContainer(
                            Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = FocalMotion.move(), fadeOutSpec = FocalMotion.fadeFast()),
                        ) {
                            Text(
                                paragraph, style = FocalType.secondary, color = colors.recede,
                                modifier = Modifier.padding(horizontal = FocalLayout.gutter).padding(bottom = Grid.x12),
                            )
                        }
                    }
                }
            }
        }
        PaperTopFade(FocalLayout.topBar, Modifier.fillMaxSize())
        TopBar(title, onBack, collapsed = {
            val first = list.firstVisibleItemIndex
            first > 0 || list.firstVisibleItemScrollOffset > with(density) { SettingsMetrics.titleTop.toPx() + FocalType.title.lineHeight.toPx() }
        })
    }
}

private val EntryPlate = PressPlate(FocalShapes.block, insetX = SettingsMetrics.plateInset)

/** Test tag prefix of the entries ("license-entry:0" is the speech model), for the screenshot gallery. */
const val LICENSE_ENTRY_TAG = "license-entry"

/** The expanded entries survive a configuration change (an ArrayList, which a Bundle can hold). */
private fun listSaver() = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateList<Int>, ArrayList<Int>>(
    save = { ArrayList(it) }, restore = { it.toMutableStateList() },
)
