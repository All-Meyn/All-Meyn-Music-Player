package com.allmeyn.player.library

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.allmeyn.player.decode.DecodedFormat
import com.allmeyn.player.decode.NativeDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads tags, duration, quality and embedded cover art.
 * - DSF: ID3v2 at the header's metadata pointer; DFF: an "ID3 " chunk if present.
 * - APE / WavPack: APEv2 tags at the end of the file.
 * - DSD / APE / WavPack duration and quality come from our own decoders.
 * - Everything else: Android's MediaMetadataRetriever.
 */
@UnstableApi
object TagReader {
    class Tags {
        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var trackNo = 0
        var discNo = 0
        var year = 0
        var durationMs = 0L
        var format = ""
        var sampleRate = 0
        var bits = 0
        var cover: ByteArray? = null
        /** Other text tags by lower-case name (ID3 TXXX descriptions, APEv2 keys), e.g. ReplayGain. */
        val extra = HashMap<String, String>()
    }

    /**
     * Free-form text tags (ReplayGain etc.) of the formats Media3 can't read tags from during
     * playback (DSF, DFF, APE, WavPack); empty for anything else.
     */
    fun extraTags(pfd: ParcelFileDescriptor, extension: String): Map<String, String> {
        val t = Tags()
        try {
            when (extension) {
                "dsf" -> dsfId3(pfd, t)
                "dff" -> dffId3(pfd, t)
                "ape", "wv" -> apeTags(pfd, t)
            }
        } catch (e: Exception) {
            Log.w(TAG, "extra tags: ${e.message}")
        }
        return t.extra
    }

    private const val TAG = "TagReader"
    private const val MAX_TAG_BYTES = 16 shl 20

    fun read(pfd: ParcelFileDescriptor, extension: String): Tags {
        val t = Tags()
        try {
            when (extension) {
                "dsf", "dff" -> {
                    nativeInfo(pfd, t)
                    if (extension == "dsf") dsfId3(pfd, t) else dffId3(pfd, t)
                }
                "ape", "wv" -> {
                    nativeInfo(pfd, t)
                    apeTags(pfd, t)
                }
                else -> retriever(pfd, t, extension)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tags: ${e.message}")
        }
        return t
    }

    private fun nativeInfo(pfd: ParcelFileDescriptor, t: Tags) {
        // dop=true: cheapest open for DSD (no filter tables); format is only used for the label.
        val h = NativeDecoder.open(pfd.fd, true, 0)
        if (h == NativeDecoder.NOT_OURS || h == NativeDecoder.OPEN_ERROR) return
        try {
            val info = NativeDecoder.info(h)
            val rate = info[0].toInt()
            val frames = info[3]
            if (rate > 0) t.durationMs = frames * 1000 / rate
            val desc = NativeDecoder.describe(h)
            if (info[4] != 0L) {
                // "DSF DSD64 → ..." -> "DSF DSD64"
                t.format = desc.substringBefore(" →")
                val dsdClass = Regex("DSD(\\d+)").find(desc)?.groupValues?.get(1)?.toIntOrNull() ?: 64
                t.sampleRate = dsdClass / 64 * 2_822_400
                t.bits = 1
            } else {
                t.format = desc.substringBefore(' ')
                t.sampleRate = rate
                t.bits = when (info[2].toInt()) {
                    DecodedFormat.S16 -> 16
                    DecodedFormat.S24 -> 24
                    else -> 32
                }
            }
        } finally {
            NativeDecoder.close(h)
        }
    }

    /** Positional read that leaves the descriptor open (a stream's close() would close it). */
    private fun readAt(pfd: ParcelFileDescriptor, pos: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = Os.pread(pfd.fileDescriptor, buf, done, len - done, pos + done)
            if (n <= 0) break
            done += n
        }
        return if (done == len) buf else buf.copyOf(done)
    }

    private fun fileSize(pfd: ParcelFileDescriptor) = pfd.statSize

