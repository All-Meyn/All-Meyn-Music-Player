package com.allmeyn.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The player's control icons, drawn directly (no icon font or library needed). */
enum class ControlIcon { PLAY, PAUSE, PREVIOUS, NEXT, QUEUE, SHUFFLE, REPEAT, REPEAT_ONE, DRAG }

@Composable
fun ControlGlyph(icon: ControlIcon, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val h = w
        fun triangle(x0: Float, x1: Float, pointRight: Boolean) = Path().apply {
            if (pointRight) { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h) } else { moveTo(x1, 0f); lineTo(x0, h / 2); lineTo(x1, h) }
            close()
        }
        when (icon) {
            ControlIcon.PLAY -> drawPath(triangle(w * 0.18f, w, true), color)
            ControlIcon.PAUSE -> {
                drawRect(color, Offset(w * 0.12f, 0f), Size(w * 0.3f, h))
                drawRect(color, Offset(w * 0.58f, 0f), Size(w * 0.3f, h))
            }
            ControlIcon.PREVIOUS -> {
                drawRect(color, Offset(0f, 0f), Size(w * 0.14f, h))
                drawPath(triangle(w * 0.14f, w, false), color)
            }
            ControlIcon.NEXT -> {
                drawPath(triangle(0f, w * 0.86f, true), color)
                drawRect(color, Offset(w * 0.86f, 0f), Size(w * 0.14f, h))
            }
            ControlIcon.QUEUE -> {
                val stroke = w * 0.12f
                for (i in 0..2) {
                    val y = h * (0.2f + 0.3f * i)
                    drawLine(color, Offset(0f, y), Offset(if (i == 2) w * 0.55f else w, y), stroke, StrokeCap.Round)
                }
                // a small "play" mark beside the shorter last line
                drawPath(Path().apply { moveTo(w * 0.7f, h * 0.62f); lineTo(w, h * 0.8f); lineTo(w * 0.7f, h * 0.98f); close() }, color)
            }
            ControlIcon.DRAG -> {
                // ≡ (the drag handle)
                val stroke = w * 0.11f
                for (y in listOf(h * 0.28f, h * 0.5f, h * 0.72f)) drawLine(color, Offset(w * 0.1f, y), Offset(w * 0.9f, y), stroke, StrokeCap.Round)
            }
            ControlIcon.SHUFFLE -> {
                // two crossing lines, each ending in an arrowhead on the right
                val stroke = w * 0.1f
                val style = Stroke(stroke, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(0f, h * 0.25f); cubicTo(w * 0.45f, h * 0.25f, w * 0.45f, h * 0.75f, w * 0.85f, h * 0.75f) }, color, style = style)
                drawPath(Path().apply { moveTo(0f, h * 0.75f); cubicTo(w * 0.45f, h * 0.75f, w * 0.45f, h * 0.25f, w * 0.85f, h * 0.25f) }, color, style = style)
                for (y in listOf(h * 0.25f, h * 0.75f)) {
                    drawPath(Path().apply { moveTo(w * 0.72f, y - h * 0.16f); lineTo(w, y); lineTo(w * 0.72f, y + h * 0.16f); close() }, color)
                }
            }
            ControlIcon.REPEAT, ControlIcon.REPEAT_ONE -> {
                // a loop: top line to the right, bottom line to the left, arrowheads at both ends
                val stroke = w * 0.1f
                val style = Stroke(stroke, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * 0.1f, h * 0.55f); lineTo(w * 0.1f, h * 0.25f); lineTo(w * 0.78f, h * 0.25f) }, color, style = style)
                drawPath(Path().apply { moveTo(w * 0.9f, h * 0.45f); lineTo(w * 0.9f, h * 0.75f); lineTo(w * 0.22f, h * 0.75f) }, color, style = style)
                drawPath(Path().apply { moveTo(w * 0.72f, h * 0.08f); lineTo(w, h * 0.25f); lineTo(w * 0.72f, h * 0.42f); close() }, color)
                drawPath(Path().apply { moveTo(w * 0.28f, h * 0.58f); lineTo(0f, h * 0.75f); lineTo(w * 0.28f, h * 0.92f); close() }, color)
                if (icon == ControlIcon.REPEAT_ONE) {
                    // a small "1" in the middle
                    drawLine(color, Offset(w * 0.5f, h * 0.36f), Offset(w * 0.5f, h * 0.64f), stroke * 0.8f, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.42f, h * 0.43f), Offset(w * 0.5f, h * 0.36f), stroke * 0.8f, StrokeCap.Round)
                }
            }
        }
    }
}
