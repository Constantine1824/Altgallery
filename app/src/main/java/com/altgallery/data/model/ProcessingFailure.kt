package com.altgallery.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable record of a photo the pipeline could not index: the last run that
 * attempted it, what went wrong, and how many attempts it took.
 *
 * One row per content URI (the URI is the primary key, so a re-attempted
 * photo overwrites its old row instead of duplicating). Written once per
 * non-empty run by replacing the whole set with that run's failures
 * (see `MetadataRepository.recordRunFailures`), so a photo that recovers on
 * a later run disappears from the log on that run, and a photo deleted from
 * the library stops being re-recorded. Until the dedicated on-screen
 * failures view lands, this table plus the run summary is the failure
 * surface: it survives process death and is readable across runs.
 */
@Entity(tableName = "processing_failures")
data class ProcessingFailure(
    @PrimaryKey val contentUri: String,
    val displayName: String,
    val reason: String,
    val attempts: Int,
    val failedAt: Long,
)
