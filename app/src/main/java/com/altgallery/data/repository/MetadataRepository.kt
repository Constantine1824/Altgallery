package com.altgallery.data.repository

import androidx.room.withTransaction
import com.altgallery.data.db.AltGalleryDatabase
import com.altgallery.data.db.EmbeddingDao
import com.altgallery.data.db.ImageMetadataDao
import com.altgallery.data.db.ProcessingFailureDao
import com.altgallery.data.db.toByteArray
import com.altgallery.data.model.ImageEmbedding
import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts
import com.altgallery.data.model.MediaImage
import com.altgallery.data.model.ProcessingFailure
import com.altgallery.processing.IndexPolicy
import com.altgallery.processing.PhotoFailure
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists fully-processed photo records and defines "already processed".
 *
 * The pipeline core (INDX-001) builds an [ImageMetadata], its embedding vector,
 * and its FTS row in memory (see `RecordAssembler`, the single FTS derivation
 * point), then lands them here in a single Room transaction. Either all three
 * land together or nothing does — a photo is never marked done unless it is
 * fully recorded and immediately findable by text search. The FTS row is
 * persisted as given, not re-derived, so the copy the tests pin is the copy
 * that ships.
 *
 * Done ([IndexPolicy]) means the FULL record landed (metadata + linked
 * embedding row + FTS row) AND the stored dims/capture date still match the
 * library with no newer file modification. A half-written record (killed
 * mid-run) is not done and stays eligible; an edited/replaced photo
 * (dims/date drifted, or mtime newer than the last index) becomes pending
 * again. Re-processing the same content URI is idempotent: stale embedding +
 * FTS rows for that URI are removed inside the same transaction before the
 * fresh rows are written, so a photo is still counted exactly once.
 */
