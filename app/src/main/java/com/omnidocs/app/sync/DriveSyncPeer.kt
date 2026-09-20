package com.omnidocs.app.sync

import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.remote.DriveService
import com.omnidocs.app.data.remote.NoteCloudCodec
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveSyncPeer @Inject constructor(
    private val driveService: DriveService
) : SyncPeer {

    override suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> {
        return try {
            val result = driveService.syncToCloud()
            if (result) {
                Result.success(notes.map { it.id })
            } else {
                Result.failure(Exception("Drive upload failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "pushNotes failed", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>> {
        return try {
            val raw = driveService.readNotesFromCloud()
            if (raw == null) {
                return Result.success(emptyList())
            }
            val allNotes = NoteCloudCodec.decode(raw)
            val filtered = if (sinceTimestamp > 0L) {
                // Tombstones must pass on deletedAt: soft-delete does not bump
                // updatedAt, so an updatedAt-only filter silently drops fresh
                // deletions and the note resurrects on the next push.
                allNotes.filter {
                    it.updatedAt > sinceTimestamp ||
                        (it.isDeleted && (it.deletedAt ?: 0L) > sinceTimestamp)
                }
            } else {
                allNotes
            }
            Result.success(filtered)
        } catch (e: Exception) {
            Log.e(TAG, "fetchRemoteChanges failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private val TAG = DriveSyncPeer::class.java.simpleName
    }
}
