package com.allmeyn.player.ui

import android.content.Context
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
import com.allmeyn.player.dsp.ConvKind
import com.allmeyn.player.dsp.ConvolutionFiles
import com.allmeyn.player.dsp.ConvolutionFilter
import com.allmeyn.player.dsp.importEqFile
import com.allmeyn.player.dsp.EqProfile
import com.allmeyn.player.dsp.GraphicEqParser
import com.allmeyn.player.dsp.OpraPresets
import com.allmeyn.player.playback.OutputMonitor
import androidx.compose.runtime.collectAsState
import com.allmeyn.player.R
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import com.allmeyn.player.dsp.WavReader
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allmeyn.player.dsp.EqProfileStore
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.log10
import kotlin.math.roundToInt

private val BAND_NAMES = listOf("LF", "LMF", "HMF", "HF")

/** The bundled OPRA presets: the build stores them unzipped as presets.tsv; the .gz name is taken too, in case a build keeps it. */
internal fun openOpraPresets(context: Context): java.io.InputStream =
    runCatching { context.assets.open(OpraPresets.ASSET) }.getOrElse { context.assets.open(OpraPresets.ASSET + ".gz") }

/**
 * The DSP chain: master switch, processor order (drag), and each processor's settings and meters.
 * Changes apply immediately and are saved.
 */
