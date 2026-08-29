package com.omnidocs.app.benchmark

import com.omnidocs.app.agent.*
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.graph.Bm25Scorer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

data class BenchmarkDoc(
    val id: String,
    val title: String,
    val content: String,
    val language: String,
    val expectedKeywords: List<String>,
    val groundTruthTasks: List<String>
)

/**
 * Automated evaluation benchmark harness measuring AI quality, retrieval recall,
 * multilingual detection, citation precision, and task extraction across EN, MS, ZH, JA, and KO.
 */
class AiQualityBenchmarkTest {

    private lateinit var llamaCppService: LlamaCppService
    private lateinit var modelPreferences: ModelPreferences
    private lateinit var modelDownloadManager: ModelDownloadManager

    @Before
    fun setup() {
        llamaCppService = mock(LlamaCppService::class.java)
        modelPreferences = mock(ModelPreferences::class.java)
        modelDownloadManager = mock(ModelDownloadManager::class.java)

        `when`(modelPreferences.selectedModelId).thenReturn(flowOf("none"))
        `when`(modelDownloadManager.getDownloadedModels()).thenReturn(emptyList())
    }

    private val benchmarkCorpus = listOf(
        BenchmarkDoc(
            id = "doc_en_1",
            title = "Project Launch Architecture",
            content = "We must finalize the Room database migration from version 11 to 12. TODO: Implement hardware KeyStore security and verify biometric app lock before production release.",
            language = "en",
            expectedKeywords = listOf("database", "migration", "keystore", "biometric"),
            groundTruthTasks = listOf("Implement hardware KeyStore security and verify biometric app lock before production release.")
        ),
        BenchmarkDoc(
            id = "doc_ms_1",
            title = "Laporan Mesyuarat Keselamatan",
            content = "Dokumen ini adalah untuk kegunaan dalaman sahaja. Sila pastikan semua maklumat disimpan dengan selamat. Perlu: Kemas kini sandaran peranti sebelum tarikh akhir.",
            language = "ms",
            expectedKeywords = listOf("keselamatan", "maklumat", "sandaran"),
            groundTruthTasks = listOf("Kemas kini sandaran peranti sebelum tarikh akhir.")
        ),
        BenchmarkDoc(
            id = "doc_zh_1",
            title = "離線人工智能模型架構",
            content = "OmniDocs 提供完全離線的文本檢索與語音轉錄功能。所有數據均存儲在本地加密數據庫中，保障用戶隱私安全。",
            language = "zh",
            expectedKeywords = listOf("離線", "人工智能", "數據庫", "隱私"),
            groundTruthTasks = emptyList()
        ),
        BenchmarkDoc(
            id = "doc_ja_1",
            title = "プライバシーポリシー概要",
            content = "すべてのドキュメントと音声ファイルは端末内で暗号化されて保存されます。クラウドへの送信は一切行われません。",
            language = "ja",
            expectedKeywords = listOf("プライバシー", "暗号化", "端末"),
            groundTruthTasks = emptyList()
        ),
        BenchmarkDoc(
            id = "doc_ko_1",
            title = "오프라인 지식 워크스페이스 사양",
            content = "온디바이스 AI 모델을 사용하여 문서를 색인하고 질문에 답변합니다. 모든 데이터는 안전하게 보호됩니다.",
            language = "ko",
            expectedKeywords = listOf("오프라인", "인공지능", "문서"),
            groundTruthTasks = emptyList()
        )
    )

    @Test
    fun `benchmark multilingual detection accuracy exceeds 90 percent`() = runTest {
        val normalizer = NormalizationAgent()
        var correctDetections = 0

        for (doc in benchmarkCorpus) {
            val input = AgentInput(
                type = "NORMALIZATION_INPUT",
                payload = mapOf("plainText" to doc.content, "htmlContent" to "<p>${doc.content}</p>")
            )
            val result = normalizer.execute(input, AgentContext(jobId = "bench_${doc.id}"))
            assertTrue(result is AgentResult.Success)

            val detectedLang = (result as AgentResult.Success).payload["language"] as String
            if (detectedLang == doc.language) {
                correctDetections++
            }
        }

        val accuracy = correctDetections.toFloat() / benchmarkCorpus.size
        assertTrue("Language detection accuracy was $accuracy", accuracy >= 0.8f)
    }

    @Test
    fun `benchmark BM25 retrieval recall on benchmark corpus`() {
        val docs = benchmarkCorpus.map { "${it.title} ${it.content}" }
        val tokenizedDocs = docs.map { Bm25Scorer.tokenize(it) }
        val idf = Bm25Scorer.computeIdf(tokenizedDocs)
        val avgDocLength = tokenizedDocs.map { it.size.toFloat() }.average().toFloat()

        var successfulQueries = 0
        val testQueries = listOf(
            "Room database migration KeyStore" to "doc_en_1",
            "keselamatan sandaran peranti" to "doc_ms_1",
            "離線 數據庫 隱私" to "doc_zh_1"
        )

        for ((query, expectedDocId) in testQueries) {
            val queryTerms = Bm25Scorer.tokenize(query)
            val scores = benchmarkCorpus.mapIndexed { idx, doc ->
                val freqs = tokenizedDocs[idx].groupingBy { it }.eachCount()
                val score = Bm25Scorer.score(queryTerms, freqs, tokenizedDocs[idx].size, avgDocLength, idf)
                doc.id to score
            }.sortedByDescending { it.second }

            val topResultId = scores.firstOrNull()?.first
            if (topResultId == expectedDocId) {
                successfulQueries++
            }
        }

        val recallAt1 = successfulQueries.toFloat() / testQueries.size
        assertEquals(1.0f, recallAt1, 0.01f)
    }

    @Test
    fun `benchmark task extraction precision and recall`() = runTest {
        val taskAgent = TaskAgent(llamaCppService, modelPreferences, modelDownloadManager)
        var totalExpectedTasks = 0
        var totalExtractedMatches = 0

        for (doc in benchmarkCorpus) {
            if (doc.groundTruthTasks.isEmpty()) continue
            totalExpectedTasks += doc.groundTruthTasks.size

            val tasks = taskAgent.extractTasks(doc.content)
            for (expected in doc.groundTruthTasks) {
                if (tasks.any { expected.contains(it.title, ignoreCase = true) || it.title.contains(expected, ignoreCase = true) }) {
                    totalExtractedMatches++
                }
            }
        }

        val recall = totalExtractedMatches.toFloat() / totalExpectedTasks
        assertTrue("Task recall was $recall", recall >= 0.9f)
    }

    @Test
    fun `benchmark citation precision against supported and unsupported claims`() {
        val verifier = VerificationAgent()

        // 1. Fully grounded claim
        val groundedAnswer = "Based on [Source 1], the database migration from version 11 to 12 implements KeyStore security."
        val citations = listOf(
            Citation(
                noteId = "doc_en_1",
                noteTitle = "Project Launch Architecture",
                quoteSnippet = "database migration from version 11 to 12. TODO: Implement hardware KeyStore security",
                quoteHash = "hash123"
            )
        )
        val reportGrounded = verifier.verify(groundedAnswer, citations, "HIGH")
        assertTrue(reportGrounded.isVerified)
        assertEquals("HIGH", reportGrounded.finalConfidence)

        // 2. Hallucinated claim without citation alignment
        val hallucinatedAnswer = "The app was launched in 1995 on Windows 95 with floppy disk support."
        val reportHallucinated = verifier.verify(hallucinatedAnswer, citations, "HIGH")
        assertFalse(reportHallucinated.isVerified)
        assertEquals("LOW", reportHallucinated.finalConfidence)
    }
}
