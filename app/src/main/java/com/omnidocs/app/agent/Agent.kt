package com.omnidocs.app.agent

/**
 * Core interface that all specialized agents implement.
 * Agents perform discrete, scoped operations and must never directly mutate arbitrary tables.
 */
interface Agent {
    val id: String
    suspend fun execute(input: AgentInput, context: AgentContext): AgentResult
}

/**
 * Input envelope passed to an agent execution.
 */
data class AgentInput(
    val type: String,
    val payload: Map<String, String> = emptyMap()
)

/**
 * Execution context carrying job provenance, privacy mode, model policy, and cancellation signal.
 */
data class AgentContext(
    val jobId: String,
    val workspaceId: String = "default",
    val privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY,
    val modelPolicy: ModelPolicy = ModelPolicy(),
    val isCancelled: () -> Boolean = { false }
)

/**
 * Typed domain outcome returned by an agent.
 */
sealed interface AgentResult {
    data class Success(val payload: Map<String, Any> = emptyMap()) : AgentResult
    data class NeedsUserInput(val question: String, val details: Map<String, String> = emptyMap()) : AgentResult
    data class RetryableFailure(val reason: String, val cause: Throwable? = null) : AgentResult
    data class PermanentFailure(val reason: String, val cause: Throwable? = null) : AgentResult
}

/**
 * Privacy policy constraint for agent operations.
 */
enum class PrivacyMode {
    LOCAL_ONLY,
    CLOUD_ALLOWED
}

/**
 * Model execution policy enforced by PolicyGuard.
 */
data class ModelPolicy(
    val preferLocal: Boolean = true,
    val allowedProviders: Set<String> = setOf("llama_cpp"),
    val maxCostEstimate: Double = 0.0
)
