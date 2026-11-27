package com.allmeyn.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.allmeyn.player.dsp.OpraPresets
import com.allmeyn.player.ui.openOpraPresets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The OPRA presets as the installed app sees them (the build changes how assets are stored). */
@RunWith(AndroidJUnit4::class)
class OpraAssetTest {
    @Test fun theAppCanReadItsPresets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.assets.list("opra")?.toList()
        val presets = openOpraPresets(context).use { OpraPresets.read(it) }
        assertTrue("assets/opra: $files, ${presets.size} presets", presets.size > 12_000)
        assertTrue(presets.any { it.headphone == "Sennheiser HD 650" })
        assertTrue(OpraPresets.search(presets, "HD 650").first().headphone.startsWith("Sennheiser HD 650"))
    }
}
