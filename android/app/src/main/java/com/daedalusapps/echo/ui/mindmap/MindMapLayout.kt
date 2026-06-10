package com.daedalusapps.echo.ui.mindmap

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class PositionedNode(
    val node: MindMapNode,
    val x: Float,
    val y: Float,
    val depth: Int,
    var rect: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
)

fun layoutRadial(root: MindMapNode, centerX: Float, centerY: Float, density: Float): List<PositionedNode> {
    val dp = density
    val radii = listOf(0f, 180f * dp, 320f * dp, 460f * dp)
    val result = mutableListOf<PositionedNode>()
    result.add(PositionedNode(root, centerX, centerY, 0))

    fun place(node: MindMapNode, startAngle: Float, sweepAngle: Float, depth: Int) {
        if (node.children.isEmpty()) return
        val r = radii.getOrElse(depth) { radii.last() }
        node.children.forEachIndexed { i, child ->
            val angle = startAngle + sweepAngle * (i + 0.5f) / node.children.size
            val x = centerX + r * cos(angle)
            val y = centerY + r * sin(angle)
            result.add(PositionedNode(child, x, y, depth))
            place(
                child,
                startAngle + sweepAngle * i / node.children.size,
                sweepAngle / node.children.size,
                depth + 1
            )
        }
    }

    place(root, 0f, 2 * PI.toFloat(), 1)
    return result
}
