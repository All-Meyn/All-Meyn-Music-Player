package com.allmeyn.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Plays [AllMeynStartupScreen] once when the app opens, over the app (which loads underneath),
 * then fades it away. Ported from the Android app (ui/StartupScreen.kt); the mark comes from the
 * same vector.
 */
@Composable
fun StartupOverlay() {
    var showing by remember { mutableStateOf(true) }
    AnimatedVisibility(visible = showing, enter = EnterTransition.None, exit = fadeOut(tween(250))) {
        // Swallow touches meant for the app underneath until the intro is gone.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }) {
            AllMeynStartupScreen { showing = false }
        }
    }
}

/**
 * The startup animation: the real AM mark (the same vector as the themed icon, ic_brand_mark) is
 * traced in along its outline, then the copper fill, the amber glow and the wordmark fade up.
 */
@Composable
fun AllMeynStartupScreen(onAnimationFinished: () -> Unit = {}) {
    val traceProgress = remember { Animatable(0f) }
    val glowAlpha = remember { Animatable(0f) }
    val mark = BrandMark.vector

    LaunchedEffect(Unit) {
        traceProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        )
        glowAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
        )
        delay(250)
        onAnimationFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12)), // = the system launch screen, so the hand-over is seamless
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(
                modifier = Modifier
                    .size(180.dp)
                    // The outline, its measure and the brushes are built once per size; each frame only
                    // cuts the traced segment (no allocations per frame).
                    .drawWithCache {
                        val w = size.width
                        val h = size.height

                        // The mark's outline, scaled to 86 % of the width and centred.
                        val outline = Path()
                        for (node in mark.root) if (node is VectorPath) node.pathData.toPath(outline)
                        val scale = minOf(w * 0.86f / mark.viewportWidth, h * 0.6f / mark.viewportHeight)
                        outline.transform(Matrix().apply { scale(scale, scale) })
                        outline.translate(
                            Offset((w - mark.viewportWidth * scale) / 2f, (h - mark.viewportHeight * scale) / 2f),
                        )
                        val top = (h - mark.viewportHeight * scale) / 2f
                        val bottom = top + mark.viewportHeight * scale

                        val pathMeasure = PathMeasure().apply { setPath(outline, true) }
                        val animatedPath = Path()
                        val copper = Brush.verticalGradient(
                            listOf(Color(0xFFFBBF24), Color(0xFFD97706), Color(0xFF9A4A1A)), startY = top, endY = bottom,
                        )
                        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val glowBrush = Brush.radialGradient(
                            colors = listOf(Color(0xFFF59E0B).copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = w * 0.55f,
                        )

                        onDrawBehind {
                            // Warm amber backlight glow, then the copper fill
                            val glow = glowAlpha.value
                            if (glow > 0f) {
                                drawCircle(brush = glowBrush, alpha = glow)
                                drawPath(path = outline, brush = copper, alpha = glow)
                            }
                            // The outline being traced
                            animatedPath.reset()
                            pathMeasure.getSegment(
                                startDistance = 0f,
                                stopDistance = pathMeasure.length * traceProgress.value,
                                destination = animatedPath,
                                startWithMoveTo = true,
                            )
                            drawPath(path = animatedPath, brush = copper, style = stroke)
                        }
                    },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ALL-MEYN",
                color = Color(0xFFE5E7EB).copy(alpha = glowAlpha.value),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 6.sp,
            )
        }
    }
}
