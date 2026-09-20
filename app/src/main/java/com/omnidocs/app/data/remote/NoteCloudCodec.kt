package com.omnidocs.app.data.remote

import android.util.Log
import com.omnidocs.app.data.local.entity.NoteEntity
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "NoteCloudCodec"

object NoteCloudCodec {

    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_CONTENT = "content"
    private const val KEY_PLAIN_TEXT = "plainText"
    private const val KEY_IS_PINNED = "isPinned"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_IMAGE_URL = "imageUrl"
    private const val KEY_ATTACHMENTS = "attachments"
    private const val KEY_IS_DELETED = "isDeleted"
    private const val KEY_DELETED_AT = "deletedAt"
    private const val KEY_TAGS = "tags"
    private const val KEY_RELATED_NOTES = "relatedNotes"

    fun encode(notes: List<NoteEntity>): String {
        val array = JSONArray()
        notes.forEach { note ->
            val obj = JSONObject().apply {
                put(KEY_ID, note.id)
                put(KEY_TITLE, note.title)
                put(KEY_CONTENT, note.content)
                put(KEY_PLAIN_TEXT, note.plainText)
                put(KEY_IS_PINNED, note.isPinned)
                put(KEY_LANGUAGE, note.language)
                put(KEY_CREATED_AT, note.createdAt)
                put(KEY_UPDATED_AT, note.updatedAt)
                put(KEY_IMAGE_URL, note.imageUrl ?: "")
                put(KEY_ATTACHMENTS, note.attachments)
                put(KEY_IS_DELETED, note.isDeleted)
                put(KEY_DELETED_AT, note.deletedAt ?: 0L)
                put(KEY_TAGS, note.tags)
                put(KEY_RELATED_NOTES, note.relatedNotes)
            }
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * Lenient decode: one malformed row is skipped (logged) instead of
     * aborting the whole pull. A single bad note used to brick every sync
     * until the cloud file was hand-fixed.
     */
    fun decode(json: String): List<NoteEntity> {
        val array = JSONArray(json)
        val notes = mutableListOf<NoteEntity>()
        for (i in 0 until array.length()) {
            try {
                val o = array.getJSONObject(i)
                val id = o.optString(KEY_ID, "").trim()
                if (id.isEmpty()) {
                    Log.w(TAG, "Skipping cloud note at index $i: missing id")
                    continue
                }
                notes.add(
                    NoteEntity(
                        id = id,
                        title = o.optString(KEY_TITLE, ""),
                        content = o.optString(KEY_CONTENT, ""),
                        plainText = o.optString(KEY_PLAIN_TEXT, ""),
                        isPinned = o.optBoolean(KEY_IS_PINNED, false),
                        language = o.optString(KEY_LANGUAGE, "en").ifBlank { "en" },
                        createdAt = o.optLong(KEY_CREATED_AT, 0L),
                        updatedAt = o.optLong(KEY_UPDATED_AT, 0L),
                        imageUrl = o.optString(KEY_IMAGE_URL, "").ifBlank { null },
                        attachments = o.optString(KEY_ATTACHMENTS, "[]"),
                        isDeleted = o.optBoolean(KEY_IS_DELETED, false),
                        deletedAt = o.optLong(KEY_DELETED_AT, 0L).takeIf { it > 0L },
                        tags = o.optString(KEY_TAGS, "[]"),
                        relatedNotes = o.optString(KEY_RELATED_NOTES, "[]")
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed cloud note at index $i", e)
            }
        }
        return notes
    }
}
