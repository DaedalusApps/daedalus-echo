package com.daedalus.voicenotes

import com.daedalus.voicenotes.data.model.Recording
import com.daedalus.voicenotes.ui.mindmap.GraphBuilder
import com.daedalus.voicenotes.ui.mindmap.GraphNode
import org.junit.Assert.*
import org.junit.Test

class GraphBuilderTest {

    @Test
    fun buildGraph_emptyList_returnsEmpty() {
        val graph = GraphBuilder.build(emptyList())
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun buildGraph_multipleRecordingsWithSharedTopics_connectsThem() {
        val r1 = Recording(filename = "r1.mp3", title = "Rec 1", topics = listOf("AI", "Android"))
        val r2 = Recording(filename = "r2.mp3", title = "Rec 2", topics = listOf("Android", "TDD"))
        
        val graph = GraphBuilder.build(listOf(r1, r2))
        
        // Nodes: 2 recordings + 3 unique topics = 5 nodes
        assertEquals(5, graph.nodes.size)
        
        // Check for specific nodes
        val aiNode = graph.nodes.find { it.label == "AI" && it.type == GraphNode.Type.TOPIC }
        val androidNode = graph.nodes.find { it.label == "Android" && it.type == GraphNode.Type.TOPIC }
        val r1Node = graph.nodes.find { it.label == "Rec 1" && it.type == GraphNode.Type.RECORDING }
        
        assertNotNull(aiNode)
        assertNotNull(androidNode)
        assertNotNull(r1Node)
        
        // Edges: 
        // r1 -> AI, r1 -> Android
        // r2 -> Android, r2 -> TDD
        // Total 4 edges
        assertEquals(4, graph.edges.size)
        
        // Verify connection
        val r1ToAndroid = graph.edges.find { it.fromId == r1Node!!.id && it.toId == androidNode!!.id }
        assertNotNull(r1ToAndroid)
    }
}
