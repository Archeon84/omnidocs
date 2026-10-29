package com.omnidocs.app.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ModelResolverTest {

    private val qwenModel = ModelInfo(
        id = "qwen_2_5_0_5b",
        name = "Qwen 2.5 0.5B",
        description = "Light",
        size = "350MB",
        downloadUrl = "http://example.com/qwen",
        fileName = "qwen.task",
        minRamGb = 3,
        isDownloaded = true
    )

    private val gemmaModel = ModelInfo(
        id = "gemma_4_e2b",
        name = "Gemma 4 E2B",
        description = "Heavy",
        size = "1.1GB",
        downloadUrl = "http://example.com/gemma",
        fileName = "gemma.litertlm",
        minRamGb = 6,
        isDownloaded = true
    )

    @Test
    fun resolveActiveModel_prefersSelectedWhenDownloaded() = runBlocking {
        val modelPrefs = mock(ModelPreferences::class.java)
        val downloadMgr = mock(ModelDownloadManager::class.java)

        `when`(modelPrefs.selectedModelId).thenReturn(flowOf("qwen_2_5_0_5b"))
        `when`(modelPrefs.getDefaultModelId()).thenReturn("gemma_4_e2b")
        `when`(downloadMgr.getDownloadedModels()).thenReturn(listOf(qwenModel, gemmaModel))

        val resolved = resolveActiveModel(modelPrefs, downloadMgr)
        assertNotNull(resolved)
        assertEquals("qwen_2_5_0_5b", resolved?.id)
    }

    @Test
    fun resolveActiveModel_fallsBackToDefaultWhenSelectedNotDownloaded() = runBlocking {
        val modelPrefs = mock(ModelPreferences::class.java)
        val downloadMgr = mock(ModelDownloadManager::class.java)

        `when`(modelPrefs.selectedModelId).thenReturn(flowOf("missing_model"))
        `when`(modelPrefs.getDefaultModelId()).thenReturn("qwen_2_5_0_5b")
        `when`(downloadMgr.getDownloadedModels()).thenReturn(listOf(qwenModel))

        val resolved = resolveActiveModel(modelPrefs, downloadMgr)
        assertNotNull(resolved)
        assertEquals("qwen_2_5_0_5b", resolved?.id)
    }

    @Test
    fun resolveActiveModel_fallsBackToAnyDownloadedWhenDefaultNotDownloaded() = runBlocking {
        val modelPrefs = mock(ModelPreferences::class.java)
        val downloadMgr = mock(ModelDownloadManager::class.java)

        `when`(modelPrefs.selectedModelId).thenReturn(flowOf("missing_model"))
        `when`(modelPrefs.getDefaultModelId()).thenReturn("gemma_4_e2b")
        // Only Qwen is downloaded
        `when`(downloadMgr.getDownloadedModels()).thenReturn(listOf(qwenModel))

        val resolved = resolveActiveModel(modelPrefs, downloadMgr)
        assertNotNull(resolved)
        assertEquals("qwen_2_5_0_5b", resolved?.id)
    }

    @Test
    fun resolveActiveModel_returnsNullWhenNothingDownloaded() = runBlocking {
        val modelPrefs = mock(ModelPreferences::class.java)
        val downloadMgr = mock(ModelDownloadManager::class.java)

        `when`(modelPrefs.selectedModelId).thenReturn(flowOf("qwen_2_5_0_5b"))
        `when`(modelPrefs.getDefaultModelId()).thenReturn("qwen_2_5_0_5b")
        `when`(downloadMgr.getDownloadedModels()).thenReturn(emptyList())

        val resolved = resolveActiveModel(modelPrefs, downloadMgr)
        assertNull(resolved)
    }
}
