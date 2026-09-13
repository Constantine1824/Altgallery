package com.altgallery.data.repository

import androidx.room.withTransaction
import com.altgallery.data.db.AltGalleryDatabase
import com.altgallery.data.db.EmbeddingDao
import com.altgallery.data.db.ImageMetadataDao
import com.altgallery.data.db.toByteArray
import com.altgallery.data.model.ImageEmbedding
import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists fully-processed photo records.
 *
 * The pipeline core (INDX-001) builds an [ImageMetadata], its embedding vector,
 * and its FTS row in memory, then lands them here in a single Room transaction.
 * Either all three land together or nothing does — a photo is never marked done
 * unless it is fully recorded and immediately findable by text search.
 *
 * Re-processing the same content URI is idempotent: stale embedding + FTS rows
 * for that URI are removed inside the same transaction before the fresh rows
 * are written.
 */
@Singleton
class MetadataRepository @Inject constructor(
    private val db: AltGalleryDatabase,
    private val metadataDao: ImageMetadataDao,
    private val embeddingDao: EmbeddingDao,
) {
    /**
     * Atomically writes one complete indexed record.
     *
     * @param metadata record without [ImageMetadata.embeddingId] (resolved here).
     * @param vector unit-normalized embedding for [metadata.description].
     * @return the persisted [ImageMetadata] with [ImageMetadata.embeddingId] set.
     */
    suspend fun saveCompleteRecord(metadata: ImageMetadata, vector: FloatArray): ImageMetadata {
        val fts = ImageMetadataFts(
            contentUri = metadata.contentUri,
            displayName = metadata.displayName,
            description = metadata.description,
            ocrText = metadata.ocrText,
            labels = metadata.labels,
            tags = metadata.tags,
        )
        val embeddingId = db.withTransaction {
            embeddingDao.deleteByUri(metadata.contentUri)
            metadataDao.deleteFtsByUri(metadata.contentUri)
            val id = embeddingDao.insert(
                ImageEmbedding(
                    contentUri = metadata.contentUri,
                    vector = vector.toByteArray(),
                ),
            )
            metadataDao.upsert(metadata.copy(embeddingId = id))
            metadataDao.upsertFts(fts)
            id
        }
        return metadata.copy(embeddingId = embeddingId)
    }

    suspend fun getByUri(uri: String): ImageMetadata? = metadataDao.getByUri(uri)

    suspend fun getAllProcessedUris(): List<String> = metadataDao.getAllProcessedUris()

    suspend fun searchLike(term: String): List<ImageMetadata> = metadataDao.searchLike(term)

    suspend fun searchFts(query: String): List<ImageMetadata> = metadataDao.searchFts(query)
}
