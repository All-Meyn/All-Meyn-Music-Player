package com.allmeyn.player.usb

/**
 * Parses the raw USB configuration descriptors of a USB Audio Class 1.0 / 2.0 device into the
 * facts the driver needs: clock sources, and for each streaming alternate setting its channel
 * count, bit depth, isochronous endpoint and sync type.
 */
object UacDescriptorParser {

    data class Endpoint(
        val address: Int,
        val maxPacketSize: Int,
        val interval: Int,
        val syncType: Int, // 0 none, 1 async, 2 adaptive, 3 sync
        val isFeedback: Boolean,
    ) {
        val isOut get() = address and 0x80 == 0
    }

    data class StreamingAlt(
        val interfaceNumber: Int,
        val altSetting: Int,
        val uacVersion: Int, // 1 or 2
        var terminalLink: Int = -1,
        var channels: Int = 0,
        var subslotBytes: Int = 0,
        var bitResolution: Int = 0,
        var formatBits: Long = 0, // UAC2 bmFormats
        val discreteRates: MutableList<Int> = mutableListOf(), // UAC1 only
        val endpoints: MutableList<Endpoint> = mutableListOf(),
    ) {
        /** UAC2 bmFormats bit 0 = PCM; UAC1 format type I is always PCM. Others are DSD/raw. */
        val isPcm get() = uacVersion == 1 || formatBits and 1L != 0L
        val dataEndpoint get() = endpoints.firstOrNull { it.isOut && !it.isFeedback }
        val feedbackEndpoint get() = endpoints.firstOrNull { it.isFeedback }
    }

    data class ClockSource(val id: Int, val attributes: Int)

    data class Result(
        val uacVersion: Int,
        val controlInterface: Int,
        val clockSources: List<ClockSource>,
        val playbackAlts: List<StreamingAlt>,
    )

    private const val DESC_CONFIG = 0x02
    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24
    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_CONTROL = 0x01
    private const val SUBCLASS_STREAMING = 0x02
    private const val PROTOCOL_UAC2 = 0x20

    fun parse(raw: ByteArray): Result {
        var uacVersion = 1
        var controlInterface = -1
        val clocks = mutableListOf<ClockSource>()
        val alts = mutableListOf<StreamingAlt>()

        var inControl = false
        var currentAlt: StreamingAlt? = null

        var configsSeen = 0
        var i = 0
        while (i + 1 < raw.size) {
            val len = raw.u8(i)
            if (len < 2 || i + len > raw.size) break
            when (raw.u8(i + 1)) {
                DESC_CONFIG -> {
                    // Android returns every configuration; only parse the first (active) one.
                    if (++configsSeen > 1) break
                }
                DESC_INTERFACE -> {
                    val number = raw.u8(i + 2)
                    val alt = raw.u8(i + 3)
                    val cls = raw.u8(i + 5)
                    val sub = raw.u8(i + 6)
                    val proto = raw.u8(i + 7)
                    inControl = false
                    currentAlt = null
                    if (cls == CLASS_AUDIO && sub == SUBCLASS_CONTROL) {
                        inControl = true
                        controlInterface = number
                        if (proto == PROTOCOL_UAC2) uacVersion = 2
                    } else if (cls == CLASS_AUDIO && sub == SUBCLASS_STREAMING && alt != 0) {
                        // Alt 0 is the zero-bandwidth "off" setting.
                        currentAlt = StreamingAlt(number, alt, if (proto == PROTOCOL_UAC2) 2 else 1)
                            .also { alts += it }
                    }
                }
                DESC_CS_INTERFACE -> {
                    val subtype = raw.u8(i + 2)
                    val a = currentAlt
                    if (inControl && uacVersion == 2 && subtype == 0x0A && len >= 5) {
                        clocks += ClockSource(id = raw.u8(i + 3), attributes = raw.u8(i + 4))
                    } else if (a != null) {
                        parseStreamingCs(raw, i, len, subtype, a)
                    }
                }
                DESC_ENDPOINT -> currentAlt?.let { a ->
                    if (len >= 7) {
                        val attrs = raw.u8(i + 3)
                        a.endpoints += Endpoint(
                            address = raw.u8(i + 2),
                            maxPacketSize = raw.u16(i + 4) and 0x7FF,
                            interval = raw.u8(i + 6),
                            syncType = (attrs shr 2) and 0x3,
                            isFeedback = (attrs shr 4) and 0x3 == 1,
                        )
                    }
                }
            }
            i += len
        }

        // Playback alts are the ones whose data endpoint is OUT (host -> DAC).
        val playback = alts.filter { a -> a.endpoints.any { it.isOut && !it.isFeedback } }
        return Result(uacVersion, controlInterface, clocks, playback)
    }

