package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.EntityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EntityDao {
    @Query("SELECT * FROM entities ORDER BY canonicalName ASC")
    fun getAllEntities(): Flow<List<EntityEntity>>

    @Query("SELECT * FROM entities WHERE entityType = :type ORDER BY canonicalName ASC")
    fun getEntitiesByType(type: String): Flow<List<EntityEntity>>

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun getEntityById(id: String): EntityEntity?

    @Query("SELECT * FROM entities WHERE canonicalName = :name LIMIT 1")
    suspend fun getEntityByName(name: String): EntityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntity(entity: EntityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntities(entities: List<EntityEntity>)

    @Update
    suspend fun updateEntity(entity: EntityEntity)

    @Query("DELETE FROM entities WHERE id = :id")
    suspend fun deleteEntity(id: String)
}