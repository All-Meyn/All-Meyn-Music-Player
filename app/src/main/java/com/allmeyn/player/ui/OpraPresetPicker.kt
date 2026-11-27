package com.allmeyn.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.allmeyn.player.dsp.OpraPreset
import com.allmeyn.player.dsp.OpraPresets

// Shared by the Android and Windows DSP screens (desktop/build.gradle.kts, sharedSources).

/**
 * Pick a headphone EQ preset from OPRA's bundled list: search, then tap one. [presets] is null while
 * the list loads ([error] says why it couldn't). Carries OPRA's attribution (logo, description, link) as its README asks; each
 * preset's creator is shown first.
 */
@Composable
fun OpraPresetPicker(presets: List<OpraPreset>?, error: String?, logo: ImageBitmap?, onPick: (OpraPreset) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(presets, query) { presets?.let { OpraPresets.search(it, query) } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Find your headphones", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Brand or model, e.g. HD 650") }, modifier = Modifier.fillMaxWidth(),
                )
                when {
                    error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    shown == null -> Text("Loading presets…", style = MaterialTheme.typography.bodySmall)
                    shown.isEmpty() -> Text("No presets match.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(shown) { p ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp)) {
                                Text(p.headphone, fontWeight = FontWeight.Medium)
                                Text(
                                    "Preset created by ${p.author}" + (if (p.details.isNotBlank()) " · ${p.details}" else "") +
                                        (if (p.kind.isNotBlank()) " · ${p.kind}" else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                if (shown != null && presets != null && shown.size < presets.size && shown.size == 200) {
                    Text("Showing the first 200: type more to narrow it down.", style = MaterialTheme.typography.bodySmall)
                }
                OpraAttribution(logo)
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/** OPRA's logo, description and a link to the project (their README's attribution). */
@Composable
fun OpraAttribution(logo: ImageBitmap?) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (logo != null) {
            Image(logo, contentDescription = "OPRA", modifier = Modifier.height(22.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
        }
        Text(OpraPresets.DESCRIPTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "More information or to contribute",
                Modifier.clickable { runCatching { uri.openUri(OpraPresets.REPO_URL) } }.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
            Text(" · ", style = MaterialTheme.typography.bodySmall)
            Text(
                "CC BY-SA 4.0",
                Modifier.clickable { runCatching { uri.openUri(OpraPresets.LICENSE_URL) } }.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
