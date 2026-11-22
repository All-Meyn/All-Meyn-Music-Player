package com.allmeyn.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The AM mark as drawn in an Android vector drawable: viewport and one path. */
data class BrandVector(val viewportWidth: Float, val viewportHeight: Float, val pathData: String)

/**
 * The AM mark, read from the Android app's ic_brand_mark.xml (copied into the resources by the
 * build), so desktop and Android always draw the same logo.
 */
object BrandMark {
    val vector: ImageVector by lazy {
        val xml = BrandMark::class.java.getResourceAsStream("/ic_brand_mark.xml")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("ic_brand_mark.xml missing from resources")
        toImageVector(parse(xml))
    }

    /** The viewport and path of a single-path vector drawable. */
    fun parse(xml: String): BrandVector {
        fun attr(name: String) = Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
            ?: error("ic_brand_mark.xml has no $name")
        return BrandVector(attr("viewportWidth").toFloat(), attr("viewportHeight").toFloat(), attr("pathData"))
    }

    fun toImageVector(v: BrandVector): ImageVector =
        ImageVector.Builder(
            name = "BrandMark",
            defaultWidth = (48f * v.viewportWidth / v.viewportHeight).dp.coerceAtMost(96.dp),
            defaultHeight = 48.dp,
            viewportWidth = v.viewportWidth,
            viewportHeight = v.viewportHeight,
        ).addPath(pathData = addPathNodes(v.pathData), fill = SolidColor(Color.White)).build()
}
