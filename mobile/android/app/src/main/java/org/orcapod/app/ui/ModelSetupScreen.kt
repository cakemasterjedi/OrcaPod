package org.orcapod.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.orcapod.app.data.PresetChoice
import org.orcapod.app.data.ProfileCatalog
import org.orcapod.app.data.ProfileCatalog.Group

/**
 * The step after picking brands: which printer models, then which filaments,
 * the pickers list. Nothing ticked on a page lists everything of that kind.
 */
@Composable
fun ModelSetupScreen(
    catalog: ProfileCatalog,
    printerChoice: PresetChoice,
    filamentChoice: PresetChoice,
    onDone: (printers: Set<Group>, filaments: Set<Group>) -> Unit,
    onSkip: () -> Unit,
) {
    var printers by remember { mutableStateOf(ticked(catalog.printers, printerChoice)) }
    var filaments by remember { mutableStateOf(ticked(catalog.filaments, filamentChoice)) }
    var onFilaments by remember { mutableStateOf(false) }
    var query by remember(onFilaments) { mutableStateOf("") }

    val groups = if (onFilaments) catalog.filaments else catalog.printers
    val picked = if (onFilaments) filaments else printers
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = groups.filter { g -> words.all { w -> g.name.contains(w, true) || g.brand.contains(w, true) } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(if (onFilaments) "Your filaments" else "Your printers", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (onFilaments) {
                "Tick the filaments you use; only those are listed in the filament picker. Your own " +
                    "imported filaments are listed too. With none ticked, every filament is listed."
            } else {
                "Tick the printers you own; only those are listed in the printer picker. " +
                    "With none ticked, every printer of your brands is listed."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(if (onFilaments) "Search filaments" else "Search printers") },
        )
        LazyColumn(Modifier.weight(1f)) {
            var lastBrand: String? = null
            for (g in shown) {
                if (g.brand != lastBrand) {
                    lastBrand = g.brand
                    item(key = "brand/${g.brand}") {
                        Text(
                            g.brand,
                            Modifier.padding(top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                item(key = "${g.brand}/${g.name}") {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val next = if (g in picked) picked - g else picked + g
                            if (onFilaments) filaments = next else printers = next
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = g in picked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(g.name)
                    }
                    HorizontalDivider()
                }
            }
        }
        Text(
            if (picked.isEmpty()) "None ticked: all are listed" else "${picked.size} ticked",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            if (onFilaments) {
                TextButton(onClick = { onFilaments = false }) { Text("Back") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDone(printers, filaments) }) { Text("Done") }
            } else {
                TextButton(onClick = onSkip) { Text("Skip") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onFilaments = true }) { Text("Next") }
            }
        }
    }
}

/** The groups that the choice already lists: any of their presets was ticked. */
private fun ticked(groups: List<Group>, choice: PresetChoice): Set<Group> =
    groups.filterTo(HashSet()) { g -> g.presets.any { it in choice.shown } }
