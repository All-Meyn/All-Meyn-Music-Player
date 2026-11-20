package com.allmeyn.player.library

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * APEv2 tags (used by Monkey's Audio and WavPack): a 32-byte "APETAGEX" footer at the end of the
 * file (possibly followed by a 128-byte ID3v1 tag), preceded by the items.
 */
object ApeTag {
    class Result(val text: Map<String, String>, val cover: ByteArray?)

    /** How many bytes from the end of the file to read to be sure to see the footer. */
    const val FOOTER_SEARCH = 32 + 128

    /**
     * [tail] is the end of the file. Returns the tag size in bytes (items + footer) if a footer is
     * found, else -1. Caller then reads that many bytes before the footer end and calls [parse].
     */
    fun locate(tail: ByteArray): Pair<Int, Int>? {
        for (footerAt in listOf(tail.size - 32, tail.size - 32 - 128)) {
            if (footerAt < 0) continue
            if (String(tail, footerAt, 8, Charsets.US_ASCII) != "APETAGEX") continue
            val b = ByteBuffer.wrap(tail, footerAt, 32).order(ByteOrder.LITTLE_ENDIAN)
            b.position(footerAt + 12)
            val size = b.int // items + footer, excluding header
            val count = b.int
            if (size < 32 || count < 0 || count > 1000) return null
            // Offset of the footer's end, counted back from the end of the file.
            return Pair(size, tail.size - (footerAt + 32))
        }
        return null
    }

    /** [block] = the [size] bytes ending at the footer end (items then the 32-byte footer). */
    fun parse(block: ByteArray): Result {
        val text = mutableMapOf<String, String>()
        var cover: ByteArray? = null
        val footer = ByteBuffer.wrap(block, block.size - 32, 32).order(ByteOrder.LITTLE_ENDIAN)
        footer.position(block.size - 32 + 16)
        val count = footer.int
        var p = 0
        val end = block.size - 32
        repeat(count) {
            if (p + 8 > end) return Result(text, cover)
            val b = ByteBuffer.wrap(block, p, 8).order(ByteOrder.LITTLE_ENDIAN)
            val valueSize = b.int
            val flags = b.int
            var k = p + 8
            while (k < end && block[k] != 0.toByte()) k++
            if (k >= end || valueSize < 0 || k + 1 + valueSize > end) return Result(text, cover)
            val key = String(block, p + 8, k - (p + 8), Charsets.US_ASCII).lowercase()
            val vStart = k + 1
            val isBinary = (flags shr 1) and 0x3 == 1
            if (isBinary) {
                if (key == "cover art (front)" || (cover == null && key.startsWith("cover art"))) {
                    // "<file name>\0<image bytes>"
                    var z = vStart
                    while (z < vStart + valueSize && block[z] != 0.toByte()) z++
                    if (z < vStart + valueSize) cover = block.copyOfRange(z + 1, vStart + valueSize)
                }
            } else {
                // UTF-8; multiple values are separated by \0 — keep the first.
                text[key] = String(block, vStart, valueSize, Charsets.UTF_8).substringBefore('\u0000')
            }
            p = vStart + valueSize
        }
        return Result(text, cover)
    }
}
