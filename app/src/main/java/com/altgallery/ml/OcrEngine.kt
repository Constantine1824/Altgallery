package com.altgallery.ml

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR via ML Kit Text Recognition v2 (Latin script). No model files to
 * manage — the recognizer is bundled with the ML Kit dependency.
 *
 * Returns the full [Text] result (not just the string) so callers like
 * [MemeClassifier] can inspect per-block bounding boxes.
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(uri: Uri): Text = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromFilePath(context, uri)   // accepts content:// URIs
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
