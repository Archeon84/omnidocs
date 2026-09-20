package com.omnidocs.app.ui.screens.editor

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import org.json.JSONObject
import java.io.File

data class ParsedBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = maxOf(1f, right - left)
    val height: Float get() = maxOf(1f, bottom - top)

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }
}

data class OcrBlockUi(
    val index: Int,
    val entity: ContentBlockEntity,
    val box: ParsedBoundingBox?,
    val confidence: Float,
    val text: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrBoundingBoxInspectorSheet(
    imageUrl: String?,
    contentBlocks: List<ContentBlockEntity>,
    selectedIndex: Int?,
    onSelectBlock: (Int?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current
    var copiedMessage by remember { mutableStateOf<String?>(null) }

    val parsedBlocks = remember(contentBlocks) {
        contentBlocks.mapIndexed { idx, block ->
            val box = block.boundingBoxJson?.let { json ->
                try {
                    val obj = JSONObject(json)
                    ParsedBoundingBox(
                        left = obj.getDouble("left").toFloat(),
                        top = obj.getDouble("top").toFloat(),
                        right = obj.getDouble("right").toFloat(),
                        bottom = obj.getDouble("bottom").toFloat()
                    )
                } catch (e: Exception) {
                    null
                }
            }
            OcrBlockUi(
                index = idx,
                entity = block,
                box = box,
                confidence = block.confidence ?: 0.85f,
                text = block.content
            )
        }
    }

    // Determine coordinate bounds for normalization if bounding boxes exist
    val maxCoord = remember(parsedBlocks) {
        var maxX = 1000f
        var maxY = 1000f
        parsedBlocks.forEach { block ->
            block.box?.let { b ->
                if (b.right > maxX) maxX = b.right
                if (b.bottom > maxY) maxY = b.bottom
            }
        }
        Pair(maxX, maxY)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "OCR Provenance & Bounding Boxes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${parsedBlocks.size} extracted blocks identified",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Visual Canvas with bounding box overlays
            if (imageUrl != null && File(imageUrl).exists() || imageUrl?.startsWith("http") == true || imageUrl?.startsWith("content://") == true) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .size(1000)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Document Scan",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay Canvas
                    val primaryColor = MaterialTheme.colorScheme.primary
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(parsedBlocks, maxCoord) {
                                detectTapGestures { tapOffset ->
                                    val canvasW = size.width
                                    val canvasH = size.height
                                    val normX = (tapOffset.x / canvasW) * maxCoord.first
                                    val normY = (tapOffset.y / canvasH) * maxCoord.second

                                    val clicked = parsedBlocks.firstOrNull { block ->
                                        block.box?.contains(normX, normY) == true
                                    }
                                    onSelectBlock(clicked?.index)
                                }
                            }
                    ) {
                        val canvasW = size.width
                        val canvasH = size.height
                        val scaleX = canvasW / maxCoord.first
                        val scaleY = canvasH / maxCoord.second

                        parsedBlocks.forEach { block ->
                            block.box?.let { b ->
                                val isSelected = block.index == selectedIndex
                                val strokeColor = when {
                                    block.confidence >= 0.85f -> Color(0xFF2E7D32) // Emerald Green
                                    block.confidence >= 0.60f -> Color(0xFFF57C00) // Amber
                                    else -> Color(0xFFD32F2F) // Coral Red
                                }

                                val leftPx = b.left * scaleX
                                val topPx = b.top * scaleY
                                val widthPx = b.width * scaleX
                                val heightPx = b.height * scaleY

                                if (isSelected) {
                                    drawRect(
                                        color = strokeColor.copy(alpha = 0.35f),
                                        topLeft = Offset(leftPx, topPx),
                                        size = Size(widthPx, heightPx)
                                    )
                                }

                                drawRect(
                                    color = if (isSelected) primaryColor else strokeColor,
                                    topLeft = Offset(leftPx, topPx),
                                    size = Size(widthPx, heightPx),
                                    style = Stroke(width = if (isSelected) 3.5.dp.toPx() else 1.5.dp.toPx())
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Selected Block Detail Inspector Card
            val selectedBlock = selectedIndex?.let { idx -> parsedBlocks.getOrNull(idx) }
            if (selectedBlock != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Block #${selectedBlock.index + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        selectedBlock.confidence >= 0.85f -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                        selectedBlock.confidence >= 0.60f -> Color(0xFFF57C00).copy(alpha = 0.15f)
                                        else -> Color(0xFFD32F2F).copy(alpha = 0.15f)
                                    }
                                ) {
                                    Text(
                                        text = "${(selectedBlock.confidence * 100).toInt()}% CONFIDENCE",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            selectedBlock.confidence >= 0.85f -> Color(0xFF2E7D32)
                                            selectedBlock.confidence >= 0.60f -> Color(0xFFF57C00)
                                            else -> Color(0xFFD32F2F)
                                        }
                                    )
                                }
                            }

                            FilledTonalButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(selectedBlock.text))
                                    copiedMessage = "Block text copied!"
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = selectedBlock.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Scrollable Block List
            Text(
                text = "Extracted Text Blocks (${parsedBlocks.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(parsedBlocks, key = { it.entity.id }) { block ->
                    val isSelected = block.index == selectedIndex
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectBlock(if (isSelected) null else block.index)
                            }
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Block #${block.index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(block.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    block.confidence >= 0.85f -> Color(0xFF2E7D32)
                                    block.confidence >= 0.60f -> Color(0xFFF57C00)
                                    else -> Color(0xFFD32F2F)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
