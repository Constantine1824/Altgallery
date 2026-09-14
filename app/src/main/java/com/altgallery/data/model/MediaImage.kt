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
    val dateModified: Long = 0L,  // file mtime, epoch millis (0 = unknown)
) {
    val uriString: String get() = contentUri.toString()

    companion object {
        /**
         * MediaStore DATE_ADDED / DATE_MODIFIED are whole seconds; the app
         * works in millis (DATE_TAKEN, processedAt). Non-positive means
         * absent -> 0 (unknown, never stale). The mtime freshness check
         * depends on this scaling, so it is unit-tested (see MediaImageTest);
         * getting it wrong fails silent-stale, the exact mode the pipeline
         * exists to close.
         */
        fun mediaStoreSecondsToMillis(seconds: Long): Long =
            if (seconds > 0) seconds * 1000L else 0L
    }
}
