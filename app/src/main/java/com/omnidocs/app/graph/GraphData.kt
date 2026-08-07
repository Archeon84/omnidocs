package com.omnidocs.app.graph

data class GraphNode(
    val noteId: String,
    val title: String,
    val wordCount: Int = 0,
    val tags: List<String> = emptyList()
)

data class GraphEdge(
    val from: String,
    val to: String,
    val label: String,
    val strength: Float
)

data class GraphData(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)