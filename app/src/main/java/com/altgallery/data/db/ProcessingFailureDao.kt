package com.altgallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.altgallery.data.model.ProcessingFailure
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingFailureDao {

    @Upsert
    suspend fun upsertAll(failures: List<ProcessingFailure>)

    @Query("SELECT * FROM processing_failures ORDER BY failedAt DESC")
    suspend fun getAll(): List<ProcessingFailure>

    @Query("SELECT * FROM processing_failures ORDER BY failedAt DESC")
    fun observeAll(): Flow<List<ProcessingFailure>>

    @Query("DELETE FROM processing_failures")
    suspend fun clear()
}
