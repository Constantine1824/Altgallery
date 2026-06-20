package com.altgallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.altgallery.data.model.Folder
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Upsert
    suspend fun upsert(folder: Folder)

    @Upsert
    suspend fun upsertAll(folders: List<Folder>)

    @Query("SELECT * FROM folders ORDER BY imageCount DESC")
    fun observeAll(): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): Folder?

    @Query("UPDATE folders SET imageCount = :count WHERE id = :id")
    suspend fun updateCount(id: String, count: Int)

    @Query("DELETE FROM folders")
    suspend fun clear()
}
