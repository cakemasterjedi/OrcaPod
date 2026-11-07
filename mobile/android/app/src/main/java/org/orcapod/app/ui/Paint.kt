package org.orcapod.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.orcapod.app.engine.PaintLayer
import org.orcapod.app.engine.PaintMesh
import org.orcapod.app.ui.viewport.PlateRenderer
import java.util.Locale

/** Desktop painter tools: the sphere brush, smart fill (by angle) and bucket fill. */
enum class PaintTool(val label: String) { BRUSH("Brush"), SMART_FILL("Smart fill"), BUCKET("Bucket fill") }

/**
 * The painter being used on [objectId]: which annotation [layer] it edits,
 * the tool, and the state it paints (0 erases; 1 enforces and 2 blocks for
 * supports and seams; the 1-based filament for colour; 1 for fuzzy skin).
 */
data class PaintState(
    val objectId: Long,
    val layer: PaintLayer = PaintLayer.SUPPORT,
    val tool: PaintTool = PaintTool.BRUSH,
    val state: Int = 1,
    /** Brush radius, mm (desktop default 2 mm). */
    val radius: Float = 2f,
    /** Smart fill angle, degrees (desktop default 30°). */
    val angle: Float = 30f,
)

/** The states a layer paints, with their labels. */
fun paintStates(layer: PaintLayer, filaments: Int): List<Pair<Int, String>> = when (layer) {
    PaintLayer.SUPPORT -> listOf(1 to "Enforce", 2 to "Block", 0 to "Erase")
    PaintLayer.SEAM -> listOf(1 to "Enforce", 2 to "Block", 0 to "Erase")
    PaintLayer.MMU -> (1..maxOf(1, filaments)).map { it to "Filament $it" } + (0 to "Erase")
    PaintLayer.FUZZY -> listOf(1 to "Fuzzy skin", 0 to "Erase")
}

/** Overlay colour of a painted state: desktop's enforcer blue and blocker red, or the filament's colour. */
fun paintColour(layer: PaintLayer, state: Int, filamentColours: List<String>): FloatArray = when (layer) {
    PaintLayer.MMU -> PlateRenderer.parseHex(filamentColours.getOrElse(state - 1) { "#808080" })
    PaintLayer.FUZZY -> floatArrayOf(0.75f, 0.3f, 0.9f, 1f)
    else -> if (state == 2) floatArrayOf(0.9f, 0.25f, 0.25f, 1f) else floatArrayOf(0.25f, 0.45f, 0.95f, 1f)
}

/** Interleaved position + colour vertices for the viewport overlay. */
fun paintOverlay(meshes: List<PaintMesh>, layer: PaintLayer, filamentColours: List<String>): FloatArray? {
    val total = meshes.sumOf { it.count }
    if (total == 0) return null
    val out = FloatArray(total * 18)
    var o = 0
    for (m in meshes) for (t in 0 until m.count) {
        val state = m.states[t].toInt() and 0xFF
        val c = paintColour(layer, state, filamentColours)
        for (v in 0 until 3) {
            for (k in 0 until 3) out[o + k] = m.positions[t * 9 + v * 3 + k]
            out[o + 3] = c[0]; out[o + 4] = c[1]; out[o + 5] = c[2]
            o += 6
        }
    }
    return out
}

@Composable
fun PaintPanel(vm: AppViewModel, paint: PaintState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Paint", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (l in PaintLayer.entries) {
                    if (l == PaintLayer.MMU && vm.filamentSlots.size < 2) continue
                    FilterChip(
                        selected = paint.layer == l,
                        onClick = { vm.choosePaintLayer(l) },
                        label = { Text(l.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for ((state, label) in paintStates(paint.layer, vm.filamentSlots.size)) {
                    FilterChip(
                        selected = paint.state == state,
                        onClick = { vm.paint = paint.copy(state = state) },
                        label = { Text(label) },
                        leadingIcon = if (state != 0) {
                            {
                                val c = paintColour(paint.layer, state, vm.filamentSlots.map { it.colour })
                                Box(Modifier.size(12.dp).background(Color(c[0], c[1], c[2]), CircleShape))
                            }
                        } else null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (t in PaintTool.entries) {
                    FilterChip(selected = paint.tool == t, onClick = { vm.paint = paint.copy(tool = t) }, label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            when (paint.tool) {
                PaintTool.BRUSH -> {
                    Text(String.format(Locale.US, "Brush radius %.1f mm", paint.radius), style = MaterialTheme.typography.labelMedium)
                    Slider(value = paint.radius, onValueChange = { vm.paint = paint.copy(radius = it) }, valueRange = 0.5f..15f)
                }
                PaintTool.SMART_FILL -> {
                    Text(String.format(Locale.US, "Smart fill angle %.0f°", paint.angle), style = MaterialTheme.typography.labelMedium)
                    Slider(value = paint.angle, onValueChange = { vm.paint = paint.copy(angle = it) }, valueRange = 1f..90f)
                }
                PaintTool.BUCKET -> Text("Tap a face to fill its connected area.", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                if (paint.tool == PaintTool.BRUSH) "Drag on the object to paint; drag elsewhere to turn the view."
                else "Tap the object to fill; drag elsewhere to turn the view.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { vm.clearPaint() }) { Text("Clear ${paint.layer.label.lowercase()}") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.paint = null }) { Text("Done") }
            }
        }
    }
}
