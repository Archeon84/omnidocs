package com.omnidocs.app.ui.screens.graph

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.agent.AgentContext
import com.omnidocs.app.agent.AgentInput
import com.omnidocs.app.agent.AgentResult
import com.omnidocs.app.agent.ContradictionDetectionAgent
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.graph.GraphData
import com.omnidocs.app.graph.GraphEngine
import com.omnidocs.app.graph.GraphExportFormat
import com.omnidocs.app.graph.GraphExportService
import com.omnidocs.app.knowledge.IdeaEvolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val ideaEvolution: IdeaEvolution,
    private val graphExportService: GraphExportService,
    private val repository: NotesRepository,
    private val modelDownloadManager: ModelDownloadManager
) : ViewModel() {

    private val _graphData = MutableStateFlow<GraphData?>(null)
    val graphData: StateFlow<GraphData?> = _graphData.asStateFlow()

    private val _contradictions = MutableStateFlow<List<ContradictionItem>>(emptyList())
    val contradictions: StateFlow<List<ContradictionItem>> = _contradictions.asStateFlow()

    private val _evolutionTimeline = MutableStateFlow<IdeaEvolution.IdeaTimeline?>(null)
    val evolutionTimeline: StateFlow<IdeaEvolution.IdeaTimeline?> = _evolutionTimeline.asStateFlow()

    private val _selectedNotePreview = MutableStateFlow<Note?>(null)
    val selectedNotePreview: StateFlow<Note?> = _selectedNotePreview.asStateFlow()

    val allTags: StateFlow<List<String>> = _graphData.map { data ->
        data?.nodes?.flatMap { it.tags }?.filter { it.isNotBlank() }?.distinct()?.sorted() ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAiScanning = MutableStateFlow(false)
    val isAiScanning: StateFlow<Boolean> = _isAiScanning.asStateFlow()

    private val _aiScanProgress = MutableStateFlow<String?>(null)
    val aiScanProgress: StateFlow<String?> = _aiScanProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        buildGraph()
    }

    /**
     * Instantly builds the structural graph (wikilinks, shared tags, BM25)
     * in <30ms without blocking on heavy LLM inference.
     */
    fun buildGraph() {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val data = graphEngine.buildGraph(includeAiEnrichment = false)
                _graphData.value = data
                if (data.nodes.isEmpty()) {
                    _error.value = "No notes found in workspace"
                } else if (data.edges.isEmpty()) {
                    _error.value = "No connections found yet"
                }
            } catch (e: Exception) {
                _error.value = "Failed to build graph: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Triggers on-demand AI deep scan in the background to classify semantic relationships
     * without freezing the canvas or hiding the visual graph.
     */
    fun enrichWithAi() {
        val current = _graphData.value ?: return
        if (_isAiScanning.value) return
        _isAiScanning.value = true
        _aiScanProgress.value = "AI scanning connections..."
        viewModelScope.launch {
            try {
                val enriched = graphEngine.enrichGraphWithAi(current) { curr, total ->
                    _aiScanProgress.value = "AI analyzing pair $curr/$total..."
                }
                _graphData.value = enriched
            } catch (e: Exception) {
                _error.value = "AI scan: ${e.message}"
            } finally {
                _isAiScanning.value = false
                _aiScanProgress.value = null
            }
        }
    }

    fun selectNodeForPreview(noteId: String?) {
        if (noteId == null) {
            _selectedNotePreview.value = null
            return
        }
        viewModelScope.launch {
            try {
                _selectedNotePreview.value = repository.getNoteById(noteId)
            } catch (_: Exception) {
                _selectedNotePreview.value = null
            }
        }
    }

    fun detectContradictions() {
        _isLoading.value = true
        _error.value = null
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
        _error.value = null
        viewModelScope.launch {
            try {
                val timeline = ideaEvolution.traceEvolution(concept.trim())
                _evolutionTimeline.value = timeline
                if (timeline.relatedNotes.isEmpty()) {
                    _error.value = "No notes found mentioning '$concept'"
                }
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
