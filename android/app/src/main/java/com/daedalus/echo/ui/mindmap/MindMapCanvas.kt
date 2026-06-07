package com.daedalus.echo.ui.mindmap

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MindMapCanvas(markdown: String) {
    val tree = remember(markdown) { MindMapParser.parse(markdown) }
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.2f, 5f)
        offset += panChange
    }

    var selectedNode by remember { mutableStateOf<PositionedNode?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val onPrimaryColor = Color.White

    selectedNode?.let { node ->
        AlertDialog(
            onDismissRequest = { selectedNode = null },
            title = { Text(node.node.label) },
            text = if (node.node.children.isNotEmpty()) ({
                Text("Sub-topics:\n" + node.node.children.joinToString("\n") { "• ${it.label}" })
            }) else null,
            confirmButton = {
                TextButton(onClick = { selectedNode = null }) { Text("OK") }
            }
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .transformable(transformState)
    ) {
        val cx = constraints.maxWidth / 2f
        val cy = constraints.maxHeight / 2f
        val nodes = remember(tree, cx, cy, density) { layoutRadial(tree, cx, cy, density) }
        val nodeMap = remember(nodes) { nodes.associateBy { it.node } }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nodes, scale, offset) {
                    detectTapGestures { tap ->
                        val wx = (tap.x - offset.x - cx) / scale + cx
                        val wy = (tap.y - offset.y - cy) / scale + cy
                        selectedNode = nodes.firstOrNull { it.rect.contains(Offset(wx, wy)) }
                    }
                }
        ) {
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, Offset(cx, cy))
            }) {
                // Edges
                nodes.forEach { positioned ->
                    positioned.node.children.forEach { child ->
                        val childPos = nodeMap[child] ?: return@forEach
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.5f),
                            start = Offset(positioned.x, positioned.y),
                            end = Offset(childPos.x, childPos.y),
                            strokeWidth = 3f
                        )
                    }
                }

                // Nodes
                nodes.forEach { positioned ->
                    val label = positioned.node.label.take(25)
                    val fontSize = when (positioned.depth) { 0 -> 16.sp; 1 -> 14.sp; else -> 12.sp }
                    val textStyle = TextStyle(color = onPrimaryColor, fontSize = fontSize, fontWeight = if (positioned.depth == 0) FontWeight.Bold else FontWeight.Normal)
                    val textLayout = textMeasurer.measure(label, textStyle)

                    val paddingH = 12.dp.toPx()
                    val paddingV = 8.dp.toPx()
                    val rectW = textLayout.size.width + (paddingH * 2)
                    val rectH = textLayout.size.height + (paddingV * 2)

                    positioned.rect = Rect(
                        positioned.x - rectW / 2, positioned.y - rectH / 2,
                        positioned.x + rectW / 2, positioned.y + rectH / 2
                    )

                    val nodeColor = when (positioned.depth) { 0 -> primaryColor; 1 -> secondaryColor; else -> tertiaryColor }

                    drawRoundRect(
                        color = nodeColor,
                        topLeft = Offset(positioned.x - rectW / 2, positioned.y - rectH / 2),
                        size = Size(rectW, rectH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )

                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(positioned.x - textLayout.size.width / 2, positioned.y - textLayout.size.height / 2)
                    )
                }
            }
        }
    }
}
