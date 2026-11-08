package org.orcapod.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.StepFile

/**
 * Desktop "Unsupported characters in names": remove the characters a
 * project cannot hold or replace each with some text, with a live preview of
 * the renamed parts and objects, and Remember my choice / Don't ask again.
 */
@Composable
fun NameCleanupDialog(
    request: NameCleanupRequest,
    preview: suspend (StepFile, String) -> List<Pair<String, String>>?,
    onAnswer: (NameCleanupAnswer?) -> Unit,
) {
    var remove by remember { mutableStateOf(request.initialRemove) }
    var replacement by remember { mutableStateOf(request.initialReplacement) }
    var remember by remember { mutableStateOf(false) }
    var dontAsk by remember { mutableStateOf(false) }
    // null while the preview is computed or the replacement is not usable
    var changes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var valid by remember { mutableStateOf(true) }

    LaunchedEffect(remove, replacement) {
        changes = null
        // the bridge refuses a replacement holding unsupported characters; an empty one means removal
        val result = if (!remove && replacement.isEmpty()) null else preview(request.step, if (remove) "" else replacement)
        valid = result != null
        changes = result
    }

    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text("Unsupported characters in names") },
        text = {
            Column {
                Text(
                    "Some original names in ${request.fileName} contain characters that OrcaPod cannot use. " +
                        "Choose how to change them before importing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth().clickable { remove = true }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = remove, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove unsupported characters", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().clickable { remove = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !remove, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Replace with:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        modifier = Modifier.weight(1f),
                        enabled = !remove,
                        singleLine = true,
                        isError = !remove && !valid,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!valid) {
                    Text(
                        "Enter a non-empty replacement containing only supported characters, or choose removal.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(vertical = 8.dp)) {
                        items(changes.orEmpty()) { (from, to) ->
                            Text("$from → $to", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                CheckRow("Remember my choice", remember) { remember = it }
                CheckRow("Don't ask again", dontAsk) { dontAsk = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && changes != null,
                onClick = { onAnswer(NameCleanupAnswer(remove, replacement, remember, dontAsk)) },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}
