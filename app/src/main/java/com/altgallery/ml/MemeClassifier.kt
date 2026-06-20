package com.altgallery.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.text.Text
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Heuristic meme detector — no trained model (spec §3b).
 *
 * Scores several cheap signals and calls an image a meme at >= [MEME_THRESHOLD]
 * points. Tune the threshold/weights against a labeled sample; the spec targets
 * >85% precision. [score] is exposed so tests and tuning can inspect raw points.
 */
@Singleton
class MemeClassifier @Inject constructor() {

    fun classify(
        ocr: Text,
        labels: List<ImageLabel>,
        width: Int,
        height: Int,
        bitmap: Bitmap?,
    ): Boolean = score(ocr, labels, width, height, bitmap) >= MEME_THRESHOLD

    fun score(
        ocr: Text,
        labels: List<ImageLabel>,
        width: Int,
        height: Int,
        bitmap: Bitmap?,
    ): Int {
        var score = 0
        val text = ocr.text

        // 1. Overlaid text covering a meaningful fraction of the image.
        if (width > 0 && height > 0 && textAreaRatio(ocr, width, height) > 0.15f) score++

        // 2. Predominantly uppercase (impact-style captions).
        if (text.length > 5 && text == text.uppercase() && text.any { it.isLetter() }) score++

        // 3. Square / near-square aspect ratio (common meme format).
        if (height > 0) {
            val aspect = width.toFloat() / height
            if (aspect in 0.8f..1.3f) score++
        }

        // 4. A high-confidence "Text" label.
        if (labels.any { it.text.equals("Text", ignoreCase = true) && it.confidence > 0.7f }) score++

        // 5. Solid border bands top/bottom (caption-meme format).
        if (bitmap != null && hasSolidBorderBands(bitmap)) score++

        return score
    }

    private fun textAreaRatio(ocr: Text, width: Int, height: Int): Float {
        val imageArea = width.toLong() * height.toLong()
        if (imageArea <= 0) return 0f
        var textArea = 0L
        for (block in ocr.textBlocks) {
            val box = block.boundingBox ?: continue
            textArea += box.width().toLong() * box.height().toLong()
        }
        return (textArea.toFloat() / imageArea).coerceIn(0f, 1f)
    }

    // True if the top or bottom band is a near-uniform solid color.
    private fun hasSolidBorderBands(bitmap: Bitmap): Boolean {
        val band = (bitmap.height * 0.12f).toInt().coerceAtLeast(1)
        return isBandSolid(bitmap, 0, band) ||
            isBandSolid(bitmap, bitmap.height - band, bitmap.height)
    }

    private fun isBandSolid(bitmap: Bitmap, top: Int, bottom: Int): Boolean {
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = ((bottom - top) / 4).coerceAtLeast(1)
        val reference = bitmap.getPixel(0, top.coerceIn(0, bitmap.height - 1))
        var samples = 0
        var matches = 0
        var y = top
        while (y < bottom && y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                samples++
                if (colorClose(bitmap.getPixel(x, y), reference)) matches++
                x += stepX
            }
            y += stepY
        }
        return samples > 0 && matches.toFloat() / samples > 0.9f
    }

    private fun colorClose(a: Int, b: Int): Boolean {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db < 24
    }

    companion object {
        const val MEME_THRESHOLD = 2
    }
}
