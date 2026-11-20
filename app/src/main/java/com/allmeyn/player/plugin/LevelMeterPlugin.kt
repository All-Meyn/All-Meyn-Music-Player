package com.allmeyn.player.plugin

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Example plugin: tracks the peak level of the captured audio (0.0..1.0).
 * Replace or add your own plugin alongside it in AllMeynApp.
 */
class LevelMeterPlugin : AudioCapturePlugin {
    override val name = "Level meter"

    @Volatile
    var peak: Float = 0f
        private set

    @Volatile
    var formatLabel: String = "—"
        private set

    private var encoding = C.ENCODING_INVALID

    override fun onConfigure(format: PcmFormat) {
        encoding = format.encoding
        val bits = when (format.encoding) {
            C.ENCODING_PCM_8BIT -> "8-bit"
            C.ENCODING_PCM_16BIT -> "16-bit"
            C.ENCODING_PCM_24BIT -> "24-bit"
            C.ENCODING_PCM_32BIT -> "32-bit"
            C.ENCODING_PCM_FLOAT -> "32-bit float"
            else -> "enc ${format.encoding}"
        }
        formatLabel = "${format.sampleRate} Hz · $bits · ${format.channelCount} ch"
    }

    override fun onBuffer(buffer: ByteBuffer, presentationTimeUs: Long) {
        buffer.order(ByteOrder.LITTLE_ENDIAN) // host restores the original order afterwards
        var max = 0f
        val start = buffer.position()
        val end = buffer.limit()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = start
                while (i + 1 < end) {
                    val s = abs(buffer.getShort(i).toInt()) / 32768f
                    if (s > max) max = s
                    i += 2
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = start
                while (i + 3 < end) {
                    val s = abs(buffer.getFloat(i))
                    if (s > max) max = s
                    i += 4
                }
            }
            C.ENCODING_PCM_24BIT -> {
                var i = start
                while (i + 2 < end) {
                    // Little-endian packed 24-bit.
                    val v = (buffer.get(i).toInt() and 0xFF) or
                        ((buffer.get(i + 1).toInt() and 0xFF) shl 8) or
                        (buffer.get(i + 2).toInt() shl 16)
                    val s = abs(v) / 8_388_608f
                    if (s > max) max = s
                    i += 3
                }
            }
            C.ENCODING_PCM_32BIT -> {
                var i = start
                while (i + 3 < end) {
                    val s = abs(buffer.getInt(i).toFloat()) / 2_147_483_648f
                    if (s > max) max = s
                    i += 4
                }
            }
            else -> return
        }
        peak = max.coerceAtMost(1f)
    }

    override fun onFlush() {
        peak = 0f
    }
}
