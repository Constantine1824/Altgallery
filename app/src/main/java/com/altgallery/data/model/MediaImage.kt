package com.altgallery.data.model

import android.net.Uri

/**
 * A single image discovered in MediaStore, before any ML processing.
 *
 * This is the raw input to the processing pipeline (M4). It is intentionally
 * NOT a Room entity — once processed, an image is persisted as [ImageMetadata].
 */
data class MediaImage(
    val contentUri: Uri,          // content://media/external/images/media/{id}
    val displayName: String,      // IMG_2041.jpg
    val dateTaken: Long,          // epoch millis (falls back to dateAdded when absent)
    val width: Int,
    val height: Int,
    val mimeType: String,         // image/jpeg, image/png, ...
) {
    val uriString: String get() = contentUri.toString()
}
