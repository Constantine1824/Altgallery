package com.altgallery.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.altgallery.ml.EmbeddingEngine
import com.altgallery.ml.ModelUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * all-MiniLM-L6-v2 embeddings via ONNX Runtime Mobile: mean-pooled over the token
 * embeddings and L2-normalized to a 384-dim unit vector. The model + vocab come
 * from [ModelAssets]; nothing is bundled in the APK.
 *
 * The session and tokenizer load lazily on first [embed] and are cached.
 *
 * MODEL-SPECIFIC KNOBS (the only things tied to a particular export):
 *  - input node names: [INPUT_IDS], [ATTENTION_MASK], [TOKEN_TYPE_IDS]
 *  - output 0 is assumed to be token embeddings of shape [1, seq, 384].
 *    If your export differs (e.g. names, or it already pools), adjust here.
 */
@Singleton
class OnnxEmbeddingEngine @Inject constructor(
    private val models: ModelAssets,
) : EmbeddingEngine {

    override val dimension = 384

    private val loadLock = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null

    override suspend fun isReady(): Boolean =
        models.embeddingModel.exists() && models.embeddingVocab.exists()

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val (session, tokenizer) = ensureLoaded()
        val env = OrtEnvironment.getEnvironment()
        val enc = tokenizer.encode(text)
        val shape = longArrayOf(1, enc.ids.size.toLong())

        OnnxTensor.createTensor(env, LongBuffer.wrap(enc.ids), shape).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape).use { mask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape).use { types ->
                    val inputs = HashMap<String, OnnxTensor>().apply {
                        put(INPUT_IDS, ids)
                        put(ATTENTION_MASK, mask)
                        if (session.inputNames.contains(TOKEN_TYPE_IDS)) put(TOKEN_TYPE_IDS, types)
                    }
                    session.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result.get(0).value as Array<Array<FloatArray>>  // [1, seq, dim]
                        meanPool(hidden[0], enc.attentionMask).also(::normalize)
                    }
                }
            }
        }
    }

    private suspend fun ensureLoaded(): Pair<OrtSession, WordPieceTokenizer> = loadLock.withLock {
        session?.let { s -> tokenizer?.let { t -> return@withLock Pair(s, t) } }
        if (!isReady()) {
            throw ModelUnavailableException(
                "Embedding model not installed. Provide '${models.embeddingModel.name}' and " +
                    "'${models.embeddingVocab.name}' via ModelAssets."
            )
        }
        val env = OrtEnvironment.getEnvironment()
        val newSession = env.createSession(
            models.embeddingModel.readBytes(),
            OrtSession.SessionOptions(),
        )
        val vocabLines = String(models.embeddingVocab.readBytes(), Charsets.UTF_8).split('\n')
        val newTokenizer = WordPieceTokenizer(vocabLines)
        session = newSession
        tokenizer = newTokenizer
        Pair(newSession, newTokenizer)
    }

    // Average token vectors over the unmasked positions.
    private fun meanPool(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = if (tokens.isNotEmpty()) tokens[0].size else dimension
        val sum = FloatArray(dim)
        var count = 0f
        for (i in tokens.indices) {
            if (i < mask.size && mask[i] == 0L) continue
            count += 1f
            val row = tokens[i]
            for (d in 0 until dim) sum[d] += row[d]
        }
        if (count > 0f) for (d in 0 until dim) sum[d] /= count
        return sum
    }

    private fun normalize(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
    }

    companion object {
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"
    }
}
