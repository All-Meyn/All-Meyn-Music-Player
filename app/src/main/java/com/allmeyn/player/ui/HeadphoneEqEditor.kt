package com.allmeyn.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allmeyn.player.dsp.ConvolutionFilter
import com.allmeyn.player.dsp.EqBand
import com.allmeyn.player.dsp.EqFilterType
import com.allmeyn.player.dsp.EqProfile
import com.wexpa.liquidglass.material3.Slider
import com.wexpa.liquidglass.material3.Switch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

// Shared by the Android and Windows DSP screens (desktop/build.gradle.kts, sharedSources): only
// Compose, Material and Liquid Glass; files, clipboard and saving come from the caller.

/**
 * The headphone EQ: on/off, the profile's name, import (file / paste) and export (copy), saved
 * profiles, the response curve, the preamp and the filters (type, Hz, dB, Q). Changes apply at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeadphoneEqEditor(
    on: Boolean,
    profile: EqProfile,
    notes: List<String>,
    saved: List<EqProfile>,
    onOn: (Boolean) -> Unit,
    onProfile: (EqProfile) -> Unit,
    onImportFile: () -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(checked = on, onCheckedChange = onOn)
        }
        Text(
            "Corrects your headphones' frequency response. Import an AutoEq / EqualizerAPO " +
                "ParametricEQ.txt, paste one, or set the filters yourself.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = profile.name, onValueChange = { onProfile(profile.copy(name = it)) }, singleLine = true,
            label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onImportFile) { Text("Import file…") }
            TextButton(onClick = onPaste) { Text("Paste") }
            TextButton(onClick = onCopy, enabled = profile.bands.isNotEmpty()) { Text("Copy") }
            TextButton(onClick = onSave, enabled = profile.name.isNotBlank()) { Text("Save") }
            Box {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }, enabled = saved.isNotEmpty()) { Text("Saved (${saved.size}) ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    saved.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = { open = false; onProfile(s) },
                            trailingIcon = { TextButton(onClick = { open = false; onDelete(s.name) }) { Text("Delete") } },
                        )
                    }
                }
            }
            TextButton(onClick = { onProfile(EqProfile()) }, enabled = profile.bands.isNotEmpty() || profile.preampDb != 0f) { Text("Clear") }
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        EqCurve(profile, Modifier.fillMaxWidth().height(150.dp))

        Row {
            Text("Preamp", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(String.format(Locale.ROOT, "%+.1f dB", profile.preampDb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = profile.preampDb, valueRange = -30f..10f,
            onValueChange = { v -> onProfile(profile.copy(preampDb = (v * 2).roundToInt() / 2f)) },
        )

        profile.bands.forEachIndexed { i, band ->
            BandRow(i + 1, band, onChange = { b -> onProfile(profile.copy(bands = profile.bands.toMutableList().also { it[i] = b })) },
                onRemove = { onProfile(profile.copy(bands = profile.bands.filterIndexed { k, _ -> k != i })) })
        }
        TextButton(
            onClick = { onProfile(profile.copy(bands = profile.bands + EqBand())) },
            enabled = profile.bands.size < EqProfile.MAX_BANDS,
        ) { Text("Add filter") }
    }
}

@Composable
private fun BandRow(number: Int, band: EqBand, onChange: (EqBand) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$number", Modifier.width(20.dp), style = MaterialTheme.typography.labelMedium)
        Box {
            var open by remember { mutableStateOf(false) }
            Text(
                band.type.label + " ▾",
                Modifier.width(96.dp).clickable { open = true }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                EqFilterType.entries.forEach { t ->
                    DropdownMenuItem(text = { Text(t.label) }, onClick = { open = false; onChange(band.copy(type = t)) })
                }
            }
        }
        NumberField("Hz", band.freqHz, 0, Modifier.weight(1.2f)) { onChange(band.copy(freqHz = it).sanitized()) }
        if (band.type.hasGain) NumberField("dB", band.gainDb, 1, Modifier.weight(1f)) { onChange(band.copy(gainDb = it).sanitized()) }
        else Box(Modifier.weight(1f))
        NumberField("Q", band.q, 2, Modifier.weight(1f)) { onChange(band.copy(q = it).sanitized()) }
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

/** A number box: typing anything that isn't a number keeps it until it is; then it applies. */
@Composable
private fun NumberField(label: String, value: Float, decimals: Int, modifier: Modifier, onValue: (Float) -> Unit) {
    val shown = String.format(Locale.ROOT, "%.${decimals}f", value)
    var text by remember(shown) { mutableStateOf(shown) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()?.let(onValue)
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/** The EQ's response (preamp included), 20 Hz - 20 kHz on a log scale, 0 dB marked. */
@Composable
fun EqCurve(profile: EqProfile, modifier: Modifier) {
    val db = remember(profile) { profile.responseDb(EqProfile.plotFrequencies()) }
    CurveOf(db, modifier)
}

/** A response in dB at EqProfile.plotFrequencies(), drawn on a log frequency scale. */
@Composable
fun CurveOf(db: FloatArray, modifier: Modifier) {
    val freqs = remember(db.size) { EqProfile.plotFrequencies(db.size) }
    val range = maxOf(6f, ceil((db.maxOfOrNull { abs(it) } ?: 0f) + 1f))
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            fun x(hz: Float) = (ln(hz / 20f) / ln(1000f)) * size.width
            fun y(v: Float) = size.height / 2f - v / range * (size.height / 2f)
            for (hz in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
                drawLine(grid, Offset(x(hz), 0f), Offset(x(hz), size.height), 1f)
            }
            drawLine(zero, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1.5f)
            val path = Path()
            db.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(freqs[i]), y(v)) else path.lineTo(x(freqs[i]), y(v)) }
            drawPath(path, line, style = Stroke(width = 2.5f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("20 Hz", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = zero)
            Text("±${range.roundToInt()} dB", style = MaterialTheme.typography.labelSmall, color = zero)
            Text("20 kHz", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = zero, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/**
 * The convolution EQ: on/off, the filter (an impulse response WAV, e.g. AutoEq's "minimum phase"
 * files or your own, or a GraphicEQ), its curve, and a gain. [curveDb] is the filter's response at
 * EqProfile.plotFrequencies() (the caller works it out off the UI thread; null while it does).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConvolutionEqEditor(
    on: Boolean,
    filter: ConvolutionFilter,
    gainDb: Float,
    curveDb: FloatArray?,
    notes: List<String>,
    onOn: (Boolean) -> Unit,
    onGain: (Float) -> Unit,
    onImportFile: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("On", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(checked = on, onCheckedChange = onOn, enabled = filter.isSet)
        }
        Text(
            "Applies an impulse response (a WAV, such as AutoEq's minimum phase files, or your own) or a " +
                "GraphicEQ, after the parametric EQ. Adds about 5 ms of delay.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filter.isSet) {
            Text(filter.name.ifBlank { "Unnamed" }, fontWeight = FontWeight.Medium)
            Text(filter.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("No filter yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onImportFile) { Text("Import file…") }
            TextButton(onClick = onPaste) { Text("Paste GraphicEQ") }
            TextButton(onClick = onClear, enabled = filter.isSet) { Text("Clear") }
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (filter.isSet) {
            if (curveDb != null) CurveOf(curveDb, Modifier.fillMaxWidth().height(150.dp))
            else Text("Working out the curve…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text("Gain", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(String.format(Locale.ROOT, "%+.1f dB", gainDb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = gainDb, valueRange = -30f..10f, onValueChange = { v -> onGain((v * 2).roundToInt() / 2f) })
        }
    }
}
