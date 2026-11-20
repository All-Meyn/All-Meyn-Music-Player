package com.allmeyn.player.decode

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds an RF64 (64-bit WAV, EBU Tech 3306) header so decoded audio of any length can be played
 * and seeked by Media3's WavExtractor. Layout: RF64 · WAVE · ds64 · fmt · data.
 */
object Rf64 {
    const val HEADER_SIZE = 80

    fun header(sampleRate: Int, channels: Int, format: Int, totalFrames: Long): ByteArray {
        val bytesPerSample = DecodedFormat.sampleBytes(format)
        val blockAlign = channels * bytesPerSample
        val dataSize = totalFrames * blockAlign
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RF64".toByteArray()).putInt(-1).put("WAVE".toByteArray())
        b.put("ds64".toByteArray()).putInt(28)
        b.putLong(HEADER_SIZE - 8 + dataSize) // RIFF size
        b.putLong(dataSize)
        b.putLong(totalFrames)
        b.putInt(0) // table length
        b.put("fmt ".toByteArray()).putInt(16)
        b.putShort((if (format == DecodedFormat.F32) 3 else 1).toShort()) // IEEE float / PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort((bytesPerSample * 8).toShort())
        b.put("data".toByteArray()).putInt(-1)
        check(b.position() == HEADER_SIZE)
        return b.array()
    }
}
