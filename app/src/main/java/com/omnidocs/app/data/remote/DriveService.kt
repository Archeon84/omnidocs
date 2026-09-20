package com.omnidocs.app.data.remote

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)) // Reuse connections across requests
        .build()

    private val folderName = "OmniDocs"
    private val fileName = "notes.json"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val TAG = "DriveService"
    private val MULTIPART_BOUNDARY = "OmniDocsBoundary"

    private fun buildMultipartUploadBody(metadata: String, content: String): String {
        return buildString {
            append("--$MULTIPART_BOUNDARY\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$MULTIPART_BOUNDARY\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content)
            append("\r\n--$MULTIPART_BOUNDARY--\r\n")
        }
    }

    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/drive.appdata"
            )
        } catch (e: GoogleAuthException) {
            Log.e(TAG, "Auth error", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
            null
        }
    }

    private suspend fun makeRequest(url: String, method: String = "GET", body: String? = null, contentType: String? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                val token = getAccessToken()
                if (token == null) {
                    return@withContext null
                }

                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")

                val mediaType = if (contentType != null) contentType.toMediaType() else jsonType

                when (method) {
                    "POST" -> {
                        requestBuilder.post((body ?: "{}").toRequestBody(mediaType))
                    }
                    "PUT" -> {
                        requestBuilder.put((body ?: "{}").toRequestBody(mediaType))
                    }
                    "PATCH" -> {
                        requestBuilder.patch((body ?: "{}").toRequestBody(mediaType))
                    }
                    "DELETE" -> {
                        requestBuilder.delete()
                    }
                }

                val response = client.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful) {
                    responseBody
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "makeRequest failed", e)
                null
            }
        }
    }

    suspend fun syncToCloud(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Include all notes (including soft-deleted) so the cloud copy stays accurate
            val notes = noteDao.getAllNotesIncludeDeleted()

            // Incremental optimisation: skip upload when nothing changed since last sync
            if (notes.all { it.isSynced }) {
                Log.d(TAG, "No unsynced changes — skipping upload")
                return@withContext true
            }

            val contentString = NoteCloudCodec.encode(notes)

            // Find or create folder
            val folderId = findOrCreateFolder()

            // Find or create file
            val fileId = findFile(folderId)

            val result = if (fileId != null) {
                // Update existing file
                makeRequest(
                    "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media",
                    "PATCH",
                    contentString,
                    "application/json"
                )
            } else {
                // Create new file with multipart/related upload
                val metadata = JSONObject().apply {
                    put("name", fileName)
                    put("mimeType", "application/json")
                    put("parents", JSONArray().apply { put(folderId) })
                }

                val multipartBody = buildMultipartUploadBody(metadata.toString(), contentString)
                makeRequest(
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                    "POST",
                    multipartBody,
                    "multipart/related; boundary=$MULTIPART_BOUNDARY"
                )
            }

            if (result != null) {
                // Only mark rows that are byte-identical to the uploaded snapshot.
                // Anything edited mid-upload keeps isSynced=0 and rides the next pass;
                // the old code marked everything, silently dropping those edits.
                val snapshot = notes.associate { it.id to it.updatedAt }
                val current = noteDao.getNotesByIdsIncludeDeleted(snapshot.keys.toList())
                val unchanged = current
                    .filter { snapshot[it.id] == it.updatedAt }
                    .map { it.id }
                if (unchanged.isNotEmpty()) {
                    noteDao.markAsSynced(unchanged)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncToCloud failed", e)
            false
        }
    }

    suspend fun readNotesFromCloud(): String? = withContext(Dispatchers.IO) {
        try {
            val folderId = findOrCreateFolder()
            val fileId = findFile(folderId)
            if (fileId != null) {
                makeRequest(
                    "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "readNotesFromCloud failed", e)
            null
        }
    }

    suspend fun syncFromCloud(): Boolean = withContext(Dispatchers.IO) {
        try {
            val folderId = findOrCreateFolder()
            val fileId = findFile(folderId)

            if (fileId != null) {
                val result = makeRequest(
                    "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                )

                if (result != null) {
                    val cloudNotes = NoteCloudCodec.decode(result)
                    for (remote in cloudNotes) {
                        applyRemoteNote(remote)
                    }
                    true
                } else {
                    false
                }
            } else {
                true // No file to restore from
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncFromCloud failed", e)
            false
        }
    }

    /**
     * Merge a single remote note into the local database with last-write-wins semantics.
     *
     * - No local copy: insert remote as synced.
     * - Either side deleted: the side with the newer effective timestamp wins.
     * - Local has unsynced edits newer than remote: keep local, do NOT overwrite.
     * - Otherwise (remote newer or equal): accept remote as synced.
     */
    internal suspend fun applyRemoteNote(remote: NoteEntity) {
        val local = noteDao.getNoteByIdIncludeDeleted(remote.id) ?: run {
            noteDao.insertNote(remote.copy(isSynced = true))
            return
        }
        // Local wins ties (matches SyncQueueManager): same-millisecond edits
        // and skewed clocks otherwise flap or destroy offline work.
        if (local.isDeleted || remote.isDeleted) {
            val localEffective = local.deletedAt ?: local.updatedAt
            val remoteEffective = remote.deletedAt ?: remote.updatedAt
            if (remoteEffective > localEffective) {
                noteDao.insertNote(remote.copy(isSynced = true))
            }
            return
        }
        if (!local.isSynced && local.updatedAt >= remote.updatedAt) {
            // Local unsynced edit is newer: preserve it; it will be pushed on next syncToCloud.
            Log.d(TAG, "applyRemoteNote: keeping newer local edit for ${remote.id}")
            return
        }
        noteDao.insertNote(remote.copy(isSynced = true))
    }

    private suspend fun findOrCreateFolder(): String {
        val query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
        val result = makeRequest(
            "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id)"
        )

        if (result != null) {
            val json = JSONObject(result)
            val files = json.getJSONArray("files")
            if (files.length() > 0) {
                return files.getJSONObject(0).getString("id")
            }
        }

        // Create folder
        val metadata = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
        }

        val createResult = makeRequest(
            "https://www.googleapis.com/drive/v3/files",
            "POST",
            metadata.toString()
        )

        if (createResult != null) {
            val json = JSONObject(createResult)
            return json.getString("id")
        }

        throw Exception("Failed to create folder")
    }

    private suspend fun findFile(folderId: String): String? {
        val query = "name='$fileName' and '$folderId' in parents and trashed=false"
        val result = makeRequest(
            "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id)"
        )

        if (result != null) {
            val json = JSONObject(result)
            val files = json.getJSONArray("files")
            if (files.length() > 0) {
                return files.getJSONObject(0).getString("id")
            }
        }

        return null
    }

    fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    fun signOut() {
        val googleSignInClient = GoogleSignIn.getClient(context, createSignInOptions())
        googleSignInClient.signOut()
    }

    companion object {
        fun createSignInOptions(): GoogleSignInOptions {
            return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
                .requestScopes(Scope("https://www.googleapis.com/auth/drive.appdata"))
                .build()
        }
    }
}
