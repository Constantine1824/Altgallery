package com.altgallery.ml.onnx

/**
 * Minimal BERT/WordPiece tokenizer (uncased) — what all-MiniLM-L6-v2 expects.
 *
 * Construct from a plain `vocab.txt`: one token per line, where the line index is
 * the token id (the same vocab shipped with bert-base-uncased). Produces a
 * `[CLS] ... [SEP]` sequence using greedy longest-match subword splitting, padded
 * to [maxLen] with an attention mask.
 *
 * If you prefer the HuggingFace `tokenizer.json` format instead of `vocab.txt`,
 * this is the one class to swap — the engine only depends on [encode].
 */
class WordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Int> = buildMap {
        vocabLines.forEachIndexed { index, raw ->
            val token = raw.trim()
            if (token.isNotEmpty()) put(token, index)
        }
    }

    private val unkId = vocab[UNK] ?: 100
    private val clsId = vocab[CLS] ?: 101
    private val sepId = vocab[SEP] ?: 102
    private val padId = vocab[PAD] ?: 0

    data class Encoded(
        val ids: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    fun encode(text: String, maxLen: Int = 128): Encoded {
        val pieces = ArrayList<Int>(maxLen)
        pieces += clsId
        for (word in basicTokenize(text)) {
            if (pieces.size >= maxLen - 1) break
            for (piece in wordPiece(word)) {
                if (pieces.size >= maxLen - 1) break
                pieces += piece
            }
        }
        pieces += sepId

        val ids = LongArray(maxLen) { padId.toLong() }
        val mask = LongArray(maxLen)
        for (i in pieces.indices) {
            ids[i] = pieces[i].toLong()
            mask[i] = 1L
        }
        return Encoded(ids, mask, LongArray(maxLen)) // single sentence: token types all 0
    }

    // Lowercase, split on whitespace, peel punctuation into its own tokens.
    private fun basicTokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            when {
                ch.isWhitespace() -> if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                !ch.isLetterOrDigit() -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                    out += ch.toString()
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    // Greedy longest-match subword splitting with the ## continuation prefix.
    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unkId)
        val ids = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matchId: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { matchId = id; break }
                end--
            }
            if (matchId == null) return listOf(unkId)  // any unmatchable piece => whole word is UNK
            ids += matchId
            start = end
        }
        return ids
    }

    companion object {
        private const val UNK = "[UNK]"
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val PAD = "[PAD]"
        private const val MAX_WORD_CHARS = 100
    }
}
