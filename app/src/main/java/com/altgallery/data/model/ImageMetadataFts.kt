package com.altgallery.data.model

import androidx.room.Entity
import androidx.room.Fts4

// Standalone FTS4 table for the text-search half of the hybrid search.
// Populated alongside image_metadata writes (see MetadataRepository in M2/M6);
// joined back to image_metadata on contentUri at query time.
@Fts4
@Entity(tableName = "image_metadata_fts")
data class ImageMetadataFts(
    val contentUri: String,
    val displayName: String,
    val description: String,
    val ocrText: String?,
    val labels: String,
    val tags: String,
)
