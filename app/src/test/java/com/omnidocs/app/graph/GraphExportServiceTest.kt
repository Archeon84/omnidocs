package com.omnidocs.app.graph

import com.omnidocs.app.ui.screens.editor.ParsedBoundingBox
import com.omnidocs.app.ui.screens.graph.ContradictionItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GraphExportServiceTest {

    private lateinit var exportService: GraphExportService

    @Before
    fun setUp() {
        exportService = GraphExportService()
    }

    @Test
    fun testExportToGraphML_producesValidXmlStructure() {
        val nodes = listOf(
            GraphNode(noteId = "note_1", title = "Budget 2026", wordCount = 120, tags = listOf("finance", "q3")),
            GraphNode(noteId = "note_2", title = "Marketing Plan", wordCount = 350, tags = listOf("marketing"))
        )
        val edges = listOf(
            GraphEdge(from = "note_1", to = "note_2", label = "references", strength = 0.85f)
        )
        val contradictions = listOf(
            ContradictionItem(
                noteIdA = "note_1",
                claimA = "Q3 budget allocated 50k",
                noteIdB = "note_2",
                claimB = "Marketing campaign costs 80k",
                explanation = "Marketing expense exceeds total allocated budget",
                confidence = 0.92f
            )
        )

        val graphData = GraphData(nodes = nodes, edges = edges)
        val xml = exportService.exportToGraphML(graphData, contradictions)

        assertNotNull(xml)
        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\""))
        assertTrue(xml.contains("<node id=\"note_1\">"))
        assertTrue(xml.contains("<data key=\"d0\">Budget 2026</data>"))
        assertTrue(xml.contains("<data key=\"d1\">120</data>"))
        assertTrue(xml.contains("<data key=\"d2\">finance,q3</data>"))
        assertTrue(xml.contains("<edge id=\"e0\" source=\"note_1\" target=\"note_2\">"))
        assertTrue(xml.contains("<data key=\"d4\">references</data>"))
        assertTrue(xml.contains("<edge id=\"c0\" source=\"note_1\" target=\"note_2\">"))
        assertTrue(xml.contains("<data key=\"d4\">contradicts</data>"))
        assertTrue(xml.contains("<data key=\"d7\">Marketing expense exceeds total allocated budget</data>"))
    }

    @Test
    fun testExportToJsonLd_producesValidW3CLinkedData() {
        val nodes = listOf(
            GraphNode(noteId = "note_alpha", title = "Architecture Spec", wordCount = 500, tags = listOf("tech")),
            GraphNode(noteId = "note_beta", title = "Security Audit", wordCount = 420, tags = listOf("security"))
        )
        val edges = listOf(
            GraphEdge(from = "note_alpha", to = "note_beta", label = "verified by", strength = 0.9f)
        )
        val contradictions = listOf(
            ContradictionItem(
                noteIdA = "note_alpha",
                claimA = "Local SQLite database is unencrypted",
                noteIdB = "note_beta",
                claimB = "SQLCipher hardware KeyStore encryption enabled",
                explanation = "Conflicting encryption security assertions",
                confidence = 0.95f
            )
        )

        val graphData = GraphData(nodes = nodes, edges = edges)
        val jsonLdString = exportService.exportToJsonLd(graphData, contradictions)

        assertNotNull(jsonLdString)
        val json = JSONObject(jsonLdString)
        assertEquals("Graph", json.getString("@type"))
        assertTrue(json.has("@context"))

        val graphArray = json.getJSONArray("@graph")
        assertEquals(3, graphArray.length()) // 2 notes + 1 contradiction claim review

        val doc1 = graphArray.getJSONObject(0)
        assertEquals("urn:omnidocs:note:note_alpha", doc1.getString("@id"))
        assertEquals("DigitalDocument", doc1.getString("@type"))
        assertEquals("Architecture Spec", doc1.getString("name"))

        val claimReview = graphArray.getJSONObject(2)
        assertEquals("ClaimReview", claimReview.getString("@type"))
        assertEquals("Conflicting encryption security assertions", claimReview.getString("reviewBody"))
        assertEquals(0.95, claimReview.getJSONObject("reviewRating").getDouble("ratingValue"), 0.001)
    }

    @Test
    fun testParsedBoundingBox_coordinateCalculations() {
        val bbox = ParsedBoundingBox(
            left = 100f,
            top = 200f,
            right = 300f,
            bottom = 250f
        )

        assertEquals(200f, bbox.width, 0.001f)
        assertEquals(50f, bbox.height, 0.001f)
        assertTrue(bbox.contains(150f, 220f))
        assertFalse(bbox.contains(50f, 220f))
        assertFalse(bbox.contains(150f, 300f))
    }
}
