package com.altgallery.ml

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives searchable tags from labels + OCR + description (spec §3e).
 *
 * Combines lowercased ML Kit labels, known meme-template matches, and salient
 * keywords pulled from the description. Order is preserved and duplicates dropped.
 */
@Singleton
class TagExtractor @Inject constructor() {

    fun extract(labels: List<String>, ocrText: String, description: String): List<String> {
        val tags = LinkedHashSet<String>()
        labels.forEach { tags += it.lowercase() }

        val combined = "$ocrText $description".lowercase()
        for ((template, keywords) in MEME_TEMPLATES) {
            if (keywords.count { it in combined } >= 2) tags += template
        }

        description.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 3 && it !in STOPWORDS }
            .take(10)
            .forEach { tags += it }

        return tags.toList()
    }

    companion object {
        // Extend with more templates as the meme library grows.
        private val MEME_TEMPLATES = mapOf(
            "drake" to listOf("drake", "hotline", "approve", "reject"),
            "distracted boyfriend" to listOf("distracted", "boyfriend", "girlfriend", "looking"),
            "expanding brain" to listOf("brain", "expanding", "galaxy", "panels"),
        )
        private val STOPWORDS = setOf(
            "the", "a", "an", "is", "in", "on", "at", "to", "of", "and", "with", "this", "that",
        )
    }
}
