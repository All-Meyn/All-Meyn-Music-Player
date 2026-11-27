package com.allmeyn.desktop.ui

import com.allmeyn.player.ui.AppGlass
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allmeyn.desktop.engine.DspStore
import com.allmeyn.desktop.library.DesktopEqProfiles
import com.allmeyn.player.dsp.ConvKind
import com.allmeyn.player.dsp.ConvolutionFiles
import com.allmeyn.player.dsp.ConvolutionFilter
import com.allmeyn.player.dsp.EqProfile
import com.allmeyn.player.dsp.GraphicEqParser
import com.allmeyn.player.dsp.OpraPresets
import com.allmeyn.player.dsp.OutputEqStore
import com.allmeyn.player.dsp.OutputId
import com.allmeyn.player.ui.OutputEqSection
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.allmeyn.player.dsp.WavReader
import com.allmeyn.player.dsp.importEqFile
import com.allmeyn.player.ui.ConvolutionEqEditor
import com.allmeyn.player.ui.HeadphoneEqEditor
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import com.allmeyn.player.dsp.DspMeters
import com.allmeyn.player.dsp.DspParams
import com.allmeyn.player.dsp.DspProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.roundToInt

private val BAND_NAMES = listOf("LF", "LMF", "HMF", "HF")

/** OPRA's logo for its attribution (tinted to the theme where it's drawn). */
private val opraLogo: ImageBitmap? by lazy {
    runCatching {
        OpraPresets::class.java.getResourceAsStream("/opra_logo.png")?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    }.getOrNull()
}

/**
 * The DSP chain, as on Android: master switch, processor order (Vocal & Detail Restoration pinned
 * first; the others move up/down), each processor's settings and meters. Changes apply at once and
 * are saved.
 */
