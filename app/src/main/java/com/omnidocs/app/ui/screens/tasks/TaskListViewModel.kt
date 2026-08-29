package com.omnidocs.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val actionItemDao: ActionItemDao
) : ViewModel() {

    val activeItems = actionItemDao.getActiveActionItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allItems = actionItemDao.getAllActionItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeCount = actionItemDao.getActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val completedCount = actionItemDao.getCompletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val overdueCount = actionItemDao.getOverdueCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun completeItem(item: ActionItemEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            actionItemDao.completeActionItem(
                id = item.id,
                status = "completed",
                completedAt = now,
                updatedAt = now
            )
        }
    }
}
