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
 * (600 dp) qualifies in landscape, which covers unfolded foldables held
 * sideways. A compact height (under 480 dp, a phone in landscape) keeps the
 * phone layout, since a pane that short cannot hold its tabs and controls.
 */
fun usesTwoPane(widthDp: Int, heightDp: Int): Boolean =
    heightDp >= 480 && (widthDp >= 840 || (widthDp >= 600 && widthDp > heightDp))

/** The side pane's width: wide enough for the object fields, the rest goes to the viewport. */
fun sidePaneWidth(widthDp: Int): Dp = if (widthDp >= 1200) 400.dp else 360.dp

enum class PaneTab(val label: String) { OBJECTS("Objects"), SETTINGS("Settings") }

/**
 * The large-screen side pane: presets on top, the object list or the print
 * settings below, and the slice controls at the bottom.
 */
@Composable
fun SidePane(
    tab: PaneTab,
    onTab: (PaneTab) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    objects: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(Modifier.padding(8.dp)) { header() }
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
        Box(Modifier.padding(8.dp)) { footer() }
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
