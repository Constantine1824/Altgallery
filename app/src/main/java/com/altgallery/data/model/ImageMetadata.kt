package com.altgallery.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_metadata",
    indices = [Index("clusterId"), Index("dateTaken")],
)
data class ImageMetadata(
    @PrimaryKey val contentUri: String,   // content://media/external/images/media/{id}
    val displayName: String,              // IMG_2041.jpg
    val filePath: String?,                // nullable — scoped storage may not expose
    val dateTaken: Long,                  // epoch millis
    val width: Int,
    val height: Int,
    val mimeType: String,                 // image/jpeg, image/png
    val isMeme: Boolean,                  // classifier output
    val description: String,              // generated caption (+ OCR appendix)
    val ocrText: String?,                 // extracted text if any
    val labels: String,                   // comma-separated ML Kit labels
    val tags: String,                     // comma-separated derived tags
    val clusterId: String,                // folder assignment
    val processedAt: Long,                // when this was indexed
    val modelVersion: String,             // e.g. "altgallery-v0.1"
    val embeddingId: Long?,               // FK to image_embeddings.id
)
