package com.omnidocs.app.agent

import javax.inject.Inject
import javax.inject.Singleton

enum class QueryType {
    EXACT,
    SEMANTIC,
    TASK,
    ENTITY,
    DATE,
    MIXED
}

data class RetrievalStrategy(
    val queryType: QueryType,
    val bm25Weight: Float,
    val semanticWeight: Float,
    val useRrf: Boolean = true,
    val topK: Int = 10
)

/**
 * Agent responsible for analyzing the user's query intent,
 * selecting the optimal retrieval strategy, and configuring fusion parameters.
 */
@Singleton
class QueryClassifierAgent @Inject constructor() : Agent {

    override val id: String = "agent_query_classifier"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Query classification cancelled by user")
        }

        val query = input.payload["query"] ?: ""
        if (query.isBlank()) {
            return AgentResult.PermanentFailure("Query is blank")
        }

        val strategy = classify(query)

        return AgentResult.Success(
            payload = mapOf(
                "queryType" to strategy.queryType.name,
                "bm25Weight" to strategy.bm25Weight,
                "semanticWeight" to strategy.semanticWeight,
                "useRrf" to strategy.useRrf,
                "topK" to strategy.topK
            )
        )
    }

    fun classify(query: String): RetrievalStrategy {
        val trimmed = query.trim()
        val lower = trimmed.lowercase()

        // 1. Exact Match (quoted strings or code/identifier tokens)
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || trimmed.contains("`") || trimmed.contains("::")) {
            return RetrievalStrategy(
                queryType = QueryType.EXACT,
                bm25Weight = 0.9f,
                semanticWeight = 0.1f,
                useRrf = false,
                topK = 10
            )
        }

        // 2. Task Query
        val taskKeywords = listOf("todo", "task", "action item", "due", "deadline", "assigned", "action items", "perlu", "tugas")
        if (taskKeywords.any { lower.contains(it) }) {
            return RetrievalStrategy(
                queryType = QueryType.TASK,
                bm25Weight = 0.7f,
                semanticWeight = 0.3f,
                useRrf = true,
                topK = 15
            )
        }

        // 3. Date / Time Query
        val datePatterns = listOf(
            Regex("\\b(today|yesterday|tomorrow|january|february|march|april|may|june|july|august|september|october|november|december)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
            Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b")
        )
        if (datePatterns.any { it.containsMatchIn(lower) }) {
            return RetrievalStrategy(
                queryType = QueryType.DATE,
                bm25Weight = 0.6f,
                semanticWeight = 0.4f,
                useRrf = true,
                topK = 12
            )
        }

        // 4. Entity / Person / Place
        val entityKeywords = listOf("who is", "what is", "where is", "siapa", "apakah", "client", "project")
        if (entityKeywords.any { lower.startsWith(it) }) {
            return RetrievalStrategy(
                queryType = QueryType.ENTITY,
                bm25Weight = 0.5f,
                semanticWeight = 0.5f,
                useRrf = true,
                topK = 10
            )
        }

        // 5. Semantic Question vs Mixed
        val isQuestion = lower.startsWith("how") || lower.startsWith("why") || lower.startsWith("explain") || lower.endsWith("?")
        return if (isQuestion) {
            RetrievalStrategy(
                queryType = QueryType.SEMANTIC,
                bm25Weight = 0.3f,
                semanticWeight = 0.7f,
                useRrf = true,
                topK = 10
            )
        } else {
            RetrievalStrategy(
                queryType = QueryType.MIXED,
                bm25Weight = 0.5f,
                semanticWeight = 0.5f,
                useRrf = true,
                topK = 10
            )
        }
    }
}
