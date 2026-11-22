package com.allmeyn.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.wexpa.liquidglass.material3.OutlinedButton
import com.wexpa.liquidglass.material3.Slider
import com.wexpa.liquidglass.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allmeyn.player.dsp.DspMeters
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspProcessor
import com.allmeyn.player.dsp.DspSettings
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt

private val BAND_NAMES = listOf("LF", "LMF", "HMF", "HF")

/**
 * The DSP chain: master switch, processor order (drag), and each processor's settings and meters.
 * Changes apply immediately and are saved.
 */
@Composable
fun DspScreen(params: DspParams, onChange: ((DspParams) -> DspParams) -> Unit) {
    var meters by remember { mutableStateOf<DspMeters?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            meters = DspSettings.meters
            delay(66)
        }
    }
    val p = params
    val listState = rememberLazyListState()
    // The processor order is the draggable part of the list; everything else is fixed items.
    val reorder = rememberReorderState(p.order, listState) { it.name }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = belowBars(16.dp, 16.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "master") {
            PanelCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow("DSP", p.enabled, { v -> onChange { it.copy(enabled = v) } })
                    Text(
                        "Processes the music, so it's no longer bit-perfect while on (also through the USB driver). " +
                            "DSD plays as PCM while the DSP is on. Off = untouched audio again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item(key = "chainTitle") {
            Text("Chain (drag to reorder)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        itemsIndexed(reorder.rows, key = { _, proc -> proc.name }) { i, proc ->
            PanelCard(reorderRow(reorder, proc).fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
                    Text(proc.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(checked = isOn(p, proc), onCheckedChange = { v -> onChange { setOn(it, proc, v) } }, enabled = p.enabled)
                    DragHandle(reorder, proc) { from, to ->
                        onChange { cur -> cur.copy(order = cur.order.toMutableList().apply { add(to, removeAt(from)) }) }
                    }
                }
            }
        }
        item(key = "compander") {
            Section(DspProcessor.COMPANDER.label, on = p.enabled && p.companderOn) {
                LabeledSlider(
                    "Dynamics", "${signed(p.expansionPercent)} % · ${p.ratioLabel}" +
                        if (p.expansionPercent < 0) " (compress)" else if (p.expansionPercent > 0) " (expand)" else "",
                    p.expansionPercent, -50f..50f, 1f,
                ) { v -> onChange { it.copy(expansionPercent = v) } }
                LabeledSlider("Transition", "${p.transitionDb.roundToInt()} dBFS", p.transitionDb, -40f..0f, 1f) { v -> onChange { it.copy(transitionDb = v) } }
                LabeledSlider("HF Trim", "${signed(p.hfTransitionDb)} dB", p.hfTransitionDb, -10f..10f, 0.5f) { v -> onChange { it.copy(hfTransitionDb = v) } }
                BandMeters(meters?.companderDb, -20f, 12f)
            }
        }
        item(key = "bass") {
            Section(DspProcessor.BASS.label, on = p.enabled && p.bassOn) {
                LabeledSlider("Amount", p.bassLabel, p.bassPercent, 0f..100f, 1f) { v -> onChange { it.copy(bassPercent = v) } }
                LabeledSlider("Bass Transition", "${p.bassTransitionDb.roundToInt()} dBFS", p.bassTransitionDb, -40f..0f, 1f) { v -> onChange { it.copy(bassTransitionDb = v) } }
                GainMeter("Bass", meters?.bassDb ?: 0f, -20f, 12f)
            }
        }
        item(key = "impact") {
            Section(DspProcessor.IMPACT.label, on = p.enabled && p.impactOn) {
                LabeledSlider("Impact", p.impactLabel, p.impactPercent, 0f..100f, 1f) { v -> onChange { it.copy(impactPercent = v) } }
                LabeledSlider("Release", "${p.impactReleaseMs.roundToInt()} ms", p.impactReleaseMs, 25f..1500f, 5f) { v -> onChange { it.copy(impactReleaseMs = v) } }
                BandMeters(meters?.impactDb, 0f, 12f)
            }
        }
        item(key = "output") {
            Section("Output", on = p.enabled) {
                LabeledSlider("Volume", "${signed(p.volumeDb)} dB", p.volumeDb, -40f..10f, 0.5f) { v -> onChange { it.copy(volumeDb = v) } }
                SwitchRow("Soft Clip (peaks never exceed 0 dBFS)", p.softClip, { v -> onChange { it.copy(softClip = v) } })
                val peak = meters?.outputPeak ?: 0f
                val peakDb = if (peak > 0f) 20 * log10(peak) else -100f
                Text(
                    if (meters == null) "Output peak: —" else "Output peak: %.1f dBFS".format(peakDb) + if (peak > 1f) "  OVER" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peak > 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item(key = "reset") {
            OutlinedButton(onClick = { onChange { DspParams(enabled = it.enabled) } }) { Text("Reset to defaults") }
        }
    }
}

private fun isOn(p: DspParams, proc: DspProcessor) = when (proc) {
    DspProcessor.COMPANDER -> p.companderOn
    DspProcessor.BASS -> p.bassOn
    DspProcessor.IMPACT -> p.impactOn
}

private fun setOn(p: DspParams, proc: DspProcessor, on: Boolean) = when (proc) {
    DspProcessor.COMPANDER -> p.copy(companderOn = on)
    DspProcessor.BASS -> p.copy(bassOn = on)
    DspProcessor.IMPACT -> p.copy(impactOn = on)
}

private fun signed(v: Float) = if (v > 0f) "+%.1f".format(v).removeSuffix(".0") else "%.1f".format(v).removeSuffix(".0")

@Composable
private fun Section(title: String, on: Boolean, content: @Composable () -> Unit) {
    PanelCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (on) "ON" else "OFF", style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValue: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = current,
            onValueChange = { v -> onValue((v / step).roundToInt() * step) },
            valueRange = range,
        )
    }
}

/** One bar per band: down (yellow) / up (red) gain in dB, as on Tetra-Expand's meters. */
@Composable
private fun BandMeters(values: FloatArray?, minDb: Float, maxDb: Float) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BAND_NAMES.forEachIndexed { b, name ->
            Box(Modifier.weight(1f)) { GainMeter(name, values?.getOrNull(b) ?: 0f, minDb, maxDb) }
        }
    }
}

@Composable
private fun GainMeter(name: String, db: Float, minDb: Float, maxDb: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val up = db > 0f
        val frac = if (up) (db / maxDb).coerceIn(0f, 1f) else if (minDb < 0f) (db / minDb).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(frac)
                    .background(if (up) Color(0xFFE53935) else Color(0xFFFDD835)),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("$name ${signed((db * 10).roundToInt() / 10f)}", style = MaterialTheme.typography.labelSmall)
    }
}
