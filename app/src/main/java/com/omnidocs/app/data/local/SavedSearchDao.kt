package com.omnidocs.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidocs.app.data.local.entity.SavedSearchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedSearchDao {

    @Query("SELECT * FROM saved_searches ORDER BY lastUsedAt DESC")
    fun getAllSavedSearches(): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM saved_searches WHERE runOnOpen = 1")
    fun getAutoRunSearches(): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM saved_searches WHERE id = :id")
    suspend fun getById(id: String): SavedSearchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(search: SavedSearchEntity)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE saved_searches SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun markUsed(id: String, timestamp: Long)
}
