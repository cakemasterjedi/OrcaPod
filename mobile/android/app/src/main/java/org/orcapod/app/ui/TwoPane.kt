package org.orcapod.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.orcapod.app.ui.viewport.Selection

/**
 * Whether a window of this size shows the viewport and a side pane next to
 * each other instead of the phone's overlays.
 *
 * Material's expanded width (840 dp) always qualifies; a medium width
 * (600 dp) qualifies when the window is landscape or nearly square, which
 * covers the inner screen of a book-style foldable held either way. A
 * compact height (under 480 dp, a phone in landscape) keeps the phone
 * layout, since a pane that short cannot hold its tabs and controls, and so
 * does a tall medium window (a small tablet in portrait), where a side pane
 * would leave the viewport a narrow strip.
 */
fun usesTwoPane(widthDp: Int, heightDp: Int): Boolean =
    heightDp >= 480 && (widthDp >= 840 || (widthDp >= 600 && widthDp >= heightDp * 0.85f))

/** The side pane's width: wide enough for the object fields, the rest goes to the viewport. */
fun sidePaneWidth(widthDp: Int): Dp = when {
    widthDp >= 1200 -> 400.dp
    widthDp >= 840 -> 360.dp
    else -> 320.dp
}

/** How a foldable is held, from Jetpack WindowManager's folding feature. */
enum class Posture {
    /** No fold, or a fold that is flat or does not split the content. */
    FLAT,
    /** Half open with a horizontal hinge: the top half stands up, the bottom half lies on the table. */
    TABLETOP,
    /** Half open with a vertical hinge, held like a book. */
    BOOK,
}

/** The plate screen's arrangement for a window size and posture. */
enum class PlateLayout {
    /** Viewport with the Workbench overlays (cover screen, phones). */
    PHONE,
    /** Viewport and side pane next to each other. */
    TWO_PANE,
    /** Viewport above the hinge, the pane's controls below it. */
    TABLETOP,
}

/**
 * Tabletop posture moves the controls to the half that lies on the table, so
 * the model stays in view on the upright half; that needs a window wide
 * enough to lay the pane out in two columns. A book posture keeps the two
 * panes even when the plain size rule would not, so the hinge separates the
 * viewport from the pane instead of running through the model.
 */
fun plateLayout(widthDp: Int, heightDp: Int, posture: Posture): PlateLayout = when {
    posture == Posture.TABLETOP && widthDp >= 600 && heightDp >= 400 -> PlateLayout.TABLETOP
    posture == Posture.BOOK && widthDp >= 600 && heightDp >= 480 -> PlateLayout.TWO_PANE
    usesTwoPane(widthDp, heightDp) -> PlateLayout.TWO_PANE
    else -> PlateLayout.PHONE
}

/**
 * The side pane's width in book posture: the pane fills the half right of
 * the hinge, so nothing straddles the fold, unless that half is too narrow
 * for the pane or would squeeze the viewport.
 */
fun bookPaneWidth(widthDp: Int, hingeEndDp: Int?): Dp {
    val standard = sidePaneWidth(widthDp)
    if (hingeEndDp == null) return standard
    val right = widthDp - hingeEndDp
    return if (right >= 300 && hingeEndDp >= 280) right.dp else standard
}

enum class PaneTab(val label: String) { OBJECTS("Objects"), SETTINGS("Settings") }

/**
 * The large-screen side pane: presets on top, the object list or the print
 * settings below, and the slice controls at the bottom. Tabletop posture
 * shows the presets and controls in a column of their own, without
 * [header] and [footer].
 */
@Composable
fun SidePane(
    tab: PaneTab,
    onTab: (PaneTab) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)?,
    objects: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    Column(modifier) {
        if (header != null) Box(Modifier.padding(8.dp)) { header() }
        TabRow(selectedTabIndex = tab.ordinal) {
            for (t in PaneTab.entries) {
                Tab(selected = t == tab, onClick = { onTab(t) }, text = { Text(t.label) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PaneTab.OBJECTS -> objects()
                PaneTab.SETTINGS -> settings()
            }
        }
        if (footer != null) Box(Modifier.padding(8.dp)) { footer() }
    }
}

/** The selected object's controls above the plate's object list; tapping an object selects it. */
@Composable
fun ObjectsTab(vm: AppViewModel, layFlat: Boolean, onLayFlat: () -> Unit, onEdit: (Selection) -> Unit) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        val sel = vm.selection
        if (sel != null && vm.transformOf(sel) != null) {
            ObjectPanel(vm, sel, layFlat = layFlat, onLayFlat = onLayFlat, onEdit = { onEdit(sel) })
            Spacer(Modifier.height(8.dp))
        }
        ObjectListContent(vm, onOpen = { vm.selection = it }, modifier = Modifier.weight(1f), showTitle = false)
    }
}