@Composable
fun DspScreen(params: DspParams, outputs: OutputMonitor, onChange: ((DspParams) -> DspParams) -> Unit) {
    var meters by remember { mutableStateOf<DspMeters?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            meters = DspSettings.meters
            delay(66)
        }
    }
    val p = params
    val context = LocalContext.current
    val eqStore = remember { EqProfileStore(context.applicationContext) }
    var savedEqs by remember { mutableStateOf(eqStore.load()) }
    var eqNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var convNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // Stored impulse responses nothing uses any more (not the one in use, nor an output's) go.
    fun pruneResponses() {
        val keep = outputs.store.irFiles() + DspSettings.live.conv.irFile
        scope.launch(Dispatchers.IO) { ConvolutionFiles.prune(keep) }
    }
    // Any EQ file, from either section (see importEqFile). Files are read and stored off the UI thread.
    fun importFile(bytes: ByteArray, fileName: String, fromConvolution: Boolean) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { importEqFile(bytes, fileName, fromConvolution) }
            r.change?.let(onChange)
            r.eqNotes?.let { eqNotes = it }
            r.convNotes?.let { convNotes = it }
            if (r.change != null) pruneResponses()
        }
    }
    val currentOutput by outputs.current.collectAsState()
    var outputsOn by remember { mutableStateOf(outputs.store.enabled) }
    var remembered by remember { mutableStateOf(outputs.store.outputs()) }
    LaunchedEffect(currentOutput, outputsOn) { remembered = outputs.store.outputs() }
    var importingForConvolution by remember { mutableStateOf(false) }
    val openEq = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= WavReader.MAX_BYTES } }
        }.getOrNull()
        if (bytes == null) {
            val msg = listOf("Couldn't open that file (or it's over 32 MB).")
            if (importingForConvolution) convNotes = msg else eqNotes = msg
        } else importFile(bytes, name, importingForConvolution)
    }
    // The convolution filter's curve (a WAV's response takes a moment: worked out off the UI thread).
    var convCurve by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(p.conv, p.convGainDb) {
        convCurve = null
        val f = p.conv
        convCurve = withContext(Dispatchers.Default) {
            when (f.kind) {
                ConvKind.GRAPHIC -> GraphicEqParser.responseDb(f.graphic, EqProfile.plotFrequencies(), p.convGainDb)
                ConvKind.IMPULSE -> ConvolutionFiles.load(f.irFile)?.responseDb(EqProfile.plotFrequencies(), p.convGainDb)
                ConvKind.NONE -> null
            }
        }
    }
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val opraLogo = remember { runCatching { ImageBitmap.imageResource(context.resources, R.drawable.opra_logo) }.getOrNull() }
    val listState = rememberLazyListState()
    // The processor order is the draggable part of the list; everything else is fixed items.
    // Vocal & Detail Restoration is pinned first, so only the others are draggable.
    val movable = remember(p.order) { p.order.filter { it != DspProcessor.VOCAL } }
    val reorder = rememberReorderState(movable, listState) { it.name }

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
        item(key = "vocalPinned") {
            PanelCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("1", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(DspProcessor.VOCAL.label, fontWeight = FontWeight.Medium)
                        Text("Always first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.vocalOn, onCheckedChange = { v -> onChange { it.copy(vocalOn = v) } }, enabled = p.enabled)
                }
            }
        }
        itemsIndexed(reorder.rows, key = { _, proc -> proc.name }) { i, proc ->
            PanelCard(reorderRow(reorder, proc).fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 2}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
                    Text(proc.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(checked = isOn(p, proc), onCheckedChange = { v -> onChange { setOn(it, proc, v) } }, enabled = p.enabled)
                    DragHandle(reorder, proc) { from, to ->
                        onChange { cur ->
                            val rest = cur.order.filter { it != DspProcessor.VOCAL }.toMutableList().apply { add(to, removeAt(from)) }
                            cur.copy(order = listOf(DspProcessor.VOCAL) + rest)
                        }
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
        item(key = "vocal") {
            Section(DspProcessor.VOCAL.label, on = p.enabled && p.vocalOn) {
                LabeledSlider("Presence", "${p.presencePercent.roundToInt()} %", p.presencePercent, 0f..100f, 1f) { v -> onChange { it.copy(presencePercent = v) } }
                LabeledSlider("Detail", "${p.detailPercent.roundToInt()} %", p.detailPercent, 0f..100f, 1f) { v -> onChange { it.copy(detailPercent = v) } }
                LabeledSlider("Air", "${p.airPercent.roundToInt()} %", p.airPercent, 0f..100f, 1f) { v -> onChange { it.copy(airPercent = v) } }
                Text(
                    "Works on the centre of the mix, where vocals sit. Presence: crisper consonants and attacks. " +
                        "Detail: lifts quiet passages a little. Air: adds sparkle above 9 kHz, and on " +
                        "MP3/AAC fills in the treble the encoder cut off.",
                    style = MaterialTheme.typography.bodySmall,
                )
                GainMeter("Vocal", meters?.vocalDb ?: 0f, -20f, 6f)
                val cutoff = meters?.airCutoffHz
                if (p.enabled && p.vocalOn && cutoff != null) {
                    Text(
                        if (cutoff > 0f) "Air source: lossy, treble ends near ${"%.1f".format(cutoff / 1000f)} kHz (Air fills above it)"
                        else "Air source: full range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        item(key = "outputs") {
            Section("Per output", on = p.enabled && outputsOn) {
                OutputEqSection(
                    enabled = outputsOn, current = currentOutput, remembered = remembered,
                    onEnabled = { v -> outputs.setEnabled(v); outputsOn = v; remembered = outputs.store.outputs() },
                    onForget = { o -> outputs.store.forget(o.key); remembered = outputs.store.outputs(); pruneResponses() },
                )
            }
        }
        item(key = "eq") {
            Section("Headphone EQ", on = p.enabled && p.eqActive) {
                HeadphoneEqEditor(
                    on = p.eqOn, profile = p.eq, notes = eqNotes, saved = savedEqs,
                    onOn = { v -> onChange { it.copy(eqOn = v) } },
                    onProfile = { e -> eqNotes = emptyList(); onChange { it.copy(eq = e) } },
                    onImportFile = { importingForConvolution = false; openEq.launch(arrayOf("text/*", "audio/*", "application/octet-stream")) },
                    onPaste = {
                        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrBlank()) eqNotes = listOf("The clipboard has no text.") else importFile(text.encodeToByteArray(), "", false)
                    },
                    onCopy = {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("EQ", p.eq.toApoText()))
                        eqNotes = listOf("Copied as EqualizerAPO text.")
                    },
                    onSave = { savedEqs = eqStore.save(p.eq); eqNotes = listOf("Saved “${p.eq.name}”.") },
                    onDelete = { name -> savedEqs = eqStore.delete(name) },
                    loadPresets = { withContext(Dispatchers.IO) { OpraPresets.load { openOpraPresets(context) } } },
                    opraLogo = opraLogo,
                )
            }
        }
        item(key = "conv") {
            Section("Convolution EQ", on = p.enabled && p.convActive) {
                ConvolutionEqEditor(
                    on = p.convOn, filter = p.conv, gainDb = p.convGainDb, curveDb = convCurve, notes = convNotes,
                    onOn = { v -> onChange { it.copy(convOn = v) } },
                    onGain = { g -> onChange { it.copy(convGainDb = g) } },
                    onImportFile = { importingForConvolution = true; openEq.launch(arrayOf("audio/*", "text/*", "application/octet-stream")) },
                    onPaste = {
                        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrBlank()) convNotes = listOf("The clipboard has no text.") else importFile(text.encodeToByteArray(), "", true)
                    },
                    onClear = {
                        convNotes = emptyList()
                        onChange { it.copy(conv = ConvolutionFilter(), convOn = false) }
                        pruneResponses()
                    },
                )
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
            OutlinedButton(onClick = { onChange { DspParams(enabled = it.enabled) } }, glass = AppGlass.regular) { Text("Reset to defaults") }
        }
    }
}

private fun isOn(p: DspParams, proc: DspProcessor) = when (proc) {
    DspProcessor.COMPANDER -> p.companderOn
    DspProcessor.BASS -> p.bassOn
    DspProcessor.IMPACT -> p.impactOn
    DspProcessor.VOCAL -> p.vocalOn
}

private fun setOn(p: DspParams, proc: DspProcessor, on: Boolean) = when (proc) {
    DspProcessor.COMPANDER -> p.copy(companderOn = on)
    DspProcessor.BASS -> p.copy(bassOn = on)
    DspProcessor.IMPACT -> p.copy(impactOn = on)
    DspProcessor.VOCAL -> p.copy(vocalOn = on)
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
