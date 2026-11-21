package com.allmeyn.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.allmeyn.player.audio.AllMeynRenderersFactory
import com.allmeyn.player.decode.DecodeRegistry
import com.allmeyn.player.decode.DecodingDataSource
import com.allmeyn.player.decode.DsdOutput
import com.allmeyn.player.dsp.DspSettings
import com.allmeyn.player.plugin.AudioCapturePlugin
import com.allmeyn.player.plugin.PluginHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays DSF, DFF, APE and WavPack through the app's real pipeline (DecodingDataSource → Media3
 * WavExtractor → AllMeynRenderersFactory sink → Android output) and checks that each one gets
 * ready with the expected format, plays, and seeks.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class DecodedPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as AllMeynApp
    private val main = Handler(Looper.getMainLooper())

    @Test fun dsfAsPcm() = play(dsdFile("t.dsf", dff = false), dop = false, rate = 176_400, encoding = C.ENCODING_PCM_FLOAT)

    @Test fun dffAsPcm() = play(dsdFile("t.dff", dff = true), dop = false, rate = 176_400, encoding = C.ENCODING_PCM_FLOAT)

    /** DoP to an Android output is muted by DopGuardOutput, but the pipeline must still run. */
    @Test fun dsfAsDop() = play(dsdFile("t2.dsf", dff = false), dop = true, rate = 176_400, encoding = C.ENCODING_PCM_24BIT)

    @Test fun ape16() = play(asset("clip16.ape"), dop = false, rate = 44_100, encoding = C.ENCODING_PCM_16BIT)

    @Test fun wavPack24() = play(asset("clip24.wv"), dop = false, rate = 44_100, encoding = C.ENCODING_PCM_24BIT)

    /** DSP on: integer audio (converted to float for the chain) and float audio (in place). */
    @Test fun dspOnIntegerAndFloatAudio() {
        DspSettings.update(context) {
            it.copy(enabled = true, companderOn = true, bassOn = true, impactOn = true, expansionPercent = -30f, softClip = true)
        }
        try {
            play(asset("clip16.ape"), dop = false, rate = 44_100, encoding = C.ENCODING_PCM_16BIT, expectDsp = true)
            play(dsdFile("t4.dsf", dff = false), dop = false, rate = 176_400, encoding = C.ENCODING_PCM_FLOAT, expectDsp = true)
        } finally {
            DspSettings.update(context) { com.allmeyn.player.dsp.DspParams() }
        }
    }

    /**
     * Native DSD through Media3: 4-channel 16-bit at DSD rate / 32. Checks every byte Media3 hands
     * the sink is the file's DSD, untouched (oldest byte first per channel). The Android output
     * itself is muted by DopGuardOutput.
     */
    @Test fun dsfAsNative() {
        val file = dsdFile("t3.dsf", dff = false)
        val captured = java.io.ByteArrayOutputStream()
        var seenFormat: com.allmeyn.player.plugin.PcmFormat? = null
        val tap = object : AudioCapturePlugin {
            override val name = "test tap"
            override fun onConfigure(format: com.allmeyn.player.plugin.PcmFormat) { seenFormat = format }
            override fun onBuffer(buffer: ByteBuffer, presentationTimeUs: Long) {
                if (captured.size() < 8 * 20_000) {
                    val b = ByteArray(buffer.remaining()); buffer.get(b); captured.write(b)
                }
            }
        }
        PluginHost.register(tap)
        try {
            play(file, dop = true, rate = 88_200, encoding = C.ENCODING_PCM_16BIT, nativeMaxRate = 24_576_000, channels = 4)
        } finally {
            PluginHost.unregister(tap)
        }
        assertTrue("plugin format: $seenFormat", seenFormat?.isNativeDsd == true)
        val got = captured.toByteArray()
        assertTrue("captured ${got.size} bytes", got.size >= 8 * 10_000)
        val bits = lastBits!!
        for (f in 0 until 10_000) for (c in 0..1) for (k in 0..3) {
            assertEquals("frame $f ch $c byte $k", bits[c][4 * f + k], got[8 * f + 4 * c + k])
        }
    }

    private fun play(
        file: File, dop: Boolean, rate: Int, encoding: Int, nativeMaxRate: Int = 0, channels: Int = 2,
        expectDsp: Boolean = false,
    ) {
        lateinit var player: ExoPlayer
        val ready = CountDownLatch(1)
        var error: PlaybackException? = null
        sync {
            player = ExoPlayer.Builder(context, AllMeynRenderersFactory(context, app.usbDac, app.usbDriver))
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        DecodingDataSource.Factory(context, DefaultDataSource.Factory(context)) { DsdOutput(dop, nativeMaxRate) },
                    ),
                )
                .build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    ready.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
        }
        try {
            assertTrue("${file.name}: not ready in 15 s", ready.await(15, TimeUnit.SECONDS))
            assertNull("${file.name}: ${error?.errorCodeName} ${error?.cause}", error)
            var format: androidx.media3.common.Format? = null
            sync { format = player.audioFormat }
            assertEquals("${file.name} sample rate", rate, format?.sampleRate)
            assertEquals("${file.name} encoding", encoding, format?.pcmEncoding)
            assertEquals("${file.name} channels", channels, format?.channelCount)
            assertEquals(dop && nativeMaxRate == 0, DecodeRegistry.dopActive)
            assertEquals(nativeMaxRate > 0, DecodeRegistry.nativeActive)

            sync { player.play() }
            Thread.sleep(1200)
            var pos = 0L
            sync { pos = player.currentPosition; assertNull(player.playerError) }
            assertTrue("${file.name}: position didn't advance ($pos ms)", pos > 400)
            if (expectDsp) {
                val m = DspSettings.meters
                assertTrue("${file.name}: DSP chain didn't run", m != null)
                assertTrue("${file.name}: DSP output peak ${m!!.outputPeak}", m.outputPeak > 0f && m.outputPeak <= 1f)
            } else {
                assertNull("${file.name}: DSP ran while off", DspSettings.meters)
            }

            sync { player.seekTo(1000) }
            Thread.sleep(600)
            sync {
                assertNull(player.playerError)
                assertTrue("${file.name}: seek landed at ${player.currentPosition}", player.currentPosition >= 1000)
            }
        } finally {
            sync { player.release() }
        }
    }

    private fun sync(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        main.post {
            try { block() } catch (t: Throwable) { failure = t } finally { done.countDown() }
        }
        done.await(10, TimeUnit.SECONDS)
        failure?.let { throw it }
    }

    private fun asset(name: String): File {
        val out = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        return out
    }

    /** The DSD bits (MSB = oldest) of the last [dsdFile], per channel. */
    private var lastBits: Array<ByteArray>? = null

    /** 3 s of DSD64 stereo (1 kHz / 3 kHz sines through a 2nd-order sigma-delta modulator). */
    private fun dsdFile(name: String, dff: Boolean): File {
        val rate = 2_822_400
        val bytesPerCh = rate * 3 / 8
        val bits = Array(2) { ByteArray(bytesPerCh) }
        for (c in 0..1) {
            var i1 = 0.0; var i2 = 0.0; var y = 0.0
            val f = if (c == 0) 1000.0 else 3000.0
            for (n in 0 until bytesPerCh * 8) {
                val x = 0.5 * sin(2 * PI * f * n / rate)
                i1 += x - y; i2 += i1 - y
                y = if (i2 >= 0) 1.0 else -1.0
                if (y > 0) bits[c][n / 8] = (bits[c][n / 8].toInt() or (0x80 ushr (n % 8))).toByte()
            }
        }
        lastBits = bits
        val out = File(context.cacheDir, name)
        FileOutputStream(out).use { os ->
            if (dff) {
                val be = { size: Int -> ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN) }
                val dsd = bytesPerCh.toLong() * 2
                val prop = 4L + (12 + 4) + (12 + 2 + 8) + (12 + 4 + 2)
                val total = 4L + (12 + 4) + (12 + prop) + (12 + dsd)
                os.write(be(16).put("FRM8".toByteArray()).putLong(total).put("DSD ".toByteArray()).array())
                os.write(be(16).put("FVER".toByteArray()).putLong(4).putInt(0x01050000).array())
                os.write(be(16).put("PROP".toByteArray()).putLong(prop).put("SND ".toByteArray()).array())
                os.write(be(16).put("FS  ".toByteArray()).putLong(4).putInt(rate).array())
                os.write(be(22).put("CHNL".toByteArray()).putLong(10).putShort(2).put("SLFTSRGT".toByteArray()).array())
                os.write(be(18).put("CMPR".toByteArray()).putLong(6).put("DSD ".toByteArray()).put(0).put(0).array())
                os.write(be(12).put("DSD ".toByteArray()).putLong(dsd).array())
                val inter = ByteArray(bytesPerCh * 2)
                for (i in 0 until bytesPerCh) { inter[2 * i] = bits[0][i]; inter[2 * i + 1] = bits[1][i] }
                os.write(inter)
            } else {
                val block = 4096
                val blocks = (bytesPerCh + block - 1) / block
                val data = blocks.toLong() * block * 2
                val le = { size: Int -> ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN) }
                os.write(le(28).put("DSD ".toByteArray()).putLong(28).putLong(92 + data).putLong(0).array())
                os.write(
                    le(52).put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2)
                        .putInt(rate).putInt(8).putLong(bytesPerCh * 8L).putInt(block).putInt(0).array(),
                )
                os.write(le(12).put("data".toByteArray()).putLong(12 + data).array())
                val buf = ByteArray(block)
                for (b in 0 until blocks) for (c in 0..1) {
                    java.util.Arrays.fill(buf, 0x69)
                    val from = b * block
                    System.arraycopy(bits[c], from, buf, 0, minOf(block, bytesPerCh - from))
                    os.write(buf)
                }
            }
        }
        return out
    }
}
