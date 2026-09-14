package com.altgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.altgallery.data.model.ImageEmbedding

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: ImageEmbedding): Long

    @Query("SELECT * FROM image_embeddings")
    suspend fun getAll(): List<ImageEmbedding>

    @Query("SELECT * FROM image_embeddings WHERE contentUri = :uri")
    suspend fun getByUri(uri: String): ImageEmbedding?

    @Query("SELECT contentUri FROM image_embeddings")
    suspend fun getAllUris(): List<String>

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun count(): Int

    @Query("DELETE FROM image_embeddings WHERE contentUri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM image_embeddings")
    suspend fun clear()
}
