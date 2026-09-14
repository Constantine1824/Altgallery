package com.altgallery.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.altgallery.data.model.MediaImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device photo library through MediaStore (scoped storage, content://).
 *
 * Requires the media-read permission (see [com.altgallery.permissions.MediaPermissions]).
 * Without it the query yields an empty list rather than throwing.
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Every image on the device, newest first. */
    suspend fun queryAllImages(): List<MediaImage> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val out = ArrayList<MediaImage>()
        resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                // DATE_TAKEN is millis and may be 0/absent; DATE_ADDED is seconds.
                val dateTaken = cursor.getLong(takenCol)
                    .takeIf { it > 0 }
                    ?: (cursor.getLong(addedCol) * 1000L)
                out += MediaImage(
                    contentUri = uri,
                    displayName = cursor.getString(nameCol) ?: "image_$id",
                    dateTaken = dateTaken,
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol),
                    mimeType = cursor.getString(mimeCol) ?: "image/*",
                )
            }
        }
        out
    }

    /**
     * Images present in MediaStore but not in [alreadyProcessed] (URI presence only).
     *
     * Prefer [MetadataRepository.findPending] for the definition of done: it also
     * treats partial records as pending and re-queues edited photos whose
     * dims/capture date drifted. This helper remains for callers that already
     * hold a URI set.
     */
    suspend fun queryUnprocessed(alreadyProcessed: Set<String>): List<MediaImage> =
        queryAllImages().filter { it.uriString !in alreadyProcessed }
}