    private fun dsfId3(pfd: ParcelFileDescriptor, t: Tags) {
        val head = readAt(pfd, 0, 28)
        if (head.size < 28) return
        val ptr = ByteBuffer.wrap(head, 20, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val size = fileSize(pfd)
        if (ptr <= 0 || ptr >= size) return
        val len = (size - ptr).coerceAtMost(MAX_TAG_BYTES.toLong()).toInt()
        id3(readAt(pfd, ptr, len), t)
    }

    private fun dffId3(pfd: ParcelFileDescriptor, t: Tags) {
        // Top-level chunks after "FRM8"<size>"DSD ": look for a (non-standard but common) "ID3 " chunk.
        val size = fileSize(pfd)
        var pos = 16L
        while (pos + 12 <= size) {
            val h = readAt(pfd, pos, 12)
            if (h.size < 12) return
            val id = String(h, 0, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(h, 4, 8).order(ByteOrder.BIG_ENDIAN).long
            if (len < 0 || len > size - pos - 12) return // damaged size: stop, don't wrap around
            if (id == "ID3 ") {
                id3(readAt(pfd, pos + 12, len.coerceAtMost(MAX_TAG_BYTES.toLong()).toInt()), t)
                return
            }
            pos += 12 + len + (len and 1)
        }
    }

    private fun id3(bytes: ByteArray, t: Tags) {
        val metadata = Id3Decoder().decode(bytes, bytes.size) ?: return
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is TextInformationFrame -> {
                    val v = e.values.firstOrNull()?.trim().orEmpty()
                    when (e.id) {
                        "TIT2", "TT2" -> t.title = v
                        "TPE1", "TP1" -> t.artist = v
                        "TALB", "TAL" -> t.album = v
                        "TPE2", "TP2" -> t.albumArtist = v
                        "TRCK", "TRK" -> t.trackNo = LibraryIndex.parseNumber(v)
                        "TPOS", "TPA" -> t.discNo = LibraryIndex.parseNumber(v)
                        "TDRC", "TYER", "TYE", "TDRL" -> if (t.year == 0) t.year = LibraryIndex.parseYear(v)
                        "TXXX" -> e.description?.let { d -> t.extra[d.lowercase()] = v }
                    }
                }
                is ApicFrame -> if (t.cover == null || e.pictureType == 3) t.cover = e.pictureData
            }
        }
    }

    private fun apeTags(pfd: ParcelFileDescriptor, t: Tags) {
        val size = fileSize(pfd)
        if (size < 32) return
        val tailLen = minOf(size, ApeTag.FOOTER_SEARCH.toLong()).toInt()
        val tail = readAt(pfd, size - tailLen, tailLen)
        val (tagSize, afterFooter) = ApeTag.locate(tail) ?: return
        if (tagSize > MAX_TAG_BYTES) return
        val block = readAt(pfd, size - afterFooter - tagSize, tagSize)
        val r = ApeTag.parse(block)
        t.extra.putAll(r.text)
        r.text["title"]?.let { t.title = it }
        r.text["artist"]?.let { t.artist = it }
        r.text["album"]?.let { t.album = it }
        (r.text["album artist"] ?: r.text["albumartist"])?.let { t.albumArtist = it }
        t.trackNo = LibraryIndex.parseNumber(r.text["track"])
        t.discNo = LibraryIndex.parseNumber(r.text["disc"])
        t.year = LibraryIndex.parseYear(r.text["year"] ?: r.text["date"])
        t.cover = r.cover
    }

    private fun retriever(pfd: ParcelFileDescriptor, t: Tags, extension: String) {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(pfd.fileDescriptor)
            fun key(k: Int) = m.extractMetadata(k)?.trim().orEmpty()
            t.title = key(MediaMetadataRetriever.METADATA_KEY_TITLE)
            t.artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            t.album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            t.albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            t.trackNo = LibraryIndex.parseNumber(key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            t.discNo = LibraryIndex.parseNumber(key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            t.year = LibraryIndex.parseYear(key(MediaMetadataRetriever.METADATA_KEY_YEAR).ifBlank { key(MediaMetadataRetriever.METADATA_KEY_DATE) })
            t.durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                t.sampleRate = key(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: 0
                t.bits = key(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE).toIntOrNull() ?: 0
            }
            t.cover = m.embeddedPicture
        } finally {
            m.release()
        }
        t.format = when (extension) {
            "m4a", "mp4", "alac" -> "M4A"
            "aif", "aiff" -> "AIFF"
            "oga", "ogg" -> "OGG"
            else -> extension.uppercase()
        }
    }
}
