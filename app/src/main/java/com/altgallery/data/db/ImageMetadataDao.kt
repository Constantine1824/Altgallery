package com.altgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageMetadataDao {

    @Upsert
    suspend fun upsert(metadata: ImageMetadata)

    @Upsert
    suspend fun upsertAll(metadata: List<ImageMetadata>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(fts: ImageMetadataFts)

    @Query("SELECT * FROM image_metadata ORDER BY dateTaken DESC")
    fun observeAll(): Flow<List<ImageMetadata>>

    @Query("SELECT * FROM image_metadata WHERE contentUri = :uri")
    suspend fun getByUri(uri: String): ImageMetadata?

    @Query("SELECT * FROM image_metadata WHERE clusterId = :clusterId ORDER BY dateTaken DESC")
    fun observeByCluster(clusterId: String): Flow<List<ImageMetadata>>

    @Query("SELECT contentUri FROM image_metadata")
    suspend fun getAllProcessedUris(): List<String>

    @Query("SELECT COUNT(*) FROM image_metadata")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM image_metadata WHERE isMeme = 1")
    fun observeMemeCount(): Flow<Int>

    // Plain LIKE fallback (works without the FTS table populated).
    @Query(
        """
        SELECT * FROM image_metadata
        WHERE description LIKE '%' || :term || '%'
           OR ocrText LIKE '%' || :term || '%'
           OR labels LIKE '%' || :term || '%'
           OR tags LIKE '%' || :term || '%'
           OR displayName LIKE '%' || :term || '%'
        ORDER BY dateTaken DESC
        """
    )
    suspend fun searchLike(term: String): List<ImageMetadata>

    // FTS MATCH search joined back to metadata.
    @Query(
        """
        SELECT m.* FROM image_metadata AS m
        JOIN image_metadata_fts AS f ON m.contentUri = f.contentUri
        WHERE image_metadata_fts MATCH :query
        """
    )
    suspend fun searchFts(query: String): List<ImageMetadata>

    @Query("DELETE FROM image_metadata")
    suspend fun clear()
}
