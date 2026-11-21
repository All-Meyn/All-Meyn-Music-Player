package com.allmeyn.player.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApeTagTest {
    private fun item(key: String, value: ByteArray, binary: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(value.size).putInt(if (binary) 2 else 0).array())
        out.write(key.toByteArray(Charsets.US_ASCII)); out.write(0); out.write(value)
        return out.toByteArray()
    }

    private fun tag(items: List<ByteArray>): ByteArray {
        val body = items.fold(ByteArray(0)) { a, b -> a + b }
        val footer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .put("APETAGEX".toByteArray()).putInt(2000).putInt(body.size + 32).putInt(items.size).putInt(0).putLong(0).array()
        return body + footer
    }

    private val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    private val tagBytes = tag(listOf(
        item("Title", "Rhythm of Love".toByteArray(), false),
        item("Artist", "Yes".toByteArray(), false),
        item("Album", "Big Generator".toByteArray(), false),
        item("Track", "1/8".toByteArray(), false),
        item("Year", "1987".toByteArray(), false),
        item("Album Artist", "Yës".toByteArray(Charsets.UTF_8), false),
        item("Cover Art (Front)", "front.jpg".toByteArray() + 0 + cover, true),
    ))

    @Test fun parsesTextAndCover() {
        val file = ByteArray(1000) + tagBytes
        val (size, after) = ApeTag.locate(file.copyOfRange(file.size - ApeTag.FOOTER_SEARCH, file.size))!!
        assertEquals(tagBytes.size, size)
        assertEquals(0, after)
        val r = ApeTag.parse(file.copyOfRange(file.size - after - size, file.size - after))
        assertEquals("Rhythm of Love", r.text["title"])
        assertEquals("Yes", r.text["artist"])
        assertEquals("Big Generator", r.text["album"])
        assertEquals(1, LibraryIndex.parseNumber(r.text["track"]))
        assertEquals("Yës", r.text["album artist"])
        assertArrayEquals(cover, r.cover)
    }

    @Test fun findsFooterBeforeId3v1() {
        val id3v1 = "TAG".toByteArray() + ByteArray(125)
        val file = ByteArray(500) + tagBytes + id3v1
        val (size, after) = ApeTag.locate(file.copyOfRange(file.size - ApeTag.FOOTER_SEARCH, file.size))!!
        assertEquals(128, after)
        assertEquals("Yes", ApeTag.parse(file.copyOfRange(file.size - after - size, file.size - after)).text["artist"])
    }

    @Test fun noTag() {
        assertNull(ApeTag.locate(ByteArray(ApeTag.FOOTER_SEARCH)))
    }

    @Test fun truncatedItemsDontCrash() {
        val bad = tagBytes.copyOf()
        // Corrupt the first item's value size to run past the end.
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 1_000_000)
        val r = ApeTag.parse(bad)
        assertEquals(0, r.text.size)
    }

    @Test fun hugeValueSizeIsRejectedNotWrapped() {
        // A damaged item claims Int.MAX_VALUE - 4 bytes: k + 1 + size used to wrap negative and
        // pass the bounds check (then throw). It must just end parsing, keeping earlier items.
        val good = item("Title", "Ok".toByteArray(), false)
        val bad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE - 4).putInt(0).array() +
            "Artist".toByteArray(Charsets.US_ASCII) + 0 + "x".toByteArray()
        val r = ApeTag.parse(tag(listOf(good, bad)))
        assertEquals("Ok", r.text["title"])
        assertNull(r.text["artist"])
    }
}
