package org.orcapod.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
 * the pickers list. No printer ticked lists every printer; no filament
 * ticked lists none, so the filament picker holds only what the user owns.
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
    var type by remember { mutableStateOf<String?>(null) }
    var moreBrands by remember { mutableStateOf(false) }
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matching = groups.filter { g ->
        (!onFilaments || type == null || g.type == type) &&
            words.all { w -> g.name.contains(w, true) || g.brand.contains(w, true) }
    }
    // Filaments: the common makers first in a fixed order, the rest folded under "More brands".
    val (common, rare) = if (onFilaments) {
        matching.partition { commonRank(it.brand) >= 0 }.let { (c, r) -> c.sortedBy { commonRank(it.brand) } to r }
    } else {
        matching to emptyList()
    }
    val rareOpen = moreBrands || words.isNotEmpty()

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(if (onFilaments) "Your filaments" else "Your printers", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (onFilaments) {
                "Tick the filaments you use; only those are listed in the filament picker. Your own " +
                    "imported filaments are listed too. You can change them later with Choose in the filament picker."
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
        if (onFilaments) TypeChips(catalog.filaments, type) { type = it }
        LazyColumn(Modifier.weight(1f)) {
            fun section(list: List<Group>) {
                var lastBrand: String? = null
                for (g in list) {
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
            section(common)
            if (rare.isNotEmpty()) {
                item(key = "more") {
                    val brands = rare.map { it.brand }.distinct().size
                    val tickedHere = rare.count { it in picked }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = words.isEmpty()) { moreBrands = !moreBrands }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (rareOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (tickedHere > 0) "More brands ($brands, $tickedHere ticked)" else "More brands ($brands)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    HorizontalDivider()
                }
                if (rareOpen) section(rare)
            }
        }
        Text(
            when {
                picked.isNotEmpty() -> "${picked.size} ticked"
                onFilaments -> "None ticked: only your own filaments are listed"
                else -> "None ticked: all are listed"
            },
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

/** Position among the common filament makers, or -1 for the others. */
private fun commonRank(brand: String): Int =
    ProfileCatalog.COMMON_MAKERS.indexOfFirst { it.equals(brand, ignoreCase = true) }

/** The common material types as chips, the other types present in a "More types" menu. */
@Composable
private fun TypeChips(filaments: List<Group>, type: String?, onType: (String?) -> Unit) {
    val present = filaments.map { it.type }.toSet()
    val common = ProfileCatalog.COMMON_TYPES.filter { it in present }
    val others = (present - common.toSet()).sortedWith(compareBy<String> { it == "Other" }.thenBy { it })
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = type == null, onClick = { onType(null) }, label = { Text("All") })
        for (t in common) FilterChip(selected = type == t, onClick = { onType(if (type == t) null else t) }, label = { Text(t) })
        if (others.isNotEmpty()) {
            Box {
                FilterChip(
                    selected = type != null && type in others,
                    onClick = { menu = true },
                    label = { Text(if (type != null && type in others) type else "More types") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (t in others) DropdownMenuItem(text = { Text(t) }, onClick = { menu = false; onType(t) })
                }
            }
        }
    }
}

/** The groups that the choice already lists: any of their presets was ticked. */
private fun ticked(groups: List<Group>, choice: PresetChoice): Set<Group> =
    groups.filterTo(HashSet()) { g -> g.presets.any { it in choice.shown } }