@Composable
fun DspScreen(params: DspParams, outputEqs: OutputEqStore, currentOutput: OutputId?, onChange: ((DspParams) -> DspParams) -> Unit) {
    var meters by remember { mutableStateOf<DspMeters?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            meters = withContext(Dispatchers.IO) { DspStore.meters() }
            delay(66)
        }
    }
    val p = params
    val eqStore = remember { DesktopEqProfiles() }
    var savedEqs by remember { mutableStateOf(eqStore.load()) }
    var eqNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var convNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // Stored impulse responses nothing uses any more (not the one in use, nor an output's) go.
    fun pruneResponses(inUse: String) {
        val keep = outputEqs.irFiles() + inUse
        scope.launch(Dispatchers.IO) { ConvolutionFiles.prune(keep) }
    }
    var outputsOn by remember { mutableStateOf(outputEqs.enabled) }
    var remembered by remember { mutableStateOf(outputEqs.outputs()) }
    LaunchedEffect(currentOutput, outputsOn) { remembered = outputEqs.outputs() }
    // Any EQ file, from either section (importEqFile sends it to the right one), read off the UI thread.
    fun importFile(read: () -> ByteArray?, fileName: String, fromConvolution: Boolean) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            if (bytes == null) {
                val msg = listOf("Couldn't open that file (or it's over 32 MB).")
                if (fromConvolution) convNotes = msg else eqNotes = msg
                return@launch
            }
            val r = withContext(Dispatchers.IO) { importEqFile(bytes, fileName, fromConvolution) }
            r.change?.let(onChange)
            r.eqNotes?.let { eqNotes = it }
            r.convNotes?.let { convNotes = it }
            r.change?.let { pruneResponses(it(p).conv.irFile) }
        }
    }
    fun pickFile(title: String, fromConvolution: Boolean) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true  // modal
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) {
            importFile({ File(dir, name).takeIf { it.length() <= WavReader.MAX_BYTES }?.readBytes() }, name, fromConvolution)
        }
    }
    fun pasteText(fromConvolution: Boolean) {
        val text = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (text.isNullOrBlank()) {
            val msg = listOf("The clipboard has no text.")
            if (fromConvolution) convNotes = msg else eqNotes = msg
        } else importFile({ text.encodeToByteArray() }, "", fromConvolution)
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle("DSP")
            Section("DSP", on = p.enabled, toggle = { v -> onChange { it.copy(enabled = v) } }) {
                Note(
                    "Processes the music, so it's no longer bit-perfect while on (also with exclusive mode or ASIO). " +
                        "DSD plays as PCM while the DSP is on. Off = untouched audio again.",
                )
            }

            Text("Chain", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Section(null) {
                ChainRow(1, DspProcessor.VOCAL.label, "Always first", p.vocalOn, p.enabled, { v -> onChange { it.copy(vocalOn = v) } })
                // The other three: drag the ≡ handle to change the order (as on Android).
                val movable = p.order.filter { it != DspProcessor.VOCAL }
                val listState = rememberLazyListState()
                val reorder = rememberReorderState(movable, listState) { it.name }
                LazyColumn(Modifier.fillMaxWidth().height(53.dp * movable.size), state = listState, userScrollEnabled = false) {
                    items(reorder.rows, key = { it.name }) { proc ->
                        Column(reorderRow(reorder, proc)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ChainRow(
                                reorder.rows.indexOf(proc) + 2, proc.label, null, isOn(p, proc), p.enabled, { v -> onChange { setOn(it, proc, v) } },
                                handle = { DragHandle(reorder, proc) { from, to -> onChange { moveTo(it, from, to) } } },
                            )
                        }
                    }
                }
            }

            Section(DspProcessor.VOCAL.label, on = p.enabled && p.vocalOn) {
                LabeledSlider("Presence", "${p.presencePercent.roundToInt()} %", p.presencePercent, 0f..100f, 1f) { v -> onChange { it.copy(presencePercent = v) } }
                LabeledSlider("Detail", "${p.detailPercent.roundToInt()} %", p.detailPercent, 0f..100f, 1f) { v -> onChange { it.copy(detailPercent = v) } }
                LabeledSlider("Air", "${p.airPercent.roundToInt()} %", p.airPercent, 0f..100f, 1f) { v -> onChange { it.copy(airPercent = v) } }
                Note(
                    "Works on the centre of the mix, where vocals sit. Presence: crisper consonants and attacks. " +
                        "Detail: lifts quiet passages a little. Air: adds sparkle above 9 kHz, and on " +
                        "MP3/AAC fills in the treble the encoder cut off.",
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
            p.order.filter { it != DspProcessor.VOCAL }.forEach { proc ->
                when (proc) {
                    DspProcessor.COMPANDER -> Section(proc.label, on = p.enabled && p.companderOn) {
                        LabeledSlider(
                            "Dynamics", "${signed(p.expansionPercent)} % · ${p.ratioLabel}" +
                                if (p.expansionPercent < 0) " (compress)" else if (p.expansionPercent > 0) " (expand)" else "",
                            p.expansionPercent, -50f..50f, 1f,
                        ) { v -> onChange { it.copy(expansionPercent = v) } }
                        LabeledSlider("Transition", "${p.transitionDb.roundToInt()} dBFS", p.transitionDb, -40f..0f, 1f) { v -> onChange { it.copy(transitionDb = v) } }
                        LabeledSlider("HF Trim", "${signed(p.hfTransitionDb)} dB", p.hfTransitionDb, -10f..10f, 0.5f) { v -> onChange { it.copy(hfTransitionDb = v) } }
                        BandMeters(meters?.companderDb, -20f, 12f)
                    }
                    DspProcessor.BASS -> Section(proc.label, on = p.enabled && p.bassOn) {
                        LabeledSlider("Amount", p.bassLabel, p.bassPercent, 0f..100f, 1f) { v -> onChange { it.copy(bassPercent = v) } }
                        LabeledSlider("Bass Transition", "${p.bassTransitionDb.roundToInt()} dBFS", p.bassTransitionDb, -40f..0f, 1f) { v -> onChange { it.copy(bassTransitionDb = v) } }
                        GainMeter("Bass", meters?.bassDb ?: 0f, -20f, 12f)
                    }
                    DspProcessor.IMPACT -> Section(proc.label, on = p.enabled && p.impactOn) {
                        LabeledSlider("Impact", p.impactLabel, p.impactPercent, 0f..100f, 1f) { v -> onChange { it.copy(impactPercent = v) } }
                        LabeledSlider("Release", "${p.impactReleaseMs.roundToInt()} ms", p.impactReleaseMs, 25f..1500f, 5f) { v -> onChange { it.copy(impactReleaseMs = v) } }
                        BandMeters(meters?.impactDb, 0f, 12f)
                    }
                    DspProcessor.VOCAL -> Unit
                }
            }
            Section("Per output", on = p.enabled && outputsOn) {
                OutputEqSection(
                    enabled = outputsOn, current = currentOutput, remembered = remembered,
                    onEnabled = { v ->
                        outputEqs.setEnabled(v)
                        currentOutput?.let { outputEqs.remember(it, p) }
                        outputsOn = v
                        remembered = outputEqs.outputs()
                    },
                    onForget = { o -> outputEqs.forget(o.key); remembered = outputEqs.outputs(); pruneResponses(p.conv.irFile) },
                )
            }
            Section("Headphone EQ", on = p.enabled && p.eqActive) {
                HeadphoneEqEditor(
                    on = p.eqOn, profile = p.eq, notes = eqNotes, saved = savedEqs,
                    onOn = { v -> onChange { it.copy(eqOn = v) } },
                    onProfile = { e -> eqNotes = emptyList(); onChange { it.copy(eq = e) } },
                    onImportFile = { pickFile("Import EQ (ParametricEQ.txt)", fromConvolution = false) },
                    onPaste = { pasteText(fromConvolution = false) },
                    onCopy = {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(p.eq.toApoText()), null)
                        eqNotes = listOf("Copied as EqualizerAPO text.")
                    },
                    onSave = { savedEqs = eqStore.save(p.eq); eqNotes = listOf("Saved “${p.eq.name}”.") },
                    onDelete = { name -> savedEqs = eqStore.delete(name) },
                    loadPresets = {
                        withContext(Dispatchers.IO) {
                            OpraPresets.load { OpraPresets::class.java.getResourceAsStream("/opra_presets.tsv.gz") ?: error("presets missing") }
                        }
                    },
                    opraLogo = opraLogo,
                )
            }
            Section("Convolution EQ", on = p.enabled && p.convActive) {
                ConvolutionEqEditor(
                    on = p.convOn, filter = p.conv, gainDb = p.convGainDb, curveDb = convCurve, notes = convNotes,
                    onOn = { v -> onChange { it.copy(convOn = v) } },
                    onGain = { g -> onChange { it.copy(convGainDb = g) } },
                    onImportFile = { pickFile("Import impulse response (WAV) or GraphicEQ", fromConvolution = true) },
                    onPaste = { pasteText(fromConvolution = true) },
                    onClear = {
                        convNotes = emptyList()
                        onChange { it.copy(conv = ConvolutionFilter(), convOn = false) }
                        pruneResponses("")
                    },
                )
            }
            Section("Output", on = p.enabled) {
                LabeledSlider("Volume", "${signed(p.volumeDb)} dB", p.volumeDb, -40f..10f, 0.5f) { v -> onChange { it.copy(volumeDb = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Soft Clip (peaks never exceed 0 dBFS)", Modifier.weight(1f))
                    Switch(checked = p.softClip, onCheckedChange = { v -> onChange { it.copy(softClip = v) } })
                }
                val peak = meters?.outputPeak ?: 0f
                val peakDb = if (peak > 0f) 20 * log10(peak) else -100f
                Text(
                    if (meters == null) "Output peak: —" else "Output peak: %.1f dBFS".format(peakDb) + if (peak > 1f) "  OVER" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peak > 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            OutlinedButton(onClick = { onChange { DspParams(enabled = it.enabled) } }, glass = AppGlass.regular) { Text("Reset to defaults") }
        }
    }
}

/** Moves the movable processor at [from] to [to] (positions after Vocal, which stays first). */
internal fun moveTo(p: DspParams, from: Int, to: Int): DspParams {
    val rest = p.order.filter { it != DspProcessor.VOCAL }.toMutableList()
    if (from !in rest.indices || to !in rest.indices || from == to) return p
    rest.add(to, rest.removeAt(from))
    return p.copy(order = listOf(DspProcessor.VOCAL) + rest)
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
private fun Section(title: String?, on: Boolean = true, toggle: ((Boolean) -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface,  // (not derivable from a translucent colour)
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (toggle != null) Switch(checked = on, onCheckedChange = toggle)
            }
            content()
        }
    }
}

@Composable
private fun ChainRow(
    position: Int, label: String, detail: String?, on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit,
    handle: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$position", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        handle?.invoke()
        Switch(checked = on, onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledSlider(
    label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current, valueRange = range,
            onValueChange = { v -> onChange((Math.round(v / step) * step).coerceIn(range.start, range.endInclusive)) },
        )
    }
}

@Composable
private fun BandMeters(values: FloatArray?, minDb: Float, maxDb: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BAND_NAMES.forEachIndexed { i, name ->
            Box(Modifier.weight(1f)) { GainMeter(name, values?.getOrNull(i) ?: 0f, minDb, maxDb) }
        }
    }
}

/** One bar per meter: down (yellow) / up (red) gain in dB, as on Android and Tetra-Expand's meters. */
@Composable
private fun GainMeter(name: String, db: Float, minDb: Float, maxDb: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val up = db > 0f
        val frac = if (up) (db / maxDb).coerceIn(0f, 1f) else if (minDb < 0f) (db / minDb).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (up) MeterUp else MeterDown))
        }
        Spacer(Modifier.height(2.dp))
        Text("$name ${signed((db * 10).roundToInt() / 10f)}", style = MaterialTheme.typography.labelSmall)
    }
}

private val MeterUp = Color(0xFFE53935)    // boost (red), the Android colours
private val MeterDown = Color(0xFFFDD835)  // cut (yellow)
