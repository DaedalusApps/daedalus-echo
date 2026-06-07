package com.daedalus.voicenotes.ui.mindmap

import com.daedalus.voicenotes.data.model.Recording

data class GraphNode(
    val id: String,
    val label: String,
    val type: Type
) {
    enum class Type { RECORDING, TOPIC }
}

data class GraphEdge(
    val fromId: String,
    val toId: String
)

data class GlobalGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)

object GraphBuilder {
    fun build(recordings: List<Recording>): GlobalGraph {
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        
        val topicSet = mutableSetOf<String>()
        
        recordings.forEach { recording ->
            val recordingId = "rec_${recording.filename}"
            val recordingLabel = recording.title.ifBlank { recording.filename }
            nodes.add(GraphNode(recordingId, recordingLabel, GraphNode.Type.RECORDING))
            
            recording.topics.forEach { topic ->
                val topicId = "topic_$topic"
                if (topicSet.add(topic)) {
                    nodes.add(GraphNode(topicId, topic, GraphNode.Type.TOPIC))
                }
                edges.add(GraphEdge(recordingId, topicId))
            }
        }
        
        return GlobalGraph(nodes, edges)
    }
}
