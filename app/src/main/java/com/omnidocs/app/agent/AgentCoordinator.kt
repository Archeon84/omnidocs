package com.omnidocs.app.agent

import com.omnidocs.app.data.local.AgentEventDao
import com.omnidocs.app.data.local.AgentJobDao
import com.omnidocs.app.data.local.entity.AgentEventEntity
import com.omnidocs.app.data.local.entity.AgentJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal JSON string escaper for audit/event payloads (quotes, backslash, control chars). */
internal fun escapeJsonString(raw: String): String = buildString(raw.length + 16) {
    for (c in raw) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/**
 * Lenient number readers: `has(key)` proves presence, not type, and LLM JSON
 * frequently delivers numbers as strings ("12", "high", 1.0, true). These
 * coerce instead of throwing, so one bad element can't abort a whole step.
 */
internal fun JSONObject.optIntLenient(key: String): Int? {
    if (isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
        is Boolean -> if (v) 1 else 0
        else -> null
    }
}

internal fun JSONObject.optLongLenient(key: String): Long? {
    if (isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
        is Boolean -> if (v) 1L else 0L
        else -> null
    }
}

internal fun JSONObject.optDoubleLenient(key: String): Double? {
    if (isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }
}

/**
 * Central orchestrator managing agent job lifecycles, state transitions,
 * privacy policy enforcement, cancellation, and event audit trails.
 */
@Singleton
class AgentCoordinator @Inject constructor(
    private val agentJobDao: AgentJobDao,
    private val agentEventDao: AgentEventDao,
    private val policyGuard: PolicyGuard
) {
    private val activeCancellations = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Sticky cancellation requests. Per-step flags in [activeCancellations] are
     * removed after each step, so a cancel landing BETWEEN steps was silently
     * lost; this set survives until the pipeline explicitly clears it.
     */
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Creates a new durable agent job in QUEUED status.
     */
    suspend fun createJob(
        jobType: String,
        inputRefsJson: String = "{}",
        isLocal: Boolean = true,
        modelId: String? = null
    ): AgentJobEntity = withContext(Dispatchers.IO) {
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = AgentJobEntity(
            id = jobId,
            jobType = jobType,
            inputRefsJson = inputRefsJson,
            status = AgentJobEntity.STATUS_QUEUED,
            progress = 0,
            isLocalExecution = isLocal,
            modelId = modelId,
            createdAt = now
        )
        agentJobDao.insertJob(job)
        recordEvent(
            jobId = jobId,
            agentId = "coordinator",
            eventType = "JOB_CREATED",
            safeMetadata = "{\"jobType\":\"$jobType\"}"
        )
        activeCancellations[jobId] = AtomicBoolean(false)
        job
    }

    /**
     * Executes an agent within the lifecycle of a job, handling policy enforcement,
     * status transitions, timing, cancellation, and error handling.
     */
    suspend fun runAgent(
        jobId: String,
        agent: Agent,
        input: AgentInput,
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY,
        modelPolicy: ModelPolicy = ModelPolicy(),
        requiresCloud: Boolean = false,
        onAnswerToken: (String) -> Unit = {}
    ): AgentResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Abort the whole pipeline when the caller's coroutine was cancelled.
        coroutineContext.ensureActive()

        // Re-seed a flag cancelled between steps (the per-step entry was removed).
        if (cancelledJobs.contains(jobId)) {
            activeCancellations.getOrPut(jobId) { AtomicBoolean(false) }.set(true)
        }

        // 1. Policy Guard Evaluation
        when (val policy = policyGuard.evaluate(privacyMode, modelPolicy, requiresCloud)) {
            is PolicyDecision.Denied -> {
                val errorMsg = "Policy denied: ${policy.reason}"
                markJobFailed(jobId, errorCode = "POLICY_DENIED", errorMessage = errorMsg, retryable = false)
                recordEvent(jobId, agent.id, "POLICY_DENIED", "{\"reason\":\"${policy.reason}\"}")
                return@withContext AgentResult.PermanentFailure(errorMsg)
            }
            is PolicyDecision.RequiresUserConsent -> {
                updateJobStatus(jobId, AgentJobEntity.STATUS_WAITING_FOR_USER)
                recordEvent(jobId, agent.id, "REQUIRES_USER_CONSENT", "{\"reason\":\"${policy.reason}\"}")
                return@withContext AgentResult.NeedsUserInput(policy.reason)
            }
            is PolicyDecision.Allowed -> {
                // Proceed
            }
        }

        // 2. Transition to RUNNING
        agentJobDao.startJob(jobId, AgentJobEntity.STATUS_RUNNING, System.currentTimeMillis())
        recordEvent(jobId, agent.id, "AGENT_STARTED", "{\"inputType\":\"${input.type}\"}")

        val cancellationFlag = activeCancellations.getOrPut(jobId) { AtomicBoolean(false) }
        val context = AgentContext(
            jobId = jobId,
            privacyMode = privacyMode,
            modelPolicy = modelPolicy,
            isCancelled = { cancellationFlag.get() },
            onAnswerToken = onAnswerToken
        )

        // 3. Execute Agent
        return@withContext try {
            if (context.isCancelled()) {
                markJobCancelled(jobId)
                return@withContext AgentResult.PermanentFailure("Job cancelled before execution")
            }

            val result = agent.execute(input, context)
            val durationMs = System.currentTimeMillis() - startTime

            when (result) {
                is AgentResult.Success -> {
                    agentJobDao.updateJobStatus(jobId, AgentJobEntity.STATUS_SUCCEEDED, System.currentTimeMillis())
                    agentJobDao.updateProgress(jobId, 100)
                    recordEvent(jobId, agent.id, "AGENT_SUCCEEDED", "{}", durationMs)
                }
                is AgentResult.NeedsUserInput -> {
                    agentJobDao.updateJobStatus(jobId, AgentJobEntity.STATUS_WAITING_FOR_USER)
                    recordEvent(jobId, agent.id, "WAITING_FOR_USER", "{\"question\":\"${result.question}\"}", durationMs)
                }
                is AgentResult.RetryableFailure -> {
                    agentJobDao.incrementRetryCount(jobId)
                    markJobFailed(jobId, "RETRYABLE_ERROR", result.reason, retryable = true)
                    recordEvent(jobId, agent.id, "AGENT_FAILED_RETRYABLE", "{\"reason\":\"${result.reason}\"}", durationMs)
                }
                is AgentResult.PermanentFailure -> {
                    markJobFailed(jobId, "PERMANENT_ERROR", result.reason, retryable = false)
                    recordEvent(jobId, agent.id, "AGENT_FAILED_PERMANENT", "{\"reason\":\"${result.reason}\"}", durationMs)
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val durationMs = System.currentTimeMillis() - startTime
            val message = e.message ?: e.javaClass.simpleName
            markJobFailed(jobId, "UNCAUGHT_EXCEPTION", message, retryable = true)
            recordEvent(jobId, agent.id, "AGENT_CRASHED", "{\"error\":\"$message\"}", durationMs)
            AgentResult.RetryableFailure("Agent threw exception: $message", e)
        } finally {
            activeCancellations.remove(jobId)
        }
    }

    /**
     * Signals cancellation for an active job.
     */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        cancelledJobs.add(jobId)
        activeCancellations[jobId]?.set(true)
        markJobCancelled(jobId)
        recordEvent(jobId, "coordinator", "JOB_CANCELLED", "{}")
    }

    /**
     * Clears cancellation state when a pipeline finishes (all terminal paths).
     * Must be called in a finally block by every pipeline coordinator, or the
     * sticky set leaks entries (keyed by unique jobId, so the leak is bounded
     * but the noise is not).
     */
    fun clearJobCancellation(jobId: String) {
        cancelledJobs.remove(jobId)
        activeCancellations.remove(jobId)
    }

    /**
     * Updates progress percentage for a job (0 to 100).
     */
    suspend fun updateProgress(jobId: String, progress: Int) = withContext(Dispatchers.IO) {
        agentJobDao.updateProgress(jobId, progress.coerceIn(0, 100))
    }

    private suspend fun updateJobStatus(jobId: String, status: String) {
        agentJobDao.updateJobStatus(jobId, status)
    }

    private suspend fun markJobCancelled(jobId: String) {
        agentJobDao.updateJobStatus(jobId, AgentJobEntity.STATUS_CANCELLED, System.currentTimeMillis())
    }

    private suspend fun markJobFailed(
        jobId: String,
        errorCode: String,
        errorMessage: String,
        retryable: Boolean
    ) {
        val status = if (retryable) {
            AgentJobEntity.STATUS_FAILED_RETRYABLE
        } else {
            AgentJobEntity.STATUS_FAILED_PERMANENT
        }
        val existing = agentJobDao.getJobById(jobId)
        if (existing != null) {
            val updated = existing.copy(
                status = status,
                errorCode = errorCode,
                errorMessage = errorMessage,
                completedAt = System.currentTimeMillis()
            )
            agentJobDao.updateJob(updated)
        }
    }

    /**
     * Records a structured, safe audit event without sensitive payload text.
     */
    suspend fun recordEvent(
        jobId: String,
        agentId: String,
        eventType: String,
        safeMetadata: String = "{}",
        durationMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        val event = AgentEventEntity(
            id = UUID.randomUUID().toString(),
            jobId = jobId,
            agentId = agentId,
            eventType = eventType,
            safeMetadata = safeMetadata,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis()
        )
        agentEventDao.insertEvent(event)
    }

    fun observeJob(jobId: String): Flow<List<AgentEventEntity>> {
        return agentEventDao.getEventsForJob(jobId)
    }

    fun observeRecentJobs(limit: Int = 50): Flow<List<AgentJobEntity>> {
        return agentJobDao.getRecentJobs(limit)
    }
}
