package com.omnidocs.app.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.agent.AgentCoordinator
import com.omnidocs.app.data.local.AgentEventDao
import com.omnidocs.app.data.local.AgentJobDao
import com.omnidocs.app.data.local.entity.AgentEventEntity
import com.omnidocs.app.data.local.entity.AgentJobEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgentJobViewModel @Inject constructor(
    private val agentCoordinator: AgentCoordinator,
    private val agentJobDao: AgentJobDao,
    private val agentEventDao: AgentEventDao
) : ViewModel() {

    val recentJobs: StateFlow<List<AgentJobEntity>> = agentJobDao.getRecentJobs(limit = 100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedJob = MutableStateFlow<AgentJobEntity?>(null)
    val selectedJob: StateFlow<AgentJobEntity?> = _selectedJob.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedJobEvents: StateFlow<List<AgentEventEntity>> = _selectedJob
        .flatMapLatest { job ->
            if (job == null) flowOf(emptyList())
            else agentEventDao.getEventsForJob(job.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectJob(job: AgentJobEntity?) {
        _selectedJob.value = job
    }

    fun cancelJob(jobId: String) {
        viewModelScope.launch {
            agentCoordinator.cancelJob(jobId)
        }
    }
}
