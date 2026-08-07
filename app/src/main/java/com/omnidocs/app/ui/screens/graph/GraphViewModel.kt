package com.omnidocs.app.ui.screens.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.graph.GraphData
import com.omnidocs.app.graph.GraphEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GraphViewModel @Inject constructor(
    private val graphEngine: GraphEngine
) : ViewModel() {

    private val _graphData = MutableStateFlow<GraphData?>(null)
    val graphData: StateFlow<GraphData?> = _graphData.asStateFlow()

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

    fun rebuildGraph() {
        buildGraph()
    }
}