@Singleton
class MetadataRepository @Inject constructor(
    private val db: AltGalleryDatabase,
    private val metadataDao: ImageMetadataDao,
    private val embeddingDao: EmbeddingDao,
    private val failureDao: ProcessingFailureDao,
) {
    /**
     * Atomically writes one complete indexed record.
     *
     * All four writes run inside a single Room transaction ([performSave]):
     * stale embedding deleted, fresh embedding inserted, metadata upserted
     * with the new embedding id, FTS row replaced via
     * [ImageMetadataDao.replaceFts]. Either the whole record lands or a throw
     * aborts before any later write, so a killed run can only leave a partial
     * record that [findPending]/[isDone] still treat as not done.
     *
     * @param metadata record without [ImageMetadata.embeddingId] (resolved here).
     * @param fts the caller-built search row for [metadata] (see
     *   `RecordAssembler.ftsFor`); persisted as given.
     * @param vector unit-normalized embedding for [metadata.description].
     * @return the persisted [ImageMetadata] with [ImageMetadata.embeddingId] set.
     */
    suspend fun saveCompleteRecord(
        metadata: ImageMetadata,
        fts: ImageMetadataFts,
        vector: FloatArray,
    ): ImageMetadata {
        require(metadata.contentUri == fts.contentUri) {
            "metadata and FTS rows must share the content URI"
        }
        val embeddingId = db.withTransaction {
            performSave(metadata, fts, vector)
        }
        return metadata.copy(embeddingId = embeddingId)
    }

    /**
     * The write sequence behind [saveCompleteRecord], extracted so the order
     * and throw-skips-later-writes contract is pinned by JVM tests with fake
     * DAOs (see `SaveTransactionTest`). Production always invokes it inside
     * [db.withTransaction]; the SQLite atomicity itself is Room's guarantee.
     */
    internal suspend fun performSave(
        metadata: ImageMetadata,
        fts: ImageMetadataFts,
        vector: FloatArray,
    ): Long = saveRecordSteps(metadataDao, embeddingDao, metadata, fts, vector)

    suspend fun getByUri(uri: String): ImageMetadata? = metadataDao.getByUri(uri)

    /** URI presence only (includes partial rows). Prefer [getCompleteUris] / [findPending] for the definition of done. */
    suspend fun getAllProcessedUris(): List<String> = metadataDao.getAllProcessedUris()

    /** URIs whose full record landed (definition of done). */
    suspend fun getCompleteUris(): List<String> = metadataDao.getCompleteUris()

    /** One-shot indexed count for the home screen: complete records only. */
    suspend fun getIndexedCount(): Int = metadataDao.getIndexedCount()

    /** Home screen's indexed count: complete records only, stable across re-runs. */
    fun observeIndexedCount(): Flow<Int> = metadataDao.observeCount()

    /**
     * True only when the photo's full record landed AND matches [image].
     * Missing metadata, missing embedding/FTS rows, null/blank linkage, or
     * drifted dims/capture date all mean "not done". A file modification newer
     * than the last index time also means "not done" (in-place edit that kept
     * dims and capture date).
     */
    suspend fun isDone(image: MediaImage): Boolean {
        val metadata = metadataDao.getByUri(image.uriString) ?: return false
        if (!IndexPolicy.isComplete(
                metadata,
                hasEmbedding = embeddingDao.getByUri(image.uriString) != null,
                hasFts = metadataDao.getFtsByUri(image.uriString) != null,
            )
        ) return false
        return IndexPolicy.isFresh(
            metadata,
            image.width,
            image.height,
            image.dateTaken,
            image.dateModified,
        )
    }

    /**
     * Pending subset of [images] in input order: not-done photos (missing or
     * partial records) plus complete records whose dims/date drifted or whose
     * file was modified after indexing (edited/replaced on device). An
     * unchanged re-run returns empty, so the second pass does no work and the
     * indexed count cannot move.
     */
    suspend fun findPending(images: List<MediaImage>): List<MediaImage> {
        if (images.isEmpty()) return emptyList()
        val metadataByUri = metadataDao.getAll().associateBy { it.contentUri }
        val embeddingUris = embeddingDao.getAllUris().toSet()
        val ftsUris = metadataDao.getFtsUris().toSet()
        val library = images.map {
            IndexPolicy.LibraryPhoto(it.uriString, it.width, it.height, it.dateTaken, it.dateModified)
        }
        val storedByUri = library.associate { photo ->
            val metadata = metadataByUri[photo.contentUri]
            photo.contentUri to IndexPolicy.StoredState(
                metadata = metadata,
                hasEmbedding = photo.contentUri in embeddingUris,
                hasFts = photo.contentUri in ftsUris,
            )
        }
        val pendingUris = IndexPolicy.findPending(library, storedByUri)
            .map { it.contentUri }
            .toSet()
        return images.filter { it.uriString in pendingUris }
    }

    suspend fun searchLike(term: String): List<ImageMetadata> = metadataDao.searchLike(term)

    suspend fun searchFts(query: String): List<ImageMetadata> = metadataDao.searchFts(query)

    /**
     * Replaces the durable failure log with this run's failures (empty clears
     * it after a clean run), in one transaction. A failed photo writes no
     * index record, so it stays pending and is re-attempted with a fresh
     * budget on the next run; the log is what keeps repeat offenders visible
     * across runs instead of failing silently. A photo that recovers drops
     * out of the log on the recovering run, and a deleted photo stops being
     * re-recorded.
     */
    suspend fun recordRunFailures(failures: List<PhotoFailure>) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            replaceFailureSteps(failureDao, failures, now)
        }
    }

    /** Last run's failure log, newest first. Empty when the last run was clean. */
    suspend fun getRecordedFailures(): List<ProcessingFailure> = failureDao.getAll()

    /** Observable failure log for the future on-screen failures view. */
    fun observeRecordedFailures(): Flow<List<ProcessingFailure>> = failureDao.observeAll()
}

/**
 * Ordered write sequence for one complete record: delete the stale embedding,
 * insert the fresh one, upsert metadata with the new embedding id, replace
 * the FTS row. A throw aborts before any later write. Top-level (instead of a
 * method) so JVM tests can drive it with fake DAOs and no database.
 */
internal suspend fun saveRecordSteps(
    metadataDao: ImageMetadataDao,
    embeddingDao: EmbeddingDao,
    metadata: ImageMetadata,
    fts: ImageMetadataFts,
    vector: FloatArray,
): Long {
    embeddingDao.deleteByUri(metadata.contentUri)
    val id = embeddingDao.insert(
        ImageEmbedding(
            contentUri = metadata.contentUri,
            vector = vector.toByteArray(),
        ),
    )
    metadataDao.upsert(metadata.copy(embeddingId = id))
    metadataDao.replaceFts(fts)
    return id
}

/**
 * Ordered write sequence for one run's failure log: clear the previous run's
 * rows, then insert this run's failures stamped with [failedAt]. A throw
 * aborts before any later write. Top-level (instead of a method) so JVM tests
 * can drive it with a fake DAO and no database.
 */
internal suspend fun replaceFailureSteps(
    failureDao: ProcessingFailureDao,
    failures: List<PhotoFailure>,
    failedAt: Long,
) {
    failureDao.clear()
    if (failures.isNotEmpty()) {
        failureDao.upsertAll(
            failures.map {
                ProcessingFailure(
                    contentUri = it.contentUri,
                    displayName = it.displayName,
                    reason = it.reason,
                    attempts = it.attempts,
                    failedAt = failedAt,
                )
            },
        )
    }
}
