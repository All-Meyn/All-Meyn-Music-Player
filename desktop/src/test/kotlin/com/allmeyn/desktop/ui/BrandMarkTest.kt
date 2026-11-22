package com.allmeyn.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrandMarkTest {
    @Test fun readsTheAndroidVector() {
        val xml = javaClass.getResourceAsStream("/ic_brand_mark.xml")!!.bufferedReader().readText()
        val v = BrandMark.parse(xml)
        assertEquals(48f, v.viewportWidth)
        assertEquals(25.28f, v.viewportHeight)
        assertTrue(v.pathData.startsWith("M"), v.pathData.take(20))
        assertTrue(BrandMark.vector.root.iterator().hasNext())
    }
}
