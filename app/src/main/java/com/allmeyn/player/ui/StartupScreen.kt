package com.allmeyn.player.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Plays [AllMeynStartupScreen] once when the app opens, over the app (which loads underneath),
 * then fades it away. Not again when the screen is folded, rotated or recreated.
 */
@Composable
fun StartupOverlay() {
    var showing by rememberSaveable { mutableStateOf(true) }
    AnimatedVisibility(visible = showing, enter = EnterTransition.None, exit = fadeOut(tween(250))) {
        // Swallow touches meant for the app underneath until the intro is gone.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }) {
            AllMeynStartupScreen { showing = false }
        }
    }
}

/** The startup animation: the AM waveform traces in, then the amber glow and wordmark fade up. */
@Composable
fun AllMeynStartupScreen(onAnimationFinished: () -> Unit = {}) {
    val traceProgress = remember { Animatable(0f) }
    val glowAlpha = remember { Animatable(0f) }

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
                    // The path, its measure and the brushes are built once per size; each frame only
                    // cuts the traced segment (no allocations per frame).
                    .drawWithCache {
                        val w = size.width
                        val h = size.height

                        // AM waveform + play triangle
                        val logoPath = Path().apply {
                            moveTo(w * 0.14f, h * 0.68f)
                            lineTo(w * 0.30f, h * 0.26f)
                            lineTo(w * 0.44f, h * 0.66f)
                            lineTo(w * 0.56f, h * 0.34f)
                            lineTo(w * 0.66f, h * 0.58f)
                            // Play triangle tip
                            lineTo(w * 0.86f, h * 0.48f)
                            lineTo(w * 0.66f, h * 0.38f)
                        }
                        val pathMeasure = PathMeasure().apply { setPath(logoPath, false) }
                        val animatedPath = Path()
                        val strokeBrush = Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFD97706)))
                        val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val glowBrush = Brush.radialGradient(
                            colors = listOf(Color(0xFFF59E0B).copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = w * 0.55f,
                        )

                        onDrawBehind {
                            // Warm amber backlight glow
                            val glow = glowAlpha.value
                            if (glow > 0f) drawCircle(brush = glowBrush, alpha = glow)
                            animatedPath.reset()
                            pathMeasure.getSegment(
                                startDistance = 0f,
                                stopDistance = pathMeasure.length * traceProgress.value,
                                destination = animatedPath,
                                startWithMoveTo = true,
                            )
                            drawPath(path = animatedPath, brush = strokeBrush, style = stroke)
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
