package com.altgallery.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.label.ImageLabel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the human-readable `description` for an image.
 *
 * The MVP binds [LabelOcrCaptionEngine], which composes a description from ML Kit
 * labels + OCR (no neural captioner, no model files). A real ONNX captioner
 * (BLIP-2 / Florence-2 / Moondream) slots in behind this interface as
 * [com.altgallery.ml.onnx.OnnxCaptionEngine] — see BUILD.md "Deferred by decision".
 *
 * [bitmap] is supplied for the neural impl and ignored by the default one.
 */
interface CaptionEngine {
    suspend fun caption(bitmap: Bitmap?, labels: List<ImageLabel>, ocrText: String): String
}

/**
 * Default, model-free captioner: "a photo containing X, Y and Z", with the OCR
 * text appended in the search-friendly form the pipeline embeds.
 */
@Singleton
class LabelOcrCaptionEngine @Inject constructor() : CaptionEngine {
    override suspend fun caption(
        bitmap: Bitmap?,
        labels: List<ImageLabel>,
        ocrText: String,
    ): String {
        val labelText = labels.map { it.text }
            .filter { it.isNotBlank() }
            .distinct()
        val base = when {
            labelText.isEmpty() -> "an image"
            labelText.size == 1 -> "a photo of ${labelText[0].lowercase()}"
            else -> "a photo containing " +
                labelText.dropLast(1).joinToString(", ") { it.lowercase() } +
                " and ${labelText.last().lowercase()}"
        }
        return buildString {
            append(base)
            if (ocrText.isNotBlank()) {
                append(" [TEXT IN IMAGE: ")
                append(ocrText.replace('\n', ' ').trim())
                append("]")
            }
        }
    }
}
