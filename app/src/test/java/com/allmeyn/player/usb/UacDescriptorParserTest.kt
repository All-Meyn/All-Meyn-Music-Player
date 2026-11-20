package com.allmeyn.player.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Builds a minimal UAC2 configuration like the FiiO KA17's and checks what the parser finds. */
class UacDescriptorParserTest {

    private fun bytes(vararg v: Int) = v.map { it.toByte() }

    private fun config(featureControls: List<Long>?): ByteArray {
        val d = mutableListOf<Byte>()
        d += bytes(9, 0x02, 0, 0, 2, 1, 0, 0x80, 50)                       // config
        d += bytes(9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0)                  // AC interface, UAC2
        d += bytes(8, 0x24, 0x0A, 41, 0x03, 0x07, 0, 0)                    // clock source #41
        d += bytes(17, 0x24, 0x02, 2, 0x01, 0x01, 0, 41, 2, 0, 0, 0, 0, 0, 0, 0, 0) // IT #2 USB streaming
        if (featureControls != null) {
            val fu = mutableListOf<Byte>()
            fu += bytes(6 + featureControls.size * 4, 0x24, 0x06, 10, 2)  // FU #10 <- IT #2
            featureControls.forEach { c -> fu += bytes((c and 0xFF).toInt(), ((c shr 8) and 0xFF).toInt(), ((c shr 16) and 0xFF).toInt(), ((c shr 24) and 0xFF).toInt()) }
            fu += bytes(0)
            d += fu
        }
        d += bytes(9, 0x04, 1, 0, 0, 0x01, 0x02, 0x20, 0)                  // AS alt 0
        d += bytes(9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0)                  // AS alt 1
        d += bytes(16, 0x24, 0x01, 2, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)  // AS_GENERAL: link #2, PCM, 2 ch
        d += bytes(6, 0x24, 0x02, 1, 4, 32)                                // FORMAT_TYPE_I 4-byte / 32-bit
        d += bytes(7, 0x05, 0x01, 0x05, 0x08, 0x03, 1)                     // EP 0x01 iso async, 776 B
        d += bytes(7, 0x05, 0x81, 0x11, 0x04, 0x00, 4)                     // EP 0x81 feedback
        d += bytes(9, 0x02, 0, 0, 2, 2, 0, 0x80, 50)                       // 2nd config: must be ignored
        d += bytes(9, 0x04, 9, 1, 2, 0x01, 0x02, 0x20, 0)
        return d.toByteArray()
    }

    @Test fun parsesKa17LikeLayout() {
        val r = UacDescriptorParser.parse(config(listOf(0x0FL, 0, 0)))
        assertEquals(2, r.uacVersion)
        assertEquals(listOf(41), r.clockSources.map { it.id })
        assertEquals(1, r.playbackAlts.size) // second configuration ignored
        val alt = r.playbackAlts.single()
        assertEquals(32, alt.bitResolution)
        assertEquals(4, alt.subslotBytes)
        assertEquals(776, alt.dataEndpoint!!.maxPacketSize)
        assertEquals(0x81, alt.feedbackEndpoint!!.address)
    }

    @Test fun masterVolumeAndMute() {
        val v = UacDescriptorParser.parse(config(listOf(0x0FL, 0, 0))).playbackVolume()
        assertNotNull(v)
        assertEquals(10, v!!.unitId)
        assertEquals(listOf(0), v.channels)
        assertEquals(listOf(0), v.muteChannels)
    }

    @Test fun perChannelVolumeOnly() {
        val v = UacDescriptorParser.parse(config(listOf(0x03L, 0x0CL, 0x0CL))).playbackVolume()
        assertEquals(listOf(1, 2), v!!.channels)
        assertEquals(listOf(0), v.muteChannels)
    }

    @Test fun readOnlyVolumeIsIgnored() {
        assertNull(UacDescriptorParser.parse(config(listOf(0x04L, 0x04L, 0x04L))).playbackVolume())
        assertNull(UacDescriptorParser.parse(config(null)).playbackVolume())
    }
}
