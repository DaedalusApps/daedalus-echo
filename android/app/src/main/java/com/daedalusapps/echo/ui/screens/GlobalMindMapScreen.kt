package com.daedalusapps.echo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daedalusapps.echo.ui.mindmap.GlobalGraph
import com.daedalusapps.echo.ui.mindmap.GraphNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class PositionedGraphNode(
    val node: GraphNode,
    val x: Float,
    val y: Float,
    var rect: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalMindMapScreen(
    graph: GlobalGraph,
    onNavigateToNote: (String) -> Unit,
    onBack: () -> Unit
) {
    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Using the Knowledge Graph") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This map visualizes the relationships between your recordings based on topics identified by AI.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Text("• Nodes", fontWeight = FontWeight.Bold)
                    Text(
                        "Large rectangles represent key Topics. Smaller rectangles represent your Recordings. Tap a recording to open it.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("• Connections", fontWeight = FontWeight.Bold)
                    Text(
                        "Lines show which recordings share common topics. Clustered nodes indicate related themes across your library.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("• Exploration", fontWeight = FontWeight.Bold)
                    Text(
                        "Pinch to zoom in/out and drag to pan across your semantic landscape.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Got it") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global Knowledge Graph") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Default.Info, contentDescription = "How to use")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (graph.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No topics analyzed yet.", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                GlobalMindMapCanvas(graph, onNavigateToNote)
            }
        }
    }
}

@Composable
fun GlobalMindMapCanvas(
    graph: GlobalGraph,
    onNavigateToNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    
    var scale by remember { mutableStateOf(0.5f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isInitialized by remember { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.1f, 5f)
        offset += panChange
    }

    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer

    var selectedTopic by remember { mutableStateOf<PositionedGraphNode?>(null) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .transformable(transformState)
    ) {
        val cx = constraints.maxWidth / 2f
        val cy = constraints.maxHeight / 2f

        // Initial zoom to fit topics
        if (!isInitialized && constraints.maxWidth > 0) {
            val topicRadiusPx = 350f * density
            val targetScale = (constraints.maxWidth * 0.8f) / (topicRadiusPx * 2f)
            scale = targetScale.coerceIn(0.1f, 2f)
            isInitialized = true
        }

        val positionedNodes = remember(graph, cx, cy, density) {
            val result = mutableListOf<PositionedGraphNode>()
            val topicNodes = graph.nodes.filter { it.type == GraphNode.Type.TOPIC }
            val recordingNodes = graph.nodes.filter { it.type == GraphNode.Type.RECORDING }

            // Place topics in a circle
            topicNodes.forEachIndexed { i, node ->
                val angle = (2 * PI * i / topicNodes.size).toFloat()
                val radius = 350f * density
                result.add(PositionedGraphNode(node, cx + radius * cos(angle), cy + radius * sin(angle)))
            }

            // Place recordings in a wider outer circle
            recordingNodes.forEachIndexed { i, node ->
                val angle = (2 * PI * i / recordingNodes.size).toFloat()
                val radius = 650f * density
                result.add(PositionedGraphNode(node, cx + radius * cos(angle), cy + radius * sin(angle)))
            }
            result
        }
        
        val nodeMap = remember(positionedNodes) { positionedNodes.associateBy { it.node.id } }

        selectedTopic?.let { topic ->
            val connectedRecordings = graph.edges
                .filter { it.toId == topic.node.id }
                .mapNotNull { nodeMap[it.fromId]?.node?.label }
            AlertDialog(
                onDismissRequest = { selectedTopic = null },
                title = { Text(topic.node.label) },
                text = if (connectedRecordings.isNotEmpty()) {
                    { Text("Connected recordings:\n" + connectedRecordings.joinToString("\n") { "• $it" }) }
                } else null,
                confirmButton = {
                    TextButton(onClick = { selectedTopic = null }) { Text("OK") }
                }
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, Offset(cx, cy))
            }) {
                // Draw Edges
                graph.edges.forEach { edge ->
                    val from = nodeMap[edge.fromId] ?: return@forEach
                    val to = nodeMap[edge.toId] ?: return@forEach
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.3f),
                        start = Offset(from.x, from.y),
                        end = Offset(to.x, to.y),
                        strokeWidth = 2f / scale // Keep lines readable when zoomed out
                    )
                }
            }
        }

        // Render Nodes as Composables
        Box(Modifier.fillMaxSize()) {
            positionedNodes.forEach { p ->
                val isTopic = p.node.type == GraphNode.Type.TOPIC
                val label = if (isTopic) p.node.label.take(24) else p.node.label.take(18)
                
                val color = if (isTopic) primaryContainer else secondaryContainer
                val textColor = if (isTopic) onPrimaryContainer else onSecondaryContainer
                val shape = if (isTopic) androidx.compose.foundation.shape.CircleShape 
                            else androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

                val fontSize = (if (isTopic) 14.sp else 11.sp) * scale
                val fontWeight = if (isTopic) FontWeight.Bold else FontWeight.Normal
                
                val textStyle = TextStyle(
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    textAlign = TextAlign.Center
                )
                
                val paddingH = (if (isTopic) 14.dp else 10.dp) * scale
                val paddingV = (if (isTopic) 8.dp else 6.dp) * scale

                Surface(
                    color = color,
                    shape = shape,
                    shadowElevation = (1.dp.value * scale).dp,
                    modifier = Modifier
                        .offset {
                            // Calculate measured size in pixels
                            val measured = textMeasurer.measure(label, textStyle)
                            val totalWPx = measured.size.width + (paddingH.toPx() * 2)
                            val totalHPx = measured.size.height + (paddingV.toPx() * 2)
                            
                            androidx.compose.ui.unit.IntOffset(
                                (offset.x + (p.x - cx) * scale + cx - totalWPx / 2).toInt(),
                                (offset.y + (p.y - cy) * scale + cy - totalHPx / 2).toInt()
                            )
                        }
                        .clickable {
                            if (isTopic) {
                                selectedTopic = p
                            } else {
                                onNavigateToNote(p.node.id.removePrefix("rec_"))
                            }
                        }
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        style = textStyle,
                        modifier = Modifier.padding(horizontal = paddingH, vertical = paddingV),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