    private fun parseStreamingCs(raw: ByteArray, i: Int, len: Int, subtype: Int, a: StreamingAlt) {
        if (a.uacVersion == 2) {
            when (subtype) {
                0x01 -> if (len >= 16) { // AS_GENERAL
                    a.terminalLink = raw.u8(i + 3)
                    a.formatBits = raw.u32(i + 6)
                    a.channels = raw.u8(i + 10)
                }
                0x02 -> if (len >= 6 && raw.u8(i + 3) == 1) { // FORMAT_TYPE I
                    a.subslotBytes = raw.u8(i + 4)
                    a.bitResolution = raw.u8(i + 5)
                }
            }
        } else {
            when (subtype) {
                0x01 -> if (len >= 7) a.terminalLink = raw.u8(i + 3)
                0x02 -> if (len >= 8 && raw.u8(i + 3) == 1) {
                    a.channels = raw.u8(i + 4)
                    a.subslotBytes = raw.u8(i + 5)
                    a.bitResolution = raw.u8(i + 6)
                    val count = raw.u8(i + 7)
                    for (k in 0 until count) {
                        val off = i + 8 + k * 3
                        if (off + 2 < i + len) a.discreteRates += raw.u24(off)
                    }
                }
            }
        }
    }

    fun describe(r: Result): String = buildString {
        appendLine("USB Audio Class ${r.uacVersion}.0 · control interface ${r.controlInterface}")
        if (r.clockSources.isNotEmpty()) {
            appendLine("Clock sources: " + r.clockSources.joinToString { "#${it.id}" })
        }
        if (r.playbackAlts.isEmpty()) appendLine("No playback streaming interfaces found")
        for (a in r.playbackAlts) {
            val data = a.endpoints.firstOrNull { it.isOut && !it.isFeedback }
            val fb = a.endpoints.firstOrNull { it.isFeedback }
            append("If ${a.interfaceNumber} alt ${a.altSetting}: ${a.channels} ch · ")
            append("${a.bitResolution}-bit in ${a.subslotBytes}-byte slot · ${formatName(a)}")
            if (data != null) {
                append(" · EP 0x%02X %s · max %d B".format(data.address, syncName(data.syncType), data.maxPacketSize))
            }
            if (fb != null) append(" · feedback 0x%02X".format(fb.address))
            if (a.discreteRates.isNotEmpty()) append(" · rates ${a.discreteRates.joinToString()}")
            appendLine()
        }
    }

    private fun formatName(a: StreamingAlt): String = when {
        a.uacVersion == 1 -> "PCM"
        a.formatBits == 1L -> "PCM"
        a.formatBits and (1L shl 31) != 0L -> "RAW/DSD (0x%08X)".format(a.formatBits)
        else -> "formats 0x%08X".format(a.formatBits)
    }

    private fun syncName(t: Int) = when (t) {
        1 -> "async"
        2 -> "adaptive"
        3 -> "sync"
        else -> "no-sync"
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
    private fun ByteArray.u32(i: Int) = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
}
