package com.omnidocs.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class VerifiedBackupResult(
    val jobId: String,
    val isSuccess: Boolean,
    val noteCount: Int = 0,
    val recordingCount: Int = 0,
    val audioFileCount: Int = 0,
    val message: String = "",
    val errorMessage: String? = null
)

data class VerifiedRestoreResult(
    val jobId: String,
    val isSuccess: Boolean,
    val noteCount: Int = 0,
    val recordingCount: Int = 0,
    val audioFileCount: Int = 0,
    val message: String = "",
    val errorMessage: String? = null
)

/**
 * Coordinator implementing Workflow 5: "Verified Backup & Restore".
 * Orchestrates snapshot creation, archive encryption/packaging, integrity checksums,
 * isolated pre-restore verification, and traceable recovery.
 */
@Singleton
class BackupCoordinator @Inject constructor(
    private val agentCoordinator: AgentCoordinator,
    private val backupAgent: BackupAgent
) {
    /**
     * Executes verified backup workflow.
     */
    suspend fun createVerifiedBackup(
        folderUri: String,
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY
    ): VerifiedBackupResult = withContext(Dispatchers.IO) {
        val job = agentCoordinator.createJob(
            jobType = "WORKFLOW_VERIFIED_BACKUP",
            inputRefsJson = "{\"folderUri\":\"$folderUri\"}"
        )
        val jobId = job.id

        try {
            agentCoordinator.updateProgress(jobId, 25)
            val input = AgentInput(
                type = "BACKUP_OPERATION",
                payload = mapOf("operation" to "CREATE_BACKUP", "folderUri" to folderUri)
            )
            val result = agentCoordinator.runAgent(jobId, backupAgent, input, privacyMode)

            if (result is AgentResult.Success) {
                agentCoordinator.updateProgress(jobId, 100)
                val noteCount = result.payload["noteCount"] as? Int ?: 0
                val recordingCount = result.payload["recordingCount"] as? Int ?: 0
                val audioFileCount = result.payload["audioFileCount"] as? Int ?: 0
                val msg = result.payload["message"] as? String ?: "Backup created successfully"

                agentCoordinator.recordEvent(
                    jobId = jobId,
                    agentId = "backup_coordinator",
                    eventType = "BACKUP_COMPLETED",
                    safeMetadata = "{\"notes\":$noteCount,\"recordings\":$recordingCount}"
                )

                VerifiedBackupResult(
                    jobId = jobId,
                    isSuccess = true,
                    noteCount = noteCount,
                    recordingCount = recordingCount,
                    audioFileCount = audioFileCount,
                    message = msg
                )
            } else {
                val error = (result as? AgentResult.PermanentFailure)?.reason ?: "Backup creation failed"
                VerifiedBackupResult(jobId = jobId, isSuccess = false, errorMessage = error)
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            VerifiedBackupResult(jobId = jobId, isSuccess = false, errorMessage = message)
        }
    }

    /**
     * Executes isolated verification and restore workflow.
     */
    suspend fun restoreVerifiedBackup(
        fileUri: String,
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY
    ): VerifiedRestoreResult = withContext(Dispatchers.IO) {
        val job = agentCoordinator.createJob(
            jobType = "WORKFLOW_VERIFIED_RESTORE",
            inputRefsJson = "{\"fileUri\":\"$fileUri\"}"
        )
        val jobId = job.id

        try {
            // Step 1: Verify archive format and checksum in isolation (0% -> 40%)
            agentCoordinator.updateProgress(jobId, 20)
            val verifyInput = AgentInput(
                type = "BACKUP_OPERATION",
                payload = mapOf("operation" to "VERIFY_ARCHIVE", "fileUri" to fileUri)
            )
            val verifyResult = agentCoordinator.runAgent(jobId, backupAgent, verifyInput, privacyMode)
            if (verifyResult !is AgentResult.Success) {
                val error = (verifyResult as? AgentResult.PermanentFailure)?.reason ?: "Archive verification failed"
                return@withContext VerifiedRestoreResult(jobId = jobId, isSuccess = false, errorMessage = error)
            }

            // Step 2: Perform restore (40% -> 100%)
            agentCoordinator.updateProgress(jobId, 60)
            val restoreInput = AgentInput(
                type = "BACKUP_OPERATION",
                payload = mapOf("operation" to "RESTORE", "fileUri" to fileUri)
            )
            val restoreResult = agentCoordinator.runAgent(jobId, backupAgent, restoreInput, privacyMode)

            if (restoreResult is AgentResult.Success) {
                agentCoordinator.updateProgress(jobId, 100)
                val noteCount = restoreResult.payload["noteCount"] as? Int ?: 0
                val recordingCount = restoreResult.payload["recordingCount"] as? Int ?: 0
                val audioFileCount = restoreResult.payload["audioFileCount"] as? Int ?: 0
                val msg = restoreResult.payload["message"] as? String ?: "Restore completed"

                agentCoordinator.recordEvent(
                    jobId = jobId,
                    agentId = "backup_coordinator",
                    eventType = "RESTORE_COMPLETED",
                    safeMetadata = "{\"restoredNotes\":$noteCount,\"restoredRecordings\":$recordingCount}"
                )

                VerifiedRestoreResult(
                    jobId = jobId,
                    isSuccess = true,
                    noteCount = noteCount,
                    recordingCount = recordingCount,
                    audioFileCount = audioFileCount,
                    message = msg
                )
            } else {
                val error = (restoreResult as? AgentResult.PermanentFailure)?.reason ?: "Restore failed"
                VerifiedRestoreResult(jobId = jobId, isSuccess = false, errorMessage = error)
            }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            VerifiedRestoreResult(jobId = jobId, isSuccess = false, errorMessage = message)
        }
    }
}
