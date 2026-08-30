package com.omnidocs.app.agent

sealed interface ToolExecutionResult {
    data class Success(val outputJson: String) : ToolExecutionResult
    data class ApprovalRequired(
        val toolName: String,
        val confirmationMessage: String,
        val payloadJson: String
    ) : ToolExecutionResult
    data class Failure(val error: String) : ToolExecutionResult
}

/**
 * Narrowly scoped tool exposed to agent workflows.
 * Enforces strict authorization, read-only vs mutating classification,
 * user approval gates, timeouts, and audit logging per the Evidence-First AI Master Plan.
 */
interface AgentTool {
    val name: String
    val description: String
    val inputSchemaJson: String
    val outputSchemaJson: String
    val isMutating: Boolean
    val requiresUserApproval: Boolean
    val timeoutMs: Long get() = 15_000L
    val auditEventType: String

    suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult
}
