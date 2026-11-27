package com.allmeyn.player.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpraPresetsTest {
    // The file both apps bundle (unit tests run in the app module's folder).
    private val all by lazy { File("src/main/assets/opra/presets.tsv.gz").inputStream().use(OpraPresets::read) }

    @Test fun readsALine() {
        val p = OpraPresets.parseLine("Sennheiser\tHD 650\tOver-ear\tAutoEQ\tMeasured by oratory1990\t-6.1\tLSC 105 6.4 0.7;PK 8800 5.1 1.42;LPQ 12000 0 0.707")!!
        assertEquals("Sennheiser HD 650", p.headphone)
        assertEquals("Sennheiser HD 650 (AutoEQ, oratory1990)", p.profile.name)
        assertEquals(-6.1f, p.profile.preampDb)
        assertEquals(listOf(EqBand(EqFilterType.LOW_SHELF, 105f, 6.4f, 0.7f), EqBand(EqFilterType.PEAK, 8800f, 5.1f, 1.42f), EqBand(EqFilterType.LOW_PASS, 12000f, 0f, 0.707f)), p.profile.bands)
    }

    @Test fun skipsWhatItCannotRead() {
        assertNull(OpraPresets.parseLine("# comment"))
        assertNull(OpraPresets.parseLine("A\tB\tC\tD\tE\t0"))
        assertNull(OpraPresets.parseLine("A\tB\tC\tD\tE\t0\tXX 100 1 1"))
        assertNull(OpraPresets.parseLine("A\tB\tC\tD\tE\tzero\tPK 100 1 1"))
    }

    @Test fun theBundledListIsComplete() {
        assertTrue("${all.size} presets", all.size > 12_000)
        // oratory1990's measurement via AutoEq: the same numbers as AutoEq's own HD 650 file (EqProfileTest).
        val hd650 = all.first { it.headphone == "Sennheiser HD 650" && it.author == "AutoEQ" && it.details == "Measured by oratory1990" }
        assertEquals(-6.1f, hd650.profile.preampDb)
        assertEquals(EqBand(EqFilterType.LOW_SHELF, 105f, 6.4f, 0.7f), hd650.profile.bands[0])
        assertEquals(10, hd650.profile.bands.size)
        assertTrue(all.all { it.profile.bands.size in 1..EqProfile.MAX_BANDS })
        assertTrue(all.any { it.author == "oratory1990" } && all.any { it.author == "Rtings/AutoEQ" })
    }

    // The Android build unzips the asset (the app holds plain text); Windows keeps it gzipped.
    @Test fun readsPlainOrGzipped() {
        val plain = java.util.zip.GZIPInputStream(File("src/main/assets/opra/presets.tsv.gz").inputStream()).use { it.readBytes() }
        assertEquals(all.size, OpraPresets.read(plain.inputStream()).size)
        try { OpraPresets.read("# nothing\n".byteInputStream()); org.junit.Assert.fail() } catch (_: java.io.IOException) {}
    }

    @Test fun search() {
        val hits = OpraPresets.search(all, "hd 650")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().headphone, hits.first().headphone.startsWith("Sennheiser HD 650"))
        assertTrue(OpraPresets.search(all, "sennheiser oratory1990").all { it.vendor == "Sennheiser" })
        assertEquals(200, OpraPresets.search(all, "").size)
        assertTrue(OpraPresets.search(all, "zzzz-no-such-thing").isEmpty())
    }
}
