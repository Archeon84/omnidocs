package com.omnidocs.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omnidocs.app.ui.theme.isReducedMotionEnabled

/**
 * Animated shimmer brush that sweeps across a surface.
 * Returns a static brush when reduced motion is enabled.
 */
@Composable
fun shimmerBrush(
    targetValue: Float = 1000f,
    colors: List<Color> = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
): Brush {
    val reducedMotion = isReducedMotionEnabled()
    if (reducedMotion) {
        return Brush.linearGradient(
            colors = colors,
            start = Offset.Zero,
            end = Offset(x = targetValue, y = targetValue)
        )
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerTranslate"
    )
    return Brush.linearGradient(
        colors = colors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )
}

/**
 * A single shimmer placeholder row that mimics a note card layout.
 */
@Composable
fun ShimmerNoteCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val brush = shimmerBrush()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // Title placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Body line 1
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Body line 2
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(10.dp))
        // Footer placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth(0.3f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
    }
}

/**
 * A grid of shimmer note cards shown while data loads.
 */
@Composable
fun ShimmerGrid(
    modifier: Modifier = Modifier,
    count: Int = 6
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) {
            ShimmerNoteCard(modifier = Modifier.fillMaxWidth())
        }
    }
}
