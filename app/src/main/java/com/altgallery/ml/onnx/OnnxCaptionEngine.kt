package com.altgallery.ml.onnx

import android.graphics.Bitmap
import com.altgallery.ml.CaptionEngine
import com.altgallery.ml.ModelUnavailableException
import com.google.mlkit.vision.label.ImageLabel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scaffold for a real neural image captioner (BLIP-2 / Florence-2 / Moondream)
 * running under ONNX Runtime Mobile.
 *
 * This is intentionally NOT wired up by default — the MVP binds
 * [com.altgallery.ml.LabelOcrCaptionEngine]. Captioning is a substantial,
 * model-specific effort (image preprocessing + autoregressive decode loop +
 * tokenizer/detokenizer), so the body is left for you to implement once you've
 * chosen and exported a model. See BUILD.md "Deferred by decision".
 *
 * To activate it:
 *   1. Drop your model file where [ModelAssets.captionModel] looks for it.
 *   2. Implement [caption]: preprocess [bitmap] to the model's pixel tensor,
 *      run the encoder, then greedily/beam-decode tokens to text.
 *   3. In MlModule, change provideCaptionEngine to return this instead of
 *      LabelOcrCaptionEngine.
 */
@Singleton
class OnnxCaptionEngine @Inject constructor(
    private val models: ModelAssets,
) : CaptionEngine {

    suspend fun isReady(): Boolean = models.captionModel.exists()

    override suspend fun caption(
        bitmap: Bitmap?,
        labels: List<ImageLabel>,
        ocrText: String,
    ): String {
        // TODO(model): preprocess(bitmap) -> pixel tensor -> encoder -> decode loop -> text.
        throw ModelUnavailableException(
            "OnnxCaptionEngine is a scaffold. Implement caption() for your chosen model, " +
                "or keep LabelOcrCaptionEngine as the bound CaptionEngine."
        )
    }
}
