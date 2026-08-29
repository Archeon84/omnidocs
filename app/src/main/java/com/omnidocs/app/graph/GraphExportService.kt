package com.omnidocs.app.graph

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.omnidocs.app.ui.screens.graph.ContradictionItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class GraphExportFormat(val extension: String, val mimeType: String, val displayName: String) {
    GRAPHML("graphml", "application/xml", "GraphML (.graphml)"),
    JSON_LD("jsonld", "application/ld+json", "JSON-LD (.jsonld)")
}

/**
 * Service providing export and interchange capabilities for the Knowledge Graph
 * and detected contradictions into standard interchange formats:
 * 1. GraphML (Standard XML Schema for graph visualizers like Gephi, Cytoscape, yEd)
 * 2. JSON-LD (W3C Linked Data format for semantic web, Neo4j and knowledge graph tools)
 */
@Singleton
class GraphExportService @Inject constructor() {

    /**
     * Serializes knowledge graph nodes, edges, and contradiction hyperedges into standard GraphML (XML).
     */
    fun exportToGraphML(graphData: GraphData, contradictions: List<ContradictionItem> = emptyList()): String {
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"")
            appendLine("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
            appendLine("         xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd\">")

            // Key schemas
            appendLine("  <!-- Node Attributes -->")
            appendLine("  <key id=\"d0\" for=\"node\" attr.name=\"title\" attr.type=\"string\"/>")
            appendLine("  <key id=\"d1\" for=\"node\" attr.name=\"wordCount\" attr.type=\"int\"/>")
            appendLine("  <key id=\"d2\" for=\"node\" attr.name=\"tags\" attr.type=\"string\"/>")
            appendLine("  <key id=\"d3\" for=\"node\" attr.name=\"nodeType\" attr.type=\"string\"/>")

            appendLine("  <!-- Edge Attributes -->")
            appendLine("  <key id=\"d4\" for=\"edge\" attr.name=\"label\" attr.type=\"string\"/>")
            appendLine("  <key id=\"d5\" for=\"edge\" attr.name=\"strength\" attr.type=\"double\"/>")
            appendLine("  <key id=\"d6\" for=\"edge\" attr.name=\"edgeType\" attr.type=\"string\"/>")
            appendLine("  <key id=\"d7\" for=\"edge\" attr.name=\"explanation\" attr.type=\"string\"/>")

            appendLine("  <graph id=\"OmniDocsKnowledgeGraph\" edgedefault=\"directed\">")

            // Nodes
            graphData.nodes.forEach { node ->
                appendLine("    <node id=\"${escapeXml(node.noteId)}\">")
                appendLine("      <data key=\"d0\">${escapeXml(node.title)}</data>")
                appendLine("      <data key=\"d1\">${node.wordCount}</data>")
                appendLine("      <data key=\"d2\">${escapeXml(node.tags.joinToString(","))}</data>")
                appendLine("      <data key=\"d3\">note</data>")
                appendLine("    </node>")
            }

            // Relationship Edges
            graphData.edges.forEachIndexed { idx, edge ->
                appendLine("    <edge id=\"e$idx\" source=\"${escapeXml(edge.from)}\" target=\"${escapeXml(edge.to)}\">")
                appendLine("      <data key=\"d4\">${escapeXml(edge.label)}</data>")
                appendLine("      <data key=\"d5\">${edge.strength}</data>")
                appendLine("      <data key=\"d6\">relation</data>")
                appendLine("    </edge>")
            }

            // Contradiction Edges
            contradictions.forEachIndexed { cIdx, item ->
                appendLine("    <edge id=\"c$cIdx\" source=\"${escapeXml(item.noteIdA)}\" target=\"${escapeXml(item.noteIdB)}\">")
                appendLine("      <data key=\"d4\">contradicts</data>")
                appendLine("      <data key=\"d5\">${item.confidence.toDouble()}</data>")
                appendLine("      <data key=\"d6\">contradiction</data>")
                appendLine("      <data key=\"d7\">${escapeXml(item.explanation)}</data>")
                appendLine("    </edge>")
            }

            appendLine("  </graph>")
            appendLine("</graphml>")
        }
    }

    /**
     * Serializes knowledge graph nodes, edges, and contradiction claim reviews into W3C Linked Data JSON-LD.
     */
    fun exportToJsonLd(graphData: GraphData, contradictions: List<ContradictionItem> = emptyList()): String {
        val root = JSONObject()
        root.put("@context", JSONObject().apply {
            put("@vocab", "https://schema.org/")
            put("omnidocs", "https://omnidocs.app/schema/")
            put("strength", "omnidocs:strength")
            put("contradicts", "omnidocs:contradicts")
            put("explanation", "omnidocs:explanation")
        })
        root.put("@type", "Graph")

        val graphArray = JSONArray()

        // 1. Note Entities
        graphData.nodes.forEach { node ->
            val noteObj = JSONObject().apply {
                put("@id", "urn:omnidocs:note:${node.noteId}")
                put("@type", "DigitalDocument")
                put("name", node.title)
                put("wordCount", node.wordCount)
                if (node.tags.isNotEmpty()) {
                    put("keywords", JSONArray(node.tags))
                }

                // Add outgoing relations
                val outgoing = graphData.edges.filter { it.from == node.noteId }
                if (outgoing.isNotEmpty()) {
                    val relatedArray = JSONArray()
                    outgoing.forEach { edge ->
                        relatedArray.put(JSONObject().apply {
                            put("@type", "LinkRole")
                            put("target", "urn:omnidocs:note:${edge.to}")
                            put("linkRelationship", edge.label)
                            put("strength", edge.strength.toDouble())
                        })
                    }
                    put("relatedLink", relatedArray)
                }
            }
            graphArray.put(noteObj)
        }

        // 2. Contradiction ClaimReviews
        contradictions.forEachIndexed { idx, item ->
            val claimReview = JSONObject().apply {
                put("@id", "urn:omnidocs:contradiction:$idx")
                put("@type", "ClaimReview")
                put("claimReviewed", "Claim A: \"${item.claimA}\" vs Claim B: \"${item.claimB}\"")
                put("reviewRating", JSONObject().apply {
                    put("@type", "Rating")
                    put("ratingValue", item.confidence.toDouble())
                    put("bestRating", 1.0)
                    put("worstRating", 0.0)
                })
                put("reviewBody", item.explanation)
                put("itemReviewed", JSONArray().apply {
                    put("urn:omnidocs:note:${item.noteIdA}")
                    put("urn:omnidocs:note:${item.noteIdB}")
                })
            }
            graphArray.put(claimReview)
        }

        root.put("@graph", graphArray)
        return root.toString(2)
    }

    /**
     * Saves the exported content to app cache and launches the Android system share sheet.
     */
    fun shareExportedGraph(
        context: Context,
        format: GraphExportFormat,
        content: String,
        baseName: String = "omnidocs_knowledge_graph"
    ): Boolean {
        return try {
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()
            val fileName = "${baseName}_${System.currentTimeMillis()}.${format.extension}"
            val file = File(exportDir, fileName)
            file.writeText(content, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OmniDocs Knowledge Graph (${format.displayName})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Share Knowledge Graph").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("GraphExportService", "Failed to share graph export", e)
            false
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
