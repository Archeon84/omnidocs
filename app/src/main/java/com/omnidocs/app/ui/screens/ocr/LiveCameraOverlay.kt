package com.omnidocs.app.ui.screens.ocr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.omnidocs.app.ocr.OcrBlock

@Composable
fun LiveCameraOverlay(
    blocks: List<OcrBlock>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        blocks.forEach { block ->
            val bbox = block.boundingBox
            val left = bbox.left * size.width
            val top = bbox.top * size.height
            val right = bbox.right * size.width
            val bottom = bbox.bottom * size.height

            // Color based on confidence
            val color = when {
                block.confidence > 0.8f -> Color.Green.copy(alpha = 0.6f)
                block.confidence > 0.5f -> Color.Yellow.copy(alpha = 0.6f)
                else -> Color.Red.copy(alpha = 0.4f)
            }

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
