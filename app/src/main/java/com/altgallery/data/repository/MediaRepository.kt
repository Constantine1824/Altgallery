package com.altgallery.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
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

    /** Current library row for one URI, or null when gone, unparseable, or unreadable. */
    suspend fun queryByUri(uriString: String): MediaImage? = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val id = try {
            ContentUris.parseId(Uri.parse(uriString))
        } catch (e: Exception) {
            return@withContext null
        }
        resolver.query(
            collection,
            PROJECTION,
            "${MediaStore.Images.Media._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.readImage(collection) else null
        }
    }

    /** Every image on the device, newest first. */
    suspend fun queryAllImages(): List<MediaImage> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val out = ArrayList<MediaImage>()
        resolver.query(collection, PROJECTION, null, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                out += cursor.readImage(collection)
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

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
        )

        private fun Cursor.readImage(collection: Uri): MediaImage {
            val idCol = getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedCol = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val widthCol = getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeCol = getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            val id = getLong(idCol)
            // DATE_TAKEN is millis and may be 0/absent; DATE_ADDED is seconds.
            val dateTaken = getLong(takenCol)
                .takeIf { it > 0 }
                ?: MediaImage.mediaStoreSecondsToMillis(getLong(addedCol))
            return MediaImage(
                contentUri = ContentUris.withAppendedId(collection, id),
                displayName = getString(nameCol) ?: "image_$id",
                dateTaken = dateTaken,
                width = getInt(widthCol),
                height = getInt(heightCol),
                mimeType = getString(mimeCol) ?: "image/*",
                dateModified = MediaImage.mediaStoreSecondsToMillis(getLong(modifiedCol)),
            )
        }
    }
}
