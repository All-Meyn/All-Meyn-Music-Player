package com.allmeyn.player.decode

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.min

/**
 * Plays formats Media3 can't read (DSF, DFF, APE, WavPack): decodes them natively and serves the
 * result as an RF64 WAV byte stream that Media3's WavExtractor plays and seeks. Seeking works
 * because a byte position in the WAV maps to an exact frame in the decoder. Everything else is
 * passed to [upstream] untouched.
 *
 * DSD becomes DoP (bit-exact DSD for the DAC) when [dopAllowed] says so at the time the file is
 * first opened, otherwise PCM. The choice sticks for that file until it's reloaded, since the
 * already-parsed WAV header must stay valid for later seeks.
 */
@UnstableApi
class DecodingDataSource(
    private val context: Context,
    private val upstream: DataSource,
    private val dopAllowed: () -> Boolean,
) : DataSource {

    class Factory(
        private val context: Context,
        private val upstream: DataSource.Factory,
        private val dopAllowed: () -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            DecodingDataSource(context.applicationContext, upstream.createDataSource(), dopAllowed)
    }

    private var handle = 0L
    private var delegating = false
    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var headerPos = 0
    private var bytesRemaining = 0L
    private val scratch = ByteArray(64 * 1024)
    private var scratchLen = 0
    private var scratchPos = 0
    private var skipInFrame = 0

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = try {
        openInternal(dataSpec)
    } catch (e: Throwable) {
        DecodeRegistry.lastError = "${dataSpec.uri.lastPathSegment}: ${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, "open failed for ${dataSpec.uri}", e)
        throw if (e is IOException) e else IOException(e)
    }

    private fun openInternal(dataSpec: DataSpec): Long {
        val fromStart = dataSpec.position == 0L
        val dop = (if (fromStart) null else DecodeRegistry.dopFor(dataSpec.uri)) ?: dopAllowed()
        val h = openNative(dataSpec.uri, dop)
        if (h == 0L) {
            delegating = true
            if (fromStart) DecodeRegistry.onPlainFile()
            return upstream.open(dataSpec)
        }
        if (h < 0) throw IOException("Can't decode ${dataSpec.uri.lastPathSegment}: ${NativeDecoder.lastError()}")
        handle = h
        uri = dataSpec.uri

        val info = NativeDecoder.info(h)
        val rate = info[0].toInt()
        val channels = info[1].toInt()
        val format = info[2].toInt()
        val frames = info[3]
        DecodeRegistry.onOpened(dataSpec.uri, info[4] != 0L, info[5] != 0L, NativeDecoder.describe(h), fromStart)

        header = Rf64.header(rate, channels, format, frames)
        val frameBytes = channels * DecodedFormat.sampleBytes(format)
        val total = header.size + frames * frameBytes
        val pos = dataSpec.position.coerceIn(0, total)
        if (pos < header.size) {
            headerPos = pos.toInt()
            NativeDecoder.seek(h, 0)
        } else {
            headerPos = header.size
            val dataOffset = pos - header.size
            NativeDecoder.seek(h, dataOffset / frameBytes)
            skipInFrame = (dataOffset % frameBytes).toInt()
        }
        bytesRemaining = total - pos
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) bytesRemaining = min(bytesRemaining, dataSpec.length)
        return bytesRemaining
    }

    /** 0 = not ours, <0 = ours but failed, >0 = handle. Only local files (content:// or file://). */
    private fun openNative(uri: Uri, dop: Boolean): Long {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT && uri.scheme != ContentResolver.SCHEME_FILE) return 0
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return 0
        return pfd.use { NativeDecoder.open(it.fd, dop) } // native keeps its own dup of the fd
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegating) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
        val want = min(length.toLong(), bytesRemaining).toInt()

        if (headerPos < header.size) {
            val n = min(want, header.size - headerPos)
            System.arraycopy(header, headerPos, buffer, offset, n)
            headerPos += n
            bytesRemaining -= n
            return n
        }
        if (scratchPos >= scratchLen) {
            val n = NativeDecoder.read(handle, scratch, 0, scratch.size)
            if (n < 0) {
                DecodeRegistry.lastError = "Decode error in ${uri?.lastPathSegment}"
                throw IOException("Decode error in ${uri?.lastPathSegment}")
            }
            if (n == 0) return C.RESULT_END_OF_INPUT // decoder ended a little early: fine
            scratchLen = n
            scratchPos = min(skipInFrame, n)
            skipInFrame = 0
        }
        val n = min(want, scratchLen - scratchPos)
        System.arraycopy(scratch, scratchPos, buffer, offset, n)
        scratchPos += n
        bytesRemaining -= n
        return n
    }

    private companion object {
        const val TAG = "DecodingDataSource"
    }

    override fun getUri(): Uri? = if (delegating) upstream.uri else uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (delegating) upstream.responseHeaders else emptyMap()

    override fun close() {
        if (delegating) {
            delegating = false
            upstream.close()
        }
        if (handle > 0) NativeDecoder.close(handle)
        handle = 0
        uri = null
        header = ByteArray(0)
        headerPos = 0
        scratchLen = 0
        scratchPos = 0
        skipInFrame = 0
        bytesRemaining = 0
    }
}
