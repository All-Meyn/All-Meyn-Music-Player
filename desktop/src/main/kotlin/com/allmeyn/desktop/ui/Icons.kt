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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The player's control icons, drawn directly (no icon font or library needed). */
enum class ControlIcon { PLAY, PAUSE, PREVIOUS, NEXT, QUEUE }

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
        }
    }
}
