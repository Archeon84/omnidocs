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

            val jsonArray = JSONArray()

            notes.forEach { note ->
                val jsonObject = JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("content", note.content)
                    put("plainText", note.plainText)
                    put("isPinned", note.isPinned)
                    put("language", note.language)
                    put("createdAt", note.createdAt)
                    put("updatedAt", note.updatedAt)
                    put("imageUrl", note.imageUrl ?: "")
                    put("attachments", note.attachments)
                    put("isDeleted", note.isDeleted)
                }
                jsonArray.put(jsonObject)
            }

            val contentString = jsonArray.toString()

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
                // Batch mark all synced notes in a single query
                noteDao.markAsSynced(notes.map { it.id })
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncToCloud failed", e)
            false
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
                    val jsonArray = JSONArray(result)

                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        val cloudId = jsonObject.getString("id")
                        val cloudUpdatedAt = jsonObject.getLong("updatedAt")
                        // Use includeDeleted to avoid restoring locally-deleted notes from cloud
                        val localNote = noteDao.getNoteByIdIncludeDeleted(cloudId)

                        // Conflict resolution: keep local if it's newer or equal
                        if (localNote != null && localNote.updatedAt >= cloudUpdatedAt) {
                            continue
                        }

                        val note = NoteEntity(
                            id = cloudId,
                            title = jsonObject.getString("title"),
                            content = jsonObject.getString("content"),
                            plainText = jsonObject.getString("plainText"),
                            isPinned = jsonObject.getBoolean("isPinned"),
                            language = jsonObject.getString("language"),
                            createdAt = jsonObject.getLong("createdAt"),
                            updatedAt = cloudUpdatedAt,
                            imageUrl = jsonObject.optString("imageUrl", null),
                            attachments = jsonObject.optString("attachments", "[]"),
                            isDeleted = jsonObject.optBoolean("isDeleted", false),
                            isSynced = true
                        )
                        noteDao.insertNote(note)
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
