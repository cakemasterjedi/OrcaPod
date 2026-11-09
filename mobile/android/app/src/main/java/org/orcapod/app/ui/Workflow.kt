package org.orcapod.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One row of the side menu. [checked] makes it a toggle shown with a switch. */
data class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

data class MenuGroup(val title: String, val entries: List<MenuEntry>)

/**
 * The side menu that holds everything that is not part of the
 * add → arrange → slice → print flow: projects, exports, printers, profiles,
 * tools and preferences, grouped the way desktop's menus group them.
 */
@Composable
fun AppDrawerSheet(groups: List<MenuGroup>, project: String? = null, onClose: () -> Unit) {
    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(
                "OrcaPod",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                project ?: "Prepare, slice and print",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((index, group) in groups.withIndex()) {
                if (group.entries.isEmpty()) continue
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                else Spacer(Modifier.height(12.dp))
                Text(
                    group.title,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (entry in group.entries) {
                    NavigationDrawerItem(
                        label = {
                            Text(
                                entry.label,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (entry.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        },
                        icon = entry.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                        badge = entry.checked?.let { on -> { Switch(checked = on, onCheckedChange = null) } },
                        selected = false,
                        onClick = {
                            if (entry.enabled) {
                                // Toggles stay open so several can be flipped in a row.
                                if (entry.checked == null) onClose()
                                entry.onClick()
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** The two stages of the plate, as desktop's Prepare and Preview tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageTabs(previewing: Boolean, canPreview: Boolean, onPrepare: () -> Unit, onPreview: () -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = !previewing,
            onClick = onPrepare,
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { SegmentedButtonDefaults.Icon(!previewing) { Icon(AppIcons.ViewInAr, null, Modifier.size(18.dp)) } },
        ) { Text("Prepare") }
        SegmentedButton(
            selected = previewing,
            onClick = onPreview,
            enabled = canPreview,
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { SegmentedButtonDefaults.Icon(previewing) { Icon(AppIcons.Layers, null, Modifier.size(18.dp)) } },
        ) { Text("Preview") }
    }
}

/** A compact icon-over-label button for the bottom bar and the object panel. */
@Composable
fun ToolButton(label: String, icon: ImageVector, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shown over the empty plate: the ways to get a first model onto it. */
@Composable
fun EmptyPlateCard(
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    onOpenProject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.widthIn(max = 420.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)),
    ) {
        Box {
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Icon(Icons.Filled.Close, "Close")
        }
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Icon(AppIcons.ViewInAr, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text("Add a model to start", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                "Import an STL, 3MF, OBJ, AMF or STEP file, or find one in the online library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onImport, Modifier.fillMaxWidth()) {
                Icon(AppIcons.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Import a model")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onLibrary, Modifier.fillMaxWidth()) {
                Icon(AppIcons.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("Browse the model library")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenProject, Modifier.fillMaxWidth()) { Text("Open project") }
        }
        }
    }
}
