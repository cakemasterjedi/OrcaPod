package org.orcapod.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Desktop's variable layer height editor on [objectId]: the profile as flat
 * (z, layer height) pairs (empty follows the process [layerHeight]), the
 * height [z] the manual edits act on with their [band], and the settings of
 * Adaptive and Smooth.
 */
data class LayerHeightState(
    val objectId: Long,
    /** Object height, mm. */
    val height: Double,
    val layerHeight: Double,
    val profile: DoubleArray = DoubleArray(0),
    val z: Double = 0.0,
    /** Desktop defaults: band 2 mm, quality 0.5, radius 5 layers. */
    val band: Float = 2f,
    val quality: Float = 0.5f,
    val radius: Int = 5,
    val keepMin: Boolean = false,
) {
    /** (z, layer height) points of the profile, or the uniform layer height. */
    fun points(): List<Pair<Double, Double>> =
        if (profile.size < 4) listOf(0.0 to layerHeight, height to layerHeight)
        else (profile.indices step 2).map { profile[it] to profile[it + 1] }

    override fun equals(other: Any?) = other is LayerHeightState && objectId == other.objectId && height == other.height &&
        layerHeight == other.layerHeight && profile.contentEquals(other.profile) && z == other.z && band == other.band &&
        quality == other.quality && radius == other.radius && keepMin == other.keepMin

    override fun hashCode() = objectId.hashCode() * 31 + profile.contentHashCode()

    companion object {
        /** Thickness change of one press: four of desktop's 0.005 mm timer steps. */
        const val STRENGTH = 0.02
        /** Desktop LayerHeightEditActionType. */
        const val INCREASE = 0
        const val DECREASE = 1
        const val SMOOTH = 3
    }
}

@Composable
fun LayerHeightPanel(vm: AppViewModel, l: LayerHeightState) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Variable layer height", style = MaterialTheme.typography.titleMedium)
            Text(
                if (l.profile.size < 4) String.format(Locale.US, "Uniform %.2f mm layers from the process.", l.layerHeight)
                else "Tap or drag the chart to pick a height, then make layers thicker or thinner there.",
                style = MaterialTheme.typography.bodySmall,
            )
            ProfileChart(l) { vm.layerHeights = l.copy(z = it) }
            Text(String.format(Locale.US, "At %.2f mm, band %.1f mm", l.z, l.band), style = MaterialTheme.typography.labelMedium)
            Slider(value = l.band, onValueChange = { vm.layerHeights = l.copy(band = it) }, valueRange = 1.5f..10f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.adjustLayerHeights(LayerHeightState.INCREASE) }) { Text("Thicker") }
                OutlinedButton(onClick = { vm.adjustLayerHeights(LayerHeightState.DECREASE) }) { Text("Thinner") }
                OutlinedButton(onClick = { vm.adjustLayerHeights(LayerHeightState.SMOOTH) }) { Text("Smooth here") }
            }

            Spacer(Modifier.height(8.dp))
            Text(String.format(Locale.US, "Adaptive quality %.2f", l.quality), style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(value = l.quality, onValueChange = { vm.layerHeights = l.copy(quality = it) }, valueRange = -0.1f..1f, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.adaptiveLayerHeights() }) { Text("Adaptive") }
            }
            Text("Smooth radius ${l.radius} layers", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = l.radius.toFloat(),
                    onValueChange = { vm.layerHeights = l.copy(radius = it.toInt().coerceIn(1, 10)) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.smoothLayerHeights() }) { Text("Smooth") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = l.keepMin, onCheckedChange = { vm.layerHeights = l.copy(keepMin = it) })
                Text("Keep min", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { vm.resetLayerHeights() }, enabled = l.profile.size >= 4) { Text("Reset") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.layerHeights = null }) { Text("Done") }
            }
        }
    }
}

/** Desktop's layer height bar: object height upwards, layer height to the right, the edit band shaded. */
@Composable
private fun ProfileChart(l: LayerHeightState, onPick: (Double) -> Unit) {
    val points = l.points()
    val maxH = maxOf(points.maxOf { it.second }, l.layerHeight) * 1.15
    val line = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outline
    val band = MaterialTheme.colorScheme.tertiary
    fun zAt(y: Float, h: Float) = ((1 - y / h) * l.height).coerceIn(0.0, l.height)
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).padding(vertical = 6.dp)
            .pointerInput(l.height) { detectTapGestures { onPick(zAt(it.y, size.height.toFloat())) } }
            .pointerInput(l.height) { detectDragGestures { c, _ -> onPick(zAt(c.position.y, size.height.toFloat())) } },
    ) {
        val w = size.width
        val h = size.height
        fun x(lh: Double) = (lh / maxH * w).toFloat()
        fun y(z: Double) = ((1 - z / l.height) * h).toFloat()
        val top = y(minOf(l.height, l.z + l.band / 2))
        val bottom = y(maxOf(0.0, l.z - l.band / 2))
        drawRect(band.copy(alpha = 0.18f), Offset(0f, top), Size(w, bottom - top))
        drawLine(band, Offset(0f, y(l.z)), Offset(w, y(l.z)), strokeWidth = 2f)
        drawLine(
            base, Offset(x(l.layerHeight), 0f), Offset(x(l.layerHeight), h),
            strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        val path = Path()
        for ((i, p) in points.withIndex()) {
            if (i == 0) path.moveTo(x(p.second), y(p.first)) else path.lineTo(x(p.second), y(p.first))
        }
        drawPath(path, line, style = Stroke(width = 4f))
    }
}
