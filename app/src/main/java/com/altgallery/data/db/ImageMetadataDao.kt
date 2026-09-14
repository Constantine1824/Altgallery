package com.altgallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * Raw FTS insert. Do NOT call directly: the FTS table is virtual and
     * carries no unique constraint, so REPLACE cannot deduplicate and every
     * bare call appends a duplicate row. Use [replaceFts], which owns the
     * delete-then-insert. This method stays visible only because Room must
     * implement it for [replaceFts].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(fts: ImageMetadataFts)

    /**
     * Raw FTS delete. Do NOT call directly outside [replaceFts]; a delete
     * without the paired insert strands the record text-unfindable.
     */
    @Query("DELETE FROM image_metadata_fts WHERE contentUri = :uri")
    suspend fun deleteFtsByUri(uri: String)

    /**
     * DAO-owned FTS replace. The FTS table has no unique constraint, so a bare
     * REPLACE insert would duplicate rows; delete-then-insert inside one
     * transaction keeps exactly one FTS row per URI no matter the caller.
     */
    @Transaction
    suspend fun replaceFts(fts: ImageMetadataFts) {
        deleteFtsByUri(fts.contentUri)
        upsertFts(fts)
    }

    @Query("SELECT * FROM image_metadata ORDER BY dateTaken DESC")
    suspend fun getAll(): List<ImageMetadata>

    @Query("SELECT * FROM image_metadata ORDER BY dateTaken DESC")
    fun observeAll(): Flow<List<ImageMetadata>>

    @Query("SELECT * FROM image_metadata WHERE contentUri = :uri")
    suspend fun getByUri(uri: String): ImageMetadata?

    @Query("SELECT * FROM image_metadata WHERE clusterId = :clusterId ORDER BY dateTaken DESC")
    fun observeByCluster(clusterId: String): Flow<List<ImageMetadata>>

    @Query("SELECT contentUri FROM image_metadata")
    suspend fun getAllProcessedUris(): List<String>

    @Query("SELECT contentUri FROM image_metadata_fts")
    suspend fun getFtsUris(): List<String>

    @Query("SELECT * FROM image_metadata_fts WHERE contentUri = :uri")
    suspend fun getFtsByUri(uri: String): ImageMetadataFts?

    /**
     * URIs whose FULL record landed: metadata with a linked embedding plus
     * live embedding and FTS rows. This is the definition of done (see
     * `IndexPolicy.isComplete`); partial rows from an interrupted run are
     * excluded so they stay eligible and are never counted.
     *
     * Blank-description guard mirrors `isBlank()`: SQLite TRIM strips spaces
     * only, so the trim set covers tab/LF/VT/FF/CR explicitly, otherwise a
     * tab-only corrupt row would count as done here while the policy treats
     * it as not done.
     */
    @Query(
        """
        SELECT m.contentUri FROM image_metadata AS m
        WHERE m.embeddingId IS NOT NULL
          AND TRIM(m.description, ' ' || CHAR(9) || CHAR(10) || CHAR(11) || CHAR(12) || CHAR(13)) != ''
          AND EXISTS (SELECT 1 FROM image_embeddings AS e WHERE e.contentUri = m.contentUri)
          AND EXISTS (SELECT 1 FROM image_metadata_fts AS f WHERE f.contentUri = m.contentUri)
        """
    )
    suspend fun getCompleteUris(): List<String>

    /**
     * Home screen indexed count: complete records only. A second run over an
     * unchanged library writes nothing new, so this is stable across runs,
     * and half-written rows never inflate it.
     */
    @Query(
        """
        SELECT COUNT(*) FROM image_metadata AS m
        WHERE m.embeddingId IS NOT NULL
          AND TRIM(m.description, ' ' || CHAR(9) || CHAR(10) || CHAR(11) || CHAR(12) || CHAR(13)) != ''
          AND EXISTS (SELECT 1 FROM image_embeddings AS e WHERE e.contentUri = m.contentUri)
          AND EXISTS (SELECT 1 FROM image_metadata_fts AS f WHERE f.contentUri = m.contentUri)
        """
    )
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM image_metadata AS m
        WHERE m.embeddingId IS NOT NULL
          AND TRIM(m.description, ' ' || CHAR(9) || CHAR(10) || CHAR(11) || CHAR(12) || CHAR(13)) != ''
          AND EXISTS (SELECT 1 FROM image_embeddings AS e WHERE e.contentUri = m.contentUri)
          AND EXISTS (SELECT 1 FROM image_metadata_fts AS f WHERE f.contentUri = m.contentUri)
        """
    )
    suspend fun getIndexedCount(): Int

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

    @Query("DELETE FROM image_metadata_fts")
    suspend fun clearFts()
}
