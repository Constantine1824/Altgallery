package com.altgallery.ml

/**
 * Turns text into a dense vector for semantic search.
 *
 * Default implementation: [com.altgallery.ml.onnx.OnnxEmbeddingEngine] running a
 * quantized all-MiniLM-L6-v2 (384-dim). The model + vocab are files YOU supply —
 * see [com.altgallery.ml.onnx.ModelAssets]. Until they are present, [isReady]
 * returns false and [embed] throws [ModelUnavailableException].
 */
interface EmbeddingEngine {
    /** Embedding length (384 for MiniLM-L6). */
    val dimension: Int

    /** True once the underlying model + tokenizer files are available. */
    suspend fun isReady(): Boolean

    /**
     * Embed [text] into a unit-normalized [FloatArray] of length [dimension].
     * @throws ModelUnavailableException if the model files have not been provided.
     */
    suspend fun embed(text: String): FloatArray
}

/** Thrown when an engine is invoked but its model files are not present. */
class ModelUnavailableException(message: String) : Exception(message)
