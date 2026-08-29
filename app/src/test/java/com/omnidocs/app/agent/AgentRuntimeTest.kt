package com.omnidocs.app.agent

import com.omnidocs.app.data.local.AgentEventDao
import com.omnidocs.app.data.local.AgentJobDao
import com.omnidocs.app.data.local.entity.AgentEventEntity
import com.omnidocs.app.data.local.entity.AgentJobEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AgentRuntimeTest {

    private lateinit var fakeJobDao: FakeAgentJobDao
    private lateinit var fakeEventDao: FakeAgentEventDao
    private lateinit var coordinator: AgentCoordinator
    private lateinit var policyGuard: PolicyGuard

    class FakeAgentJobDao : AgentJobDao {
        val jobs = ConcurrentHashMap<String, AgentJobEntity>()

        override suspend fun getJobById(id: String): AgentJobEntity? = jobs[id]

        override fun getJobsByStatus(status: String): Flow<List<AgentJobEntity>> =
            flowOf(jobs.values.filter { it.status == status })

        override fun getRecentJobs(limit: Int): Flow<List<AgentJobEntity>> =
            flowOf(jobs.values.sortedByDescending { it.createdAt }.take(limit))

        override suspend fun insertJob(job: AgentJobEntity) {
            jobs[job.id] = job
        }

        override suspend fun updateJob(job: AgentJobEntity) {
            jobs[job.id] = job
        }

        override suspend fun updateJobStatus(id: String, status: String, completedAt: Long?) {
            val existing = jobs[id]
            if (existing != null) {
                jobs[id] = existing.copy(status = status, completedAt = completedAt ?: existing.completedAt)
            }
        }

        override suspend fun startJob(id: String, status: String, startedAt: Long) {
            val existing = jobs[id]
            if (existing != null) {
                jobs[id] = existing.copy(status = status, startedAt = startedAt)
            }
        }

        override suspend fun updateProgress(id: String, progress: Int) {
            val existing = jobs[id]
            if (existing != null) {
                jobs[id] = existing.copy(progress = progress)
            }
        }

        override suspend fun incrementRetryCount(id: String) {
            val existing = jobs[id]
            if (existing != null) {
                jobs[id] = existing.copy(retryCount = existing.retryCount + 1)
            }
        }

        override suspend fun countActiveJobs(): Int =
            jobs.values.count { it.status in listOf("QUEUED", "RUNNING", "WAITING_FOR_MODEL") }
    }

    class FakeAgentEventDao : AgentEventDao {
        val events = mutableListOf<AgentEventEntity>()

        override fun getEventsForJob(jobId: String): Flow<List<AgentEventEntity>> =
            flowOf(events.filter { it.jobId == jobId })

        override fun getRecentEvents(limit: Int): Flow<List<AgentEventEntity>> =
            flowOf(events.takeLast(limit))

        override suspend fun insertEvent(event: AgentEventEntity) {
            events.add(event)
        }

        override suspend fun countEventsForJob(jobId: String): Int =
            events.count { it.jobId == jobId }
    }

    @Before
    fun setup() {
        fakeJobDao = FakeAgentJobDao()
        fakeEventDao = FakeAgentEventDao()

        policyGuard = object : PolicyGuard {
            override fun evaluate(
                privacyMode: PrivacyMode,
                modelPolicy: ModelPolicy,
                requiresCloud: Boolean
            ): PolicyDecision {
                if (privacyMode == PrivacyMode.LOCAL_ONLY && requiresCloud) {
                    return PolicyDecision.Denied("Cloud processing is forbidden in LOCAL_ONLY privacy mode.")
                }
                if (requiresCloud && !modelPolicy.allowedProviders.any { it != "llama_cpp" }) {
                    return PolicyDecision.RequiresUserConsent("Operation requires cloud AI provider which is not enabled.")
                }
                return PolicyDecision.Allowed
            }

            override fun isLocalModelReady(): Boolean = true
        }

        coordinator = AgentCoordinator(fakeJobDao, fakeEventDao, policyGuard)
    }

    @Test
    fun `coordinator creates job in QUEUED status and logs event`() = runTest {
        val job = coordinator.createJob(jobType = "DOCUMENT_EXTRACTION")
        assertEquals(AgentJobEntity.STATUS_QUEUED, job.status)
        assertEquals(0, job.progress)
        assertTrue(job.isLocalExecution)

        val retrieved = fakeJobDao.getJobById(job.id)
        assertNotNull(retrieved)
        assertEquals("DOCUMENT_EXTRACTION", retrieved?.jobType)

        val events = fakeEventDao.events.filter { it.jobId == job.id }
        assertEquals(1, events.size)
        assertEquals("JOB_CREATED", events.first().eventType)
    }

    @Test
    fun `coordinator executes agent successfully and transitions status to SUCCEEDED`() = runTest {
        val job = coordinator.createJob(jobType = "TEST_AGENT")
        val testAgent = object : Agent {
            override val id: String = "test_agent"
            override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
                return AgentResult.Success(mapOf("result" to "done"))
            }
        }

        val result = coordinator.runAgent(
            jobId = job.id,
            agent = testAgent,
            input = AgentInput(type = "TEST_INPUT"),
            privacyMode = PrivacyMode.LOCAL_ONLY
        )

        assertTrue(result is AgentResult.Success)
        val updatedJob = fakeJobDao.getJobById(job.id)
        assertEquals(AgentJobEntity.STATUS_SUCCEEDED, updatedJob?.status)
        assertEquals(100, updatedJob?.progress)
        assertNotNull(updatedJob?.completedAt)
    }

    @Test
    fun `coordinator blocks cloud execution when privacy mode is LOCAL_ONLY`() = runTest {
        val job = coordinator.createJob(jobType = "CLOUD_TASK")
        val testAgent = object : Agent {
            override val id: String = "cloud_agent"
            override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
                return AgentResult.Success()
            }
        }

        val result = coordinator.runAgent(
            jobId = job.id,
            agent = testAgent,
            input = AgentInput(type = "TEST_INPUT"),
            privacyMode = PrivacyMode.LOCAL_ONLY,
            requiresCloud = true
        )

        assertTrue(result is AgentResult.PermanentFailure)
        val updatedJob = fakeJobDao.getJobById(job.id)
        assertEquals(AgentJobEntity.STATUS_FAILED_PERMANENT, updatedJob?.status)
        assertEquals("POLICY_DENIED", updatedJob?.errorCode)
    }

    @Test
    fun `coordinator handles retryable failure and increments retry count`() = runTest {
        val job = coordinator.createJob(jobType = "FLAKY_TASK")
        val flakyAgent = object : Agent {
            override val id: String = "flaky_agent"
            override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
                return AgentResult.RetryableFailure("Temporary I/O glitch")
            }
        }

        val result = coordinator.runAgent(
            jobId = job.id,
            agent = flakyAgent,
            input = AgentInput(type = "TEST_INPUT")
        )

        assertTrue(result is AgentResult.RetryableFailure)
        val updatedJob = fakeJobDao.getJobById(job.id)
        assertEquals(AgentJobEntity.STATUS_FAILED_RETRYABLE, updatedJob?.status)
        assertEquals(1, updatedJob?.retryCount)
    }

    @Test
    fun `coordinator respects cancellation signal`() = runTest {
        val job = coordinator.createJob(jobType = "LONG_TASK")
        val longAgent = object : Agent {
            override val id: String = "long_agent"
            override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
                if (context.isCancelled()) {
                    return AgentResult.PermanentFailure("Cancelled")
                }
                return AgentResult.Success()
            }
        }

        coordinator.cancelJob(job.id)
        val result = coordinator.runAgent(
            jobId = job.id,
            agent = longAgent,
            input = AgentInput(type = "TEST_INPUT")
        )

        assertTrue(result is AgentResult.PermanentFailure)
        val updatedJob = fakeJobDao.getJobById(job.id)
        assertEquals(AgentJobEntity.STATUS_CANCELLED, updatedJob?.status)
    }
}
