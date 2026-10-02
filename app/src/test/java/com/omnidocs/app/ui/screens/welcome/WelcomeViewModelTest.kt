package com.omnidocs.app.ui.screens.welcome

import com.omnidocs.app.ai.DownloadState
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.stt.SttModelInfo
import com.omnidocs.app.stt.SttModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var modelPreferences: ModelPreferences
    private lateinit var onboardingPreferences: OnboardingPreferences
    private lateinit var viewModel: WelcomeViewModel

    private val dummyLlm = ModelInfo(
        id = "gemma_4_e2b",
        name = "Gemma 4 E2B",
        description = "Test LLM",
        size = "1.1 GB",
        downloadUrl = "https://example.com/model.bin",
        fileName = "model.bin"
    )

    private val dummyEmbedding = ModelInfo(
        id = "multilingual_e5_small",
        name = "Multilingual E5 Small",
        description = "Test Embeddings",
        size = "126 MB",
        downloadUrl = "https://example.com/emb.bin",
        fileName = "emb.bin"
    )

    private val dummyStt = SttModelInfo(
        id = "moonshine_tiny_en",
        name = "Moonshine Tiny",
        description = "Test STT",
        size = "50 MB",
        downloadUrl = "https://example.com/stt.tar.bz2",
        fileName = "stt.tar.bz2",
        extractedDirName = "moonshine",
        sha256 = null,
        languages = listOf("en"),
        modelType = SttModelType.MOONSHINE
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        modelDownloadManager = mock(ModelDownloadManager::class.java)
        modelPreferences = mock(ModelPreferences::class.java)
        onboardingPreferences = mock(OnboardingPreferences::class.java)

        `when`(modelDownloadManager.downloadState).thenReturn(MutableStateFlow(DownloadState.Idle))
        `when`(modelDownloadManager.embeddingDownloadState).thenReturn(MutableStateFlow(DownloadState.Idle))
        `when`(modelDownloadManager.sttDownloadState).thenReturn(MutableStateFlow(DownloadState.Idle))
        `when`(modelDownloadManager.availableModels).thenReturn(listOf(dummyLlm))
        `when`(modelDownloadManager.embeddingModels).thenReturn(listOf(dummyEmbedding))
        `when`(modelDownloadManager.sttModels).thenReturn(listOf(dummyStt))
        `when`(modelDownloadManager.getDownloadedModels()).thenReturn(listOf(dummyLlm))
        `when`(modelDownloadManager.getDownloadedEmbeddingModels()).thenReturn(listOf(dummyEmbedding))
        `when`(modelDownloadManager.getDownloadedSttModels()).thenReturn(listOf(dummyStt))

        viewModel = WelcomeViewModel(
            modelDownloadManager = modelDownloadManager,
            modelPreferences = modelPreferences,
            onboardingPreferences = onboardingPreferences
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `available models are exposed from download manager`() {
        assertEquals(1, viewModel.availableLlmModels.size)
        assertEquals("gemma_4_e2b", viewModel.availableLlmModels[0].id)
        assertEquals(1, viewModel.availableEmbeddingModels.size)
        assertEquals("multilingual_e5_small", viewModel.availableEmbeddingModels[0].id)
        assertEquals(1, viewModel.availableSttModels.size)
        assertEquals("moonshine_tiny_en", viewModel.availableSttModels[0].id)
    }

    @Test
    fun `downloadModel triggers download and updates preferences`() = runTest {
        viewModel.downloadModel(dummyLlm)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(modelDownloadManager).downloadModel(dummyLlm)
        verify(modelPreferences).setSelectedModelId("gemma_4_e2b")
    }

    @Test
    fun `downloadEmbeddingModel triggers download and updates preferences`() = runTest {
        viewModel.downloadEmbeddingModel(dummyEmbedding)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(modelDownloadManager).downloadEmbeddingModel(dummyEmbedding)
        verify(modelPreferences).setSelectedEmbeddingModelId("multilingual_e5_small")
    }

    @Test
    fun `downloadSttModel triggers download and updates preferences`() = runTest {
        viewModel.downloadSttModel(dummyStt)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(modelDownloadManager).downloadSttModel(dummyStt)
        verify(modelPreferences).setSelectedSttModelId("moonshine_tiny_en")
    }

    @Test
    fun `completeOnboarding sets flag in onboarding preferences`() {
        viewModel.completeOnboarding()
        verify(onboardingPreferences).setOnboardingCompleted(true)
    }
}
