package com.daedalus.voicenotes.ui.mindmap

data class MindMapNode(val label: String, val children: MutableList<MindMapNode> = mutableListOf())

object MindMapParser {
    fun parse(markdown: String): MindMapNode {
        val root = MindMapNode("Root")
        val lines = markdown.lines().filter { it.isNotBlank() }
        val stack = mutableListOf<Pair<Int, MindMapNode>>()
        stack.add(-1 to root)
        lines.forEach { line ->
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val label = line.trimStart().removePrefix("-").removePrefix("*").trim()
            
            // Filter empty labels and labels made entirely of structural punctuation (e.g. "{", "}", "[]")
            if (label.isBlank() || label.all { it in "{}[]()," || it.isWhitespace() }) return@forEach
            
            val node = MindMapNode(label)
            while (stack.size > 1 && stack.last().first >= indent) stack.removeAt(stack.size - 1)
            stack.last().second.children.add(node)
            stack.add(indent to node)
        }
        return root
    }
}
