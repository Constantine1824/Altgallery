package com.altgallery.ml

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device generic image labeling via ML Kit (person, food, text, ...). No model
 * files to manage — the default labeler model ships with the ML Kit dependency.
 */
@Singleton
class LabelEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    suspend fun label(uri: Uri): List<ImageLabel> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromFilePath(context, uri)   // accepts content:// URIs
        labeler.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
