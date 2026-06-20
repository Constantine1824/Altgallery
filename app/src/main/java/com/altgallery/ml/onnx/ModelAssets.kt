package com.altgallery.ml.onnx

import android.content.Context
import com.altgallery.ml.ModelUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the on-device ML model files and how they are
 * loaded. THIS is the file to touch when you manage models by hand.
 *
 * Resolution order for each logical model:
 *   1. <filesDir>/models/<name>   — files you push or download at runtime (preferred)
 *   2. assets/<name>              — files bundled in the APK (optional)
 *
 * Nothing here ships a model. Until you place the files, [ModelFile.exists]
 * returns false and the engines report "not ready" instead of crashing — so the
 * app builds and runs through M3 with the model-free caption fallback while
 * embeddings simply wait for their model.
 *
 * To install the embedding model by hand on a connected device/emulator:
 *   adb shell run-as com.altgallery mkdir -p files/models
 *   adb push minilm.onnx /data/local/tmp/
 *   adb shell run-as com.altgallery cp /data/local/tmp/minilm.onnx files/models/
 *   adb push vocab.txt   /data/local/tmp/
 *   adb shell run-as com.altgallery cp /data/local/tmp/vocab.txt   files/models/
 *
 * (The "real" UX is the M8 first-run download into filesDir; the path above is
 * the same destination, just populated manually.)
 */
@Singleton
class ModelAssets @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Quantized sentence-transformer (all-MiniLM-L6-v2), ONNX. */
    val embeddingModel = ModelFile("minilm.onnx")

    /** WordPiece vocabulary for the embedding tokenizer (one token per line). */
    val embeddingVocab = ModelFile("vocab.txt")

    /** Optional neural image captioner (BLIP-2 / Florence-2 / Moondream), ONNX. */
    val captionModel = ModelFile("caption.onnx")

    /** The directory where manually-installed / downloaded models live. */
    fun modelsDir(): File = File(context.filesDir, MODELS_DIR)

    inner class ModelFile(val name: String) {
        private val external: File get() = File(modelsDir(), name)

        /** True if the file is available either in filesDir or bundled in assets. */
        fun exists(): Boolean = external.exists() || existsInAssets()

        /** Load the whole file into memory (ONNX sessions take a byte array). */
        fun readBytes(): ByteArray = when {
            external.exists() -> external.readBytes()
            existsInAssets() -> context.assets.open(name).use { it.readBytes() }
            else -> throw ModelUnavailableException(
                "Model '$name' not found. Place it in filesDir/$MODELS_DIR/ or app/src/main/assets/."
            )
        }

        private fun existsInAssets(): Boolean =
            runCatching { context.assets.open(name).use { } }.isSuccess
    }

    companion object {
        const val MODELS_DIR = "models"
    }
}
