package com.altgallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes content:// images into downsampled [Bitmap]s for ML processing.
 *
 * Full-resolution photos can be tens of megapixels; ML Kit and the ONNX models
 * work fine on a downscaled copy, and decoding at full size risks OOM during
 * batch processing. [load] returns a bitmap roughly [maxEdge] px on its longest
 * side, or null if the image cannot be decoded (corrupt / unreadable URI).
 */
@Singleton
class BitmapLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun load(uri: Uri, maxEdge: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        // First pass: read bounds only.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        // Compute a power-of-two downsample factor.
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }
}
