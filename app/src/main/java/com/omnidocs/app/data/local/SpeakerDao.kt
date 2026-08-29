package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.SpeakerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers ORDER BY displayName ASC")
    fun getAllSpeakers(): Flow<List<SpeakerEntity>>

    @Query("SELECT * FROM speakers WHERE id = :id")
    suspend fun getSpeakerById(id: String): SpeakerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeaker(speaker: SpeakerEntity)

    @Update
    suspend fun updateSpeaker(speaker: SpeakerEntity)

    @Query("DELETE FROM speakers WHERE id = :id")
    suspend fun deleteSpeaker(id: String)
}