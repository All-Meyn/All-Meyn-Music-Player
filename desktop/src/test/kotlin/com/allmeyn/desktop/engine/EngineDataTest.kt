package com.allmeyn.desktop.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineDataTest {
    @Test fun parsesDeviceRows() {
        val d = OutputDevice.parse("{0.0.0.00000000}.{abc}\tSpeakers (FiiO KA17)\t1\t48000\t32\t2")!!
        assertEquals("Speakers (FiiO KA17)", d.name)
        assertTrue(d.isDefault)
        assertEquals("48 kHz · 32-bit · 2 ch", d.mixLabel)
    }

    @Test fun rejectsMalformedRowsAndLabelsUnknownFormats() {
        assertNull(OutputDevice.parse("only\ttwo"))
        assertNull(OutputDevice.parse("\tname\t0\t0\t0\t0"))
        val d = OutputDevice.parse("id\t\t0\t0\t0\t0")!!
        assertEquals("Unnamed output", d.name)
        assertEquals("format unknown", d.mixLabel)
    }

    @Test fun asioOutputsAreMarkedAndAllowDopWithoutExclusive() {
        val asio = OutputSettings(deviceId = OutputSettings.asioId("FiiO ASIO Driver"), exclusive = false)
        assertTrue(asio.isAsio)
        assertTrue(asio.dopPossible)
        assertEquals("asio:FiiO ASIO Driver", asio.deviceId)
        val shared = OutputSettings(deviceId = "{0.0.0.00000000}.{abc}", exclusive = false)
        assertTrue(!shared.isAsio && !shared.dopPossible)
    }

    @Test fun formatsRates() {
        assertEquals("44.1 kHz", OutputDevice.formatRate(44100))
        assertEquals("352.8 kHz", OutputDevice.formatRate(352800))
        assertEquals("192 kHz", OutputDevice.formatRate(192000))
    }

    @Test fun summarizesFormatsInOrderWithoutRepeats() {
        val got = Formats.summarize(listOf(
            "decoder:mp3float", "decoder:flac", "decoder:mp3", "decoder:pcm_s24le", "decoder:pcm_s16be",
            "decoder:opus", "decoder:wavpack", "demuxer:ogg", "decoder:unknown", "decoder:allmeyn_dsd",
        ))
        assertEquals(listOf("FLAC", "WavPack", "MP3", "Opus", "WAV / AIFF", "DSD (DSF/DFF)"), got)
    }
}
