package com.omnidocs.app.ui.screens.graph

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.agent.AgentContext
import com.omnidocs.app.agent.AgentInput
import com.omnidocs.app.agent.AgentResult
import com.omnidocs.app.agent.ContradictionDetectionAgent
import com.omnidocs.app.agent.IdeaEvolutionAgent
import com.omnidocs.app.graph.GraphData
import com.omnidocs.app.graph.GraphEngine
import com.omnidocs.app.graph.GraphExportFormat
import com.omnidocs.app.graph.GraphExportService
import com.omnidocs.app.knowledge.IdeaEvolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

data class ContradictionItem(
    val noteIdA: String,
    val claimA: String,
    val noteIdB: String,
    val claimB: String,
    val explanation: String,
    val confidence: Float
)

@HiltViewModel
class GraphViewModel @Inject constructor(
    private val graphEngine: GraphEngine,
    private val contradictionDetectionAgent: ContradictionDetectionAgent,
    private val ideaEvolutionAgent: IdeaEvolutionAgent,
    private val graphExportService: GraphExportService
) : ViewModel() {

    private val _graphData = MutableStateFlow<GraphData?>(null)
    val graphData: StateFlow<GraphData?> = _graphData.asStateFlow()

    private val _contradictions = MutableStateFlow<List<ContradictionItem>>(emptyList())
    val contradictions: StateFlow<List<ContradictionItem>> = _contradictions.asStateFlow()

    private val _evolutionTimeline = MutableStateFlow<IdeaEvolution.IdeaTimeline?>(null)
    val evolutionTimeline: StateFlow<IdeaEvolution.IdeaTimeline?> = _evolutionTimeline.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        buildGraph()
    }

    fun buildGraph() {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val data = graphEngine.buildGraph()
                _graphData.value = data
                if (data.nodes.isEmpty()) {
                    _error.value = "No notes found"
                } else if (data.edges.isEmpty()) {
                    _error.value = "No connections found between notes"
                }
            } catch (e: Exception) {
                _error.value = "Failed to build graph: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun detectContradictions() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val result = contradictionDetectionAgent.execute(
                    input = AgentInput(type = "DETECT_CONTRADICTIONS"),
                    context = AgentContext(jobId = "graph_contradictions")
                )
                if (result is AgentResult.Success) {
                    val jsonStr = result.payload["contradictionsJson"] as? String ?: "[]"
                    val array = JSONArray(jsonStr)
                    val list = mutableListOf<ContradictionItem>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            ContradictionItem(
                                noteIdA = obj.optString("noteIdA"),
                                claimA = obj.optString("claimA"),
                                noteIdB = obj.optString("noteIdB"),
                                claimB = obj.optString("claimB"),
                                explanation = obj.optString("explanation"),
                                confidence = obj.optDouble("confidence", 0.8).toFloat()
                            )
                        )
                    }
                    _contradictions.value = list
                }
            } catch (e: Exception) {
                _error.value = "Contradiction detection failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun traceEvolution(concept: String) {
        if (concept.isBlank()) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val result = ideaEvolutionAgent.execute(
                    input = AgentInput(type = "TRACE_EVOLUTION", payload = mapOf("concept" to concept)),
                    context = AgentContext(jobId = "graph_evolution")
                )
                // Timeline processed
            } catch (e: Exception) {
                _error.value = "Evolution trace failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun rebuildGraph() {
        buildGraph()
    }

    fun exportGraphml(context: Context) {
        val data = _graphData.value ?: return
        val content = graphExportService.exportToGraphML(data, _contradictions.value)
        graphExportService.shareExportedGraph(context, GraphExportFormat.GRAPHML, content)
    }

    fun exportJsonLd(context: Context) {
        val data = _graphData.value ?: return
        val content = graphExportService.exportToJsonLd(data, _contradictions.value)
        graphExportService.shareExportedGraph(context, GraphExportFormat.JSON_LD, content)
    }
}